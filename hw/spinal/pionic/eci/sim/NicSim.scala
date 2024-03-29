package pionic.eci.sim

import jsteward.blocks.eci.sim.DcsAppMaster
import pionic.eci.EciInterfacePlugin
import pionic.sim.{AsSimBusMaster, CSRSim, XilinxCmacSim, SimApp}
import pionic.{Config, ConfigWriter, NicEngine, PioNicConfig, XilinxCmacPlugin}
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}

import scala.util._
import scala.util.control.TailCalls._

case class CtrlInfoSim(size: BigInt, addr: BigInt)
object CtrlInfoSim {
  def fromBigInt(v: BigInt, nextCl: Int)(implicit config: PioNicConfig) =
    CtrlInfoSim(v & config.pktBufLenMask,
      // since we flipped nextCl already
      (1 - nextCl) * 0x80 + 0x40)
}

object NicSim extends SimApp {
  implicit val axiliteAsMaster = new AsSimBusMaster[AxiLite4Master] {
    def read(b: AxiLite4Master, addr: BigInt, totalBytes: BigInt) = b.read(addr, totalBytes)
    def write(b: AxiLite4Master, addr: BigInt, data: List[Byte]) = b.write(addr, data)
  }
  implicit val nicConfig = PioNicConfig()

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .workspaceName("eci")
    .compile(pionic.GenEngineVerilog.engineFromName("eci"))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("coreControl")

    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_ctrl_axil, dut.clockDomain)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    dut.clockDomain.forkStimulus(period = 4)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, csrMaster, rxBlockCycles)(nicConfig)

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

  var rxNextCl: Int = 0
  def tryReadPacketDesc(dcsMaster: DcsAppMaster, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[CtrlInfoSim]] = {
    if (maxTries == 0) done(None)
    else {
      val clAddr = rxNextCl * 0x80 + dut.host[ConfigWriter].getConfig[Int]("eci rx base")
      println(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      val control = dcsMaster.read(clAddr, 64).bytesToBigInt
      // always toggle cacheline
      rxNextCl = 1 - rxNextCl
      if ((control & 1) == 0) {
        sleepCycles(20)
        tailcall(tryReadPacketDesc(dcsMaster, maxTries - 1))
      } else {
        // got packet!
        done(Some(CtrlInfoSim.fromBigInt(control >> 1, rxNextCl)))
      }
    }
  }

  def rxSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, toSend: List[Byte])(implicit dut: NicEngine): Unit = {
    fork {
      sleepCycles(20)
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // TODO: check performance counters

    val info = tryReadPacketDesc(dcsMaster).result.get
    println(s"Received status register: $info")
    assert(info.size == toSend.size, s"packet length mismatch: expected ${toSend.length}, got ${info.size}")

    // read memory and check data
    val firstReadSize = if (toSend.size > 64) 64 else toSend.size
    var data = dcsMaster.read(info.addr, firstReadSize)
    if (toSend.size > 64) {
      data ++= dcsMaster.read(dut.host[ConfigWriter].getConfig[Int]("eci rx overflow"), toSend.size - 64)
    }

    assert(data == toSend,
      s"""data mismatch:
         |expected: "${toSend.bytesToHex}"
         |got:      "${data.bytesToHex}"
         |""".stripMargin)

    println(s"Successfully received packet $info")

    // packet will be acknowledged by reading next packet
  }

  test("rx-regular") { implicit dut =>
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("coreControl")

    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(10000)

    assert(tryReadPacketDesc(dcsMaster).result.isEmpty, "should not have packet on standby yet");

    // set core mask to only schedule to core 0
    val mask = b"00000001"
    csrMaster.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // reset packet allocator
    csrMaster.write(coreBlock("allocReset"), 1.toBytes)
    sleepCycles(200)
    csrMaster.write(coreBlock("allocReset"), 0.toBytes)

    // sweep from 64B to 9600B
    for (size <- Iterator.from(1).map(_ * 64).takeWhile(_ <= 9618)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        rxSimple(dcsMaster, axisMaster, toSend)
      }
    }

    // TODO: check DCS master cacheline state
  }

  var txNextCl: Int = 0
  def txSimple(dcsMaster: DcsAppMaster, axisSlave: Axi4StreamSlave, toSend: List[Byte])(implicit dut: NicEngine): Unit = {
    var received = false
    fork {
      val data = axisSlave.recv()

      assert(data == toSend,
        s"""data mismatch:
           |expected: "${toSend.bytesToHex}"
           |got:      "${data.bytesToHex}"
           |""".stripMargin)

      println(s"Packet received from TX interface and validated")

      received = true
    }

    def clAddr = txNextCl * 0x80 + dut.host[ConfigWriter].getConfig[Int]("eci tx base")
    println(f"Writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, toSend.length.toBytes)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      dcsMaster.write(dut.host[ConfigWriter].getConfig[Int]("eci tx overflow"), toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline
    // FIXME: we shouldn't need to do this manually here (only due to us wanting to check the response in the same func)
    println(s"Sent packet at $clAddr, waiting validation...")

    txNextCl = 1 - txNextCl
    dcsMaster.read(clAddr, 1)

    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  test("tx-regular") { implicit dut =>
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("coreControl")

    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()

    // sweep from 64B to 9600B
    for (size <- Iterator.from(1).map(_ * 64).takeWhile(_ <= 9618)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        txSimple(dcsMaster, axisSlave, toSend)
      }
    }
  }
}
