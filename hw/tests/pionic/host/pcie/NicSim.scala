package pionic.host.pcie

import jsteward.blocks.misc.RegBlockReadBack
import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.sim.isSorted
import pionic._
import pionic.sim._
import spinal.core._
import spinal.core.sim.{SimBigIntPimper => _, _}
import spinal.lib.{io => _, _}
import spinal.lib.bus.amba4.axi.sim._
import spinal.lib.bus.amba4.axis.sim._

import scala.language.postfixOps
import scala.util._
import scala.util.control.TailCalls._

import org.pcap4j.core.Pcaps
import org.pcap4j.packet.namednumber.DataLinkType

class NicSim extends DutSimFunSuite[NicEngine] {
  // TODO: test on multiple configs
  implicit val c = new ConfigDatabase
  c.post("num cores", 8, action = ConfigDatabase.Override) // to test for dispatching
  c.post("host interface", "pcie", emitHeader = false)

  val dut = Config.sim
    // verilog-axi flags
    .addSimulatorFlag("-Wno-SELRANGE -Wno-WIDTH -Wno-CASEINCOMPLETE -Wno-LATCH")
    .addSimulatorFlag("-Wwarn-ZEROREPL -Wno-ZEROREPL")
    // wb2axip flags
    .addSimulatorFlag("-Wno-SIDEEFFECT")
    .workspaceName("pcie")
    .compile(pionic.GenEngineVerilog.engine(c))

  def commonDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core")

    dut.clockDomain.forkStimulus(frequency = 250 MHz)

    val pcieIf = dut.host[PcieBridgeInterfacePlugin].logic.get
    val master = Axi4Master(pcieIf.s_axi, dut.clockDomain)

    CSRSim.csrSanityChecks(globalBlock, coreBlock, master, rxBlockCycles)

    val (axisMaster, axisSlave) = XilinxCmacSim.cmacDutSetup
    (master, axisMaster, axisSlave)
  }

  def tryReadRxPacketDesc(master: Axi4Master, coreBlock: RegBlockReadBack, maxTries: Int = 20)(implicit dut: NicEngine): TailRec[Option[PacketDescSim]] = {
    if (maxTries == 0) done(None)
    else {
      println(s"Reading packet desc, $maxTries tries left...")
      readRxPacketDesc(master, coreBlock) match {
        case Some(x) => done(Some(x))
        case None =>
          sleepCycles(200)
          tailcall(tryReadRxPacketDesc(master, coreBlock, maxTries - 1))
      }
    }
  }

  def rxDutSetup(rxBlockCycles: Int)(implicit dut: NicEngine) = {
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    val (axiMaster, axisMaster, _) = commonDutSetup(rxBlockCycles)
    (axiMaster, axisMaster)
  }

  def txDutSetup()(implicit dut: NicEngine) = {
    val (axiMaster, _, axisSlave) = commonDutSetup(10000) // arbitrary rxBlockCycles
    (axiMaster, axisSlave)
  }

  /** test sending a ethernet frame to trigger bypass */
  def rxTestBypass(master: Axi4Master, axisMaster: Axi4StreamMaster)(implicit dut: NicEngine) = {
    implicit val c = dut.host[ConfigDatabase]
    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core", blockIdx = 0)
    val pktBufAddr = allocFactory.readBack("pkt")("buffer")

    import PacketType._
    val (packet, proto) = randomPacket(c[Int]("mtu"))(Ethernet, Ip, Udp)
    val payload = packet.get(typeToPcap4jClass(proto)).getPayload.getRawData.toList

    fork {
      sleepCycles(20)

      val toSend = packet.getRawData.toList
      axisMaster.send(toSend)
      println(s"Sent packet of length ${toSend.length}")
    }

    val counterBefore = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt

    // set promisc mode
    // TODO: also test non promisc mode
    master.write(globalBlock("promisc"), 1.toBytes)

    // test for actually receiving a packet
    // since we didn't arm any ONCRPC services, it should always be bypass
    val desc = tryReadRxPacketDesc(master, coreBlock).result.get
    println(s"Received status register: $desc")

    assert(desc.addr % c[Int]("axis data width") == 0, "rx buffer not aligned!")
    assert(desc.addr < c[Int]("pkt buf size per core"), f"packet address out of bounds: ${desc.addr}%#x")

    // check received protocol headers
    assert(desc.isInstanceOf[BypassPacketDescSim], "should only receive bypass packet!")
    val bypassDesc = desc.asInstanceOf[BypassPacketDescSim]
    assert(proto.id == bypassDesc.ty, s"proto mismatch: expected $proto, got ${PacketType(bypassDesc.ty.toInt)}")
    checkHeader(proto, packet, bypassDesc.pkt)

    // check payload length
    // Ethernet enforces a 64 B frame => 14 B hdr + 46 B payload + 4 B FCS.  Smaller packets will be padded
    val minDescLen = proto match {
      case Ethernet => 46
      case Ip => 26
      case Udp => 18
      case _ => 0
    }
    val expectedLen = if (payload.length < minDescLen) minDescLen else payload.length
    assert(desc.size == expectedLen, s"packet length mismatch: expected $expectedLen, got ${desc.size}")

    // read memory and check payload data
    val data = master.read(pktBufAddr + desc.addr, payload.length)
    check(payload, data)

    // free packet buffer
    master.write(coreBlock("hostRxAck"), desc.toAck.toBytes)

    val counter = master.read(coreBlock("rxPacketCount"), 8).bytesToBigInt
    assert(counter == 1 + counterBefore, s"retired packet count mismatch: expected ${counterBefore + 1}, got $counter")
  }

  // TODO: test for various failures
  test("rx-regular") { implicit dut =>
    val allocFactory = dut.host[RegAlloc].f
    val coreBlock = allocFactory.readBack("core")
    val (master, axisMaster) = rxDutSetup(10000)

    assert(readRxPacketDesc(master, coreBlock).isEmpty, "should not have packet on standby yet")

    // reset packet allocator
    master.write(coreBlock("allocReset"), 1.toBytes);
    sleepCycles(200)
    master.write(coreBlock("allocReset"), 0.toBytes);

    // test for 200 runs
    0 until 200 foreach { _ => rxTestBypass(master, axisMaster) }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  test("tx-regular") { implicit dut =>
    val allocFactory = dut.host[RegAlloc].f
    val coreBlock = allocFactory.readBack("core")
    val pktBufAddr = allocFactory.readBack("pkt")("buffer")

    val (master, axisSlave) = txDutSetup()

    // get tx buffer address
    var data = master.read(coreBlock("hostTx"), 8)
    val desc = data.toTxPacketDesc
    assert(desc.addr % c[Int]("axis data width") == 0, "tx buffer not aligned!")
    println(s"Tx packet desc: $desc")

    val toSend = Random.nextBytes(256).toList
    assert(toSend.length <= desc.size, s"packet to send too big: requested ${toSend.length}, actual: ${desc.size}")

    // write packet data
    master.write(pktBufAddr + desc.addr, toSend)
    // receive from axis
    axisSlave.recvCB() { data =>
      check(toSend, data)
    }
    // write tx commit -- make sure axis have a chance to catch the first beat
    master.write(coreBlock("hostTxAck"), toSend.length.toBytes)

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  /** test enabling a ONCRPC service */
  test("rx-oncrpc-roundrobin") { implicit dut =>
    val cmacIf = dut.host[XilinxCmacPlugin].logic.get

    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val pktBufAddr = allocFactory.readBack("pkt")("buffer")

    val (master, axisMaster) = rxDutSetup(10000)

    // the tx interface should never be active!
    cmacIf.cmacTxClock.onSamplings {
      assert(!cmacIf.m_axis_tx.valid.toBoolean, "tx axi stream fired during rx only operation!")
    }

    master.write(globalBlock("rxBlockCycles"), 100.toBytes) // rxBlockCycles

    val mask = b"01101110"
    master.write(globalBlock("oncRpcCtrl", "coreMask"), mask.toBytes) // mask

    // TODO: also test non promisc mode
    master.write(globalBlock("promisc"), 1.toBytes)

    // generate ONCRPC packet
    val sport, dport = Random.nextInt(65535)
    val prog, progVer, procNum = Random.nextInt()
    // 48-bit pointer; avoid generating negative number
    val funcPtr = Random.nextLong(0x1000000000000L)

    // dump generated packet
    val dumper = Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace("rx-oncrpc-roundrobin") / "packets.pcap").toString)

    def getPacket = {
      // payload under 48B (12 words) will be inlined into control struct ("max onc rpc inline bytes")
      // val payloadWords = Random.nextInt(24)
      val payloadWords = Random.between(14, 24)
      val payloadLen = payloadWords * 4
      val payload = Random.nextBytes(payloadLen).toList
      val packet = oncRpcCallPacket(sport, dport, prog, progVer, procNum, payload)
      dumper.dump(packet)
      dumper.flush()
      (packet, payload)
    }

    // activate service
    master.write(globalBlock("oncRpcCtrl", "listenPort_0"), dport.toBytes)
    master.write(globalBlock("oncRpcCtrl", "listenPort_0_enabled"), 1.toBytes)

    master.write(globalBlock("oncRpcCtrl", "service_0_progNum"), prog.toBytes)
    master.write(globalBlock("oncRpcCtrl", "service_0_progVer"), progVer.toBytes)
    master.write(globalBlock("oncRpcCtrl", "service_0_proc"), procNum.toBytes)
    master.write(globalBlock("oncRpcCtrl", "service_0_funcPtr"), funcPtr.toBytes)
    master.write(globalBlock("oncRpcCtrl", "service_0_enabled"), 1.toBytes)

    // wait for mask and service configs to take effect
    sleepCycles(20)

    // test round-robin
    Seq.fill(40)(0 until c[Int]("num cores")).flatten
      .filter(idx => ((1 << idx) & mask) != 0).foreach { idx =>
        val (packet, payload) = getPacket
        val toSend = packet.getRawData.toList
        axisMaster.sendCB(toSend)()

        // we skip the bypass core
        val coreBlock = allocFactory.readBack("core", idx + 1)
        val desc = tryReadRxPacketDesc(master, coreBlock).result.get
        println(f"Received status register: $desc")

        desc match { case OncRpcCallPacketDescSim(addr, size, fp, xid, args) =>
          assert(funcPtr == fp, s"funcPtr mismatch: got $fp, expected $funcPtr")

          val inlineMaxLen = c[Int]("max onc rpc inline bytes")

          // check inline data
          // TODO: check if args is endian-swapped correctly
          // XXX: spinal.lib.LiteralRicher.toBytes adds an extra byte for positive BigInts
          val inlinedWords = spinal.core.sim.SimBigIntPimper(args).toBytes().toList
          check(payload.take(inlinedWords.length), inlinedWords)

          payload.length match {
            case l if l > inlineMaxLen =>
              // assert(inlinedWords.length == inlineMaxLen, s"inlined words length mismatch: got ${inlinedWords.length}, expected $inlineMaxLen")
              assert(size == l - inlineMaxLen, s"overflow payload length mismatch: got $size, expected ${l - inlineMaxLen}")
              // check data
              val data = master.read(pktBufAddr + addr, size)
              check(payload.drop(inlineMaxLen), data)
            case l =>
              assert(size == 0, s"payload shorter than inline length but still overflowed: got $l bytes")
          }

          // TODO: free packet!
        }
      }

    dut.clockDomain.waitActiveEdgeWhere(master.idle)
  }

  def getRxTimestamps(master: Axi4Master, globalBlock: RegBlockReadBack) = {
    new {
      val entry = master.read(globalBlock("lastProfile", "RxCmacEntry"), 8).bytesToBigInt
      val afterRxQueue = master.read(globalBlock("lastProfile", "RxAfterCdcQueue"), 8).bytesToBigInt
      val readStart = master.read(globalBlock("lastProfile", "RxCoreReadStart"), 8).bytesToBigInt
      val afterRead = master.read(globalBlock("lastProfile", "RxCoreReadFinish"), 8).bytesToBigInt
      val afterDmaWrite = master.read(globalBlock("lastProfile", "RxAfterDmaWrite"), 8).bytesToBigInt
      val afterRxCommit = master.read(globalBlock("lastProfile", "RxCoreCommit"), 8).bytesToBigInt

      println(s"RxCmacEntry: $entry")
      println(s"RxAfterCdcQueue: $afterRxQueue")
      println(s"RxCoreReadStart: $readStart")
      println(s"RxCoreReadFinish: $afterRead")
      println(s"RxAfterDmaWrite: $afterDmaWrite")
      println(s"RxCoreCommit: $afterRxCommit")
    }
  }

  def getTxTimestamps(master: Axi4Master, globalBlock: RegBlockReadBack) = {
    new {
      val acquire = master.read(globalBlock("lastProfile", "TxCoreAcquire"), 8).bytesToBigInt
      val afterTxCommit = master.read(globalBlock("lastProfile", "TxCoreCommit"), 8).bytesToBigInt
      val afterDmaRead = master.read(globalBlock("lastProfile", "TxAfterDmaRead"), 8).bytesToBigInt
      val exit = master.read(globalBlock("lastProfile", "TxCmacExit"), 8).bytesToBigInt

      println(s"TxCoreAcquire: $acquire")
      println(s"TxCoreCommit: $afterTxCommit")
      println(s"TxAfterDmaRead: $afterDmaRead")
      println(s"TxCmacExit: $exit")
    }
  }

  test("rx-timestamped-queued") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(10000)

    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core")

    val toSend = Random.nextBytes(256).toList

    axisMaster.send(toSend)
    // ensure that the packet has landed
    val delayed = 1000
    sleepCycles(delayed)

    val desc = readRxPacketDesc(master, coreBlock).get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxAck"), desc.toAck.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(entry, afterRxQueue, afterDmaWrite, readStart, afterRead, timestamp, afterRxCommit))
    assert(readStart - entry >= delayed - 2)
  }

  test("rx-timestamped-stalled") { implicit dut =>
    val (master, axisMaster) = rxDutSetup(10000)

    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core")

    val toSend = Random.nextBytes(256).toList
    val delayed = 500

    fork {
      sleepCycles(delayed)

      axisMaster.send(toSend)
    }

    val desc = readRxPacketDesc(master, coreBlock).get
    val timestamp = master.read(globalBlock("cycles"), 8).bytesToBigInt

    // commit
    master.write(coreBlock("hostRxAck"), desc.toAck.toBytes)

    val timestamps = getRxTimestamps(master, globalBlock)
    import timestamps._

    println(s"Current timestamp: $timestamp")

    assert(isSorted(readStart, entry, afterRxQueue, afterDmaWrite, afterRead, timestamp, afterRxCommit))
    assert(entry - readStart >= delayed)
  }

  test("tx-timestamped") { implicit dut =>
    val (master, axisSlave) = txDutSetup()

    val allocFactory = dut.host[RegAlloc].f
    val globalBlock = allocFactory.readBack("global")
    val coreBlock = allocFactory.readBack("core")
    val pktBufAddr = allocFactory.readBack("pkt")("buffer")

    val toSend = Random.nextBytes(256).toList
    val desc = master.read(coreBlock("hostTx"), 8).toTxPacketDesc
    val delayed = 500

    master.write(pktBufAddr + desc.addr, toSend)

    // insert delay
    fork {
      sleepCycles(delayed)
      master.write(coreBlock("hostTxAck"), toSend.length.toBytes)
    }

    // receive packet, check timestamps
    axisSlave.recv()

    val timestamps = getTxTimestamps(master, globalBlock)
    import timestamps._

    assert(isSorted(acquire, afterTxCommit, afterDmaRead, exit))
    assert(afterTxCommit - acquire >= delayed)
  }
}
