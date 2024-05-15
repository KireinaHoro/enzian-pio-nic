package pionic.eci

import jsteward.blocks.eci.sim.DcsAppMaster
import pionic._
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axilite.sim.AxiLite4Master
import spinal.lib.bus.amba4.axis.sim.{Axi4StreamMaster, Axi4StreamSlave}

import scala.collection.mutable
import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._

import org.scalatest.tagobjects.Slow

case class CtrlInfoSim(size: BigInt, addr: BigInt)

object CtrlInfoSim {
  def fromBigInt(v: BigInt, nextCl: Int, cid: Int)(implicit config: PioNicConfig, dut: NicEngine) =
    CtrlInfoSim(v & config.pktBufLenMask,
      // since we flipped nextCl already
      (1 - nextCl) * 0x80 + 0x40 +
        dut.host[ConfigWriter].getConfig[Int]("eci rx base") +
        dut.host[ConfigWriter].getConfig[Int]("eci core offset") * cid)
}

class NicSim extends DutSimFunSuite[NicEngine] {
  implicit val nicConfig = PioNicConfig()

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    .workspaceName("eci")
    .compile(pionic.GenEngineVerilog.engineFromName("eci"))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    val eciIf = dut.host[EciInterfacePlugin].logic.get
    val csrMaster = AxiLite4Master(eciIf.s_axil_ctrl, dut.clockDomain)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    val dcsAppMaster = DcsAppMaster(eciIf.dcsEven, eciIf.dcsOdd, dut.clockDomain)

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, csrMaster, rxBlockCycles)(nicConfig)

    (csrMaster, axisMaster, axisSlave, dcsAppMaster)
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get
    for (i <- rxNextCl.indices) { rxNextCl(i) = 0 }

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (csrMaster, axisMaster, _, dcsMaster) = commonDutSetup(rxBlockCycles)
    (csrMaster, axisMaster, dcsMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (csrMaster, _, axisSlave, dcsMaster) = commonDutSetup(10000) // arbitrary rxBlockCycles
    for (i <- txNextCl.indices) { txNextCl(i) = 0 }

    (csrMaster, axisSlave, dcsMaster)
  }

  val rxNextCl = mutable.ArrayBuffer.fill(nicConfig.numCores)(0)

  def tryReadPacketDesc(dcsMaster: DcsAppMaster, cid: Int, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[CtrlInfoSim]] = {
    if (maxTries == 0) done(None)
    else {
      val clAddr = rxNextCl(cid) * 0x80 +
        dut.host[ConfigWriter].getConfig[Int]("eci rx base") +
        dut.host[ConfigWriter].getConfig[Int]("eci core offset") * cid
      println(f"Reading packet desc at $clAddr%#x, $maxTries times left...")
      // read ctrl in first
      val control = dcsMaster.read(clAddr, 64).bytesToBigInt
      // always toggle cacheline
      rxNextCl(cid) = 1 - rxNextCl(cid)
      if ((control & 1) == 0) {
        sleepCycles(20)
        tailcall(tryReadPacketDesc(dcsMaster, cid, maxTries - 1))
      } else {
        // got packet!
        done(Some(CtrlInfoSim.fromBigInt(control >> 1, rxNextCl(cid), cid)))
      }
    }
  }

  def rxSimple(dcsMaster: DcsAppMaster, axisMaster: Axi4StreamMaster, toSend: List[Byte], cid: Int = 0)(implicit dut: NicEngine): Unit = {
    fork {
      sleepCycles(20)
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    // TODO: check performance counters

    val info = tryReadPacketDesc(dcsMaster, cid).result.get
    println(s"Received status register: $info")
    assert(info.size == toSend.size, s"packet length mismatch: expected ${toSend.length}, got ${info.size}")

    // read memory and check data
    val firstReadSize = if (toSend.size > 64) 64 else toSend.size
    var data = dcsMaster.read(info.addr, firstReadSize)
    if (toSend.size > 64) {
      data ++= dcsMaster.read(
        dut.host[ConfigWriter].getConfig[Int]("eci rx base") +
          dut.host[ConfigWriter].getConfig[Int]("eci overflow offset") +
          dut.host[ConfigWriter].getConfig[Int]("eci core offset") * cid,
        toSend.size - 64)
    }

    assert(data == toSend,
      s"""data mismatch:
         |expected: "${toSend.bytesToHex}"
         |got:      "${data.bytesToHex}"
         |""".stripMargin)

    println(s"Successfully received packet $info")

    // packet will be acknowledged by reading next packet
  }

  def rxTestRange(csrMaster: AxiLite4Master, axisMaster: Axi4StreamMaster, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, cid: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control", cid)

    assert(tryReadPacketDesc(dcsMaster, cid).result.isEmpty, "should not have packet on standby yet")

    // set core mask to only schedule to one core
    val mask = 1 << cid
    csrMaster.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // reset packet allocator
    csrMaster.write(coreBlock("allocReset"), 1.toBytes)
    sleepCycles(200)
    csrMaster.write(coreBlock("allocReset"), 0.toBytes)

    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        rxSimple(dcsMaster, axisMaster, toSend, cid)
      }
    }

    // TODO: check DCS master cacheline state
  }

  def rxScanOnCore(cid: Int) = test(s"rx-scan-sizes-core$cid", Slow) { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(10000)

    rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 9618, 64, cid)
  }

  0 until nicConfig.numCores foreach rxScanOnCore

  test("rx-all-cores-serialized") { implicit dut =>
    val (csrMaster, axisMaster, dcsMaster) = rxDutSetup(10000)

    0 until nicConfig.numCores foreach { idx =>
      println(s"====> Testing core $idx")
      rxTestRange(csrMaster, axisMaster, dcsMaster, 64, 256, 64, idx)
    }
  }

  // TODO: test multiple cores interleaving

  var txNextCl = mutable.ArrayBuffer.fill(nicConfig.numCores)(0)

  def txSimple(dcsMaster: DcsAppMaster, axisSlave: Axi4StreamSlave, toSend: List[Byte], cid: Int = 0)(implicit dut: NicEngine): Unit = {
    var received = false
    fork {
      val data = axisSlave.recv()

      assert(data == toSend,
        s"""data mismatch:
           |expected: "${toSend.bytesToHex}"
           |got:      "${data.bytesToHex}"
           |""".stripMargin)

      println(s"Core $cid: packet received from TX interface and validated")

      received = true
    }

    def clAddr = txNextCl(cid) * 0x80 +
      dut.host[ConfigWriter].getConfig[Int]("eci tx base") +
      dut.host[ConfigWriter].getConfig[Int]("eci core offset") * cid

    println(f"Core $cid: sending packet of length ${toSend.length}, writing packet desc to $clAddr%#x...")
    dcsMaster.write(clAddr, toSend.length.toBytes)

    val firstWriteSize = if (toSend.size > 64) 64 else toSend.size
    dcsMaster.write(clAddr + 0x40, toSend.take(firstWriteSize))
    if (toSend.size > 64) {
      dcsMaster.write(
        dut.host[ConfigWriter].getConfig[Int]("eci tx base") +
          dut.host[ConfigWriter].getConfig[Int]("eci overflow offset") +
          dut.host[ConfigWriter].getConfig[Int]("eci core offset") * cid,
        toSend.drop(firstWriteSize))
    }

    // trigger a read on the next cacheline to actually send the packet
    println(f"Core $cid: sent packet at $clAddr%#x, waiting validation...")

    txNextCl(cid) = 1 - txNextCl(cid)
    dcsMaster.read(clAddr, 1)

    waitUntil(received)

    // packet will be acknowledged by writing next packet
  }

  def txTestRange(csrMaster: AxiLite4Master, axisSlave: Axi4StreamSlave, dcsMaster: DcsAppMaster, startSize: Int, endSize: Int, step: Int, cid: Int)(implicit dut: NicEngine) = {
    val globalBlock = nicConfig.allocFactory.readBack("global")
    val coreBlock = nicConfig.allocFactory.readBack("control")

    // set core mask to only schedule to one core
    val mask = 1 << cid
    csrMaster.write(globalBlock("dispatchMask"), mask.toBytes) // mask

    // sweep from 64B to 9600B
    for (size <- Iterator.from(startSize / step).map(_ * step).takeWhile(_ <= endSize)) {
      0 until 25 + Random.nextInt(25) foreach { _ =>
        val toSend = Random.nextBytes(size).toList
        txSimple(dcsMaster, axisSlave, toSend, cid)
      }
    }
  }

  def txScanOnCore(cid: Int) = test(s"tx-scan-sizes-core$cid", Slow) { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()

    txTestRange(csrMaster, axisSlave, dcsMaster, 64, 9618, 64, cid)
  }

  0 until nicConfig.numCores foreach txScanOnCore

  test("tx-all-cores-serialized") { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()

    0 until nicConfig.numCores foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(csrMaster, axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }

  test("tx-no-voluntary-inv") { implicit dut =>
    val (csrMaster, axisSlave, dcsMaster) = txDutSetup()
    dcsMaster.voluntaryInvProb = 0
    dcsMaster.doPartialWrite = false

    0 until nicConfig.numCores foreach { idx =>
      println(s"====> Testing core $idx")
      txTestRange(csrMaster, axisSlave, dcsMaster, 64, 256, 64, idx)
    }
  }
}
