package pionic.eci.sim

import jsteward.blocks.eci.sim.DcsAppMaster
import pionic.eci.EciInterfacePlugin
import pionic.sim.{AsSimBusMaster, CSRSim, XilinxCmacSim}
import pionic.{Config, NicEngine, PioNicConfig, XilinxCmacPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.Axi4StreamMaster

import scala.util._
import scala.util.control.TailCalls._

case class CtrlInfoSim(size: BigInt)
object CtrlInfoSim {
  def fromBigInt(v: BigInt)(implicit config: PioNicConfig) = CtrlInfoSim(v & config.pktBufLenMask)
}

object NicSim extends App {
  implicit val axiAsMaster = new AsSimBusMaster[AxiLite4Master] {
    def read(b: AxiLite4Master, addr: BigInt, totalBytes: BigInt) = b.read(addr, totalBytes)
    def write(b: AxiLite4Master, addr: BigInt, data: List[Byte]) = b.write(addr, data)
  }
  implicit val nicConfig = PioNicConfig()

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .compile(pionic.GenEngineVerilog.engineFromName("eci"))

  def cyc(c: Int)(implicit dut: NicEngine): TimeNumber = dut.clockDomain.frequency.getValue.toTime * c / 1000

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("coreControl")

    dut.clockDomain.forkStimulus(period = 4)

    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_ctrl_axil, dut.clockDomain)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, csrMaster, rxBlockCycles)(nicConfig)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)
    (csrMaster, axisMaster, dcsMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles
    (csrMaster, axisSlave, dcsMaster)
  }

  var nextCl: Int = 0
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[CtrlInfoSim]] = {
    if (maxTries == 0) done(None)
    else {
      println(s"Reading packet desc, $maxTries times left...")
      // read ctrl in first
      val control = dcsMaster.read(nextCl * 0x80, 64).bytesToBigInt
      if ((control & 1) == 0) {
        sleep(cyc(20))
        tailcall(tryReadPacketDesc(dcsMaster, maxTries - 1))
      } else {
        // got packet!
        nextCl = 1 - nextCl
        done(Some(CtrlInfoSim.fromBigInt(control >> 1)))
      }
    }
  }

  def rxSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, toSend: List[Byte])(implicit dut: NicEngine): Unit = {
    fork {
      sleep(cyc(20))
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // TODO: check performance counters

    val info = tryReadPacketDesc(dcsMaster).result.get
    println(s"Received status register: $info")
    assert(info.size == toSend.size, s"packet length mismatch: expected ${toSend.length}, got ${info.size}")

    // read memory and check data
    var data = dcsMaster.read(nextCl * 0x80 + 0x40, 64)
    if (toSend.size > 64) {
      data = data ++ dcsMaster.read(0x100, toSend.size - 64)
    }

    assert(data == toSend,
      s"""data mismatch:
         |expected: "${toSend.bytesToHex}"
         |got:      "${data.bytesToHex}"
         |""".stripMargin)

    // next packet will be acknowledged by reading next packet
  }

  dut.doSim("rx-regular") { implicit dut =>
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("coreControl")

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(100)

    assert(tryReadPacketDesc(dcsMaster).result.isEmpty, "should not have packet on standby yet");

    // set core mask to only schedule to core 0
    val mask = b"00000001"
    csrMaster.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // reset packet allocator
    csrMaster.write(coreBlock("allocReset"), 1.toBytes);
    sleep(cyc(200))
    csrMaster.write(coreBlock("allocReset"), 0.toBytes);

    // test for 200 runs
    for (size <- Iterator.from(1).map(_ * 64).takeWhile(_ <= 512)) {
      val toSend = Random.nextBytes(size).toList
      0 until 50 foreach { _ => rxSimple(dcsMaster, axisMaster, toSend) }
    }

    // TODO: check DCS master cacheline state
  }
}
