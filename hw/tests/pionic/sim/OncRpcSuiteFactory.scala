package pionic.sim

import jsteward.blocks.DutSimFunSuite
import jsteward.blocks.misc.RegBlockReadBack
import org.pcap4j.core.{PcapDumper, Pcaps}
import org.pcap4j.packet.namednumber.DataLinkType
import pionic.{AsSimBusMaster, NicEngine}
import spinal.lib._

import scala.util.Random
import scala.collection.mutable

trait OncRpcSuiteFactory { this: DutSimFunSuite[NicEngine] =>
  /** Enable one process in the scheduler. */
  def enableProcess[B](bus: B, globalBlock: RegBlockReadBack, pid: Int, maxThreads: Int, idx: Int)(implicit asMaster: AsSimBusMaster[B]) = {
    // activate process
    asMaster.write(bus, globalBlock("schedCtrl", "proc_pid"), pid.toBytes)
    asMaster.write(bus, globalBlock("schedCtrl", "proc_maxThreads"), maxThreads.toBytes)
    asMaster.write(bus, globalBlock("schedCtrl", "proc_enabled"), 1.toBytes)

    asMaster.write(bus, globalBlock("schedCtrl", "proc_idx"), idx.toBytes)

    println(s"Enabled PID#$pid with $maxThreads threads @ table idx $idx")
  }

  /** Enable one service in the given process. */
  def enableService[B](bus: B, globalBlock: RegBlockReadBack, prog: Int, progVer: Int, procNum: Int, funcPtr: Long, sport: Int, dport: Int, idx: Int, pid: Int)(implicit asMaster: AsSimBusMaster[B]) = {
    // activate service
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_progNum"), prog.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_progVer"), progVer.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_proc"), procNum.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_funcPtr"), funcPtr.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_listenPort"), dport.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_enabled"), 1.toBytes)
    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_pid"), pid.toBytes)

    asMaster.write(bus, globalBlock("oncRpcCtrl", "service_idx"), idx.toBytes)

    println(f"Enabled service progNum $prog progVer $progVer port $dport -> $funcPtr%#x @ table idx $idx")
  }

  val dumpers = mutable.Map[String, PcapDumper]()
  def getDumper(workspaceName: String): PcapDumper = {
    dumpers.getOrElseUpdate(workspaceName, {
      Pcaps.openDead(DataLinkType.EN10MB, 65535).dumpOpen((workspace(workspaceName) / "packets.pcap").toString)
    })
  }

  /** Used for generating test benches where one service sits in one process.  Tests the following paths:
    *  - service scaling up from 0 to all cores
    */
  def oncRpcCallPacketFactory[B](bus: B, globalBlock: RegBlockReadBack, packetDumpWorkspace: Option[String] = None)(implicit dut: NicEngine, asMaster: AsSimBusMaster[B]) = {
    // generate ONCRPC packet
    val sport, dport = Random.nextInt(65535)
    val prog, progVer, procNum = Random.nextInt()
    // 48-bit pointer; avoid generating negative number
    val funcPtr = Random.nextLong(0x1000000000000L)

    // TODO: also test non promisc mode
    asMaster.write(bus, globalBlock("csr", "promisc"), 1.toBytes)

    // create one process with all cores and enable a service inside
    val pid = Random.nextInt(65535)
    enableProcess(bus, globalBlock, pid, pionic.Global.NUM_WORKER_CORES, idx = 1) // slot 0 is for IDLE
    enableService(bus, globalBlock, prog, progVer, procNum, funcPtr, sport, dport, idx = 0, pid)

    // wait for mask and service configs to take effect
    sleepCycles(20)

    def getPacket = {
      // payload under 48B (12 words) will be inlined into control struct ("max onc rpc inline bytes")
      val payloadWords = Random.nextInt(24)
      val payloadLen = payloadWords * 4
      val payload = Random.nextBytes(payloadLen).toList
      val xid = Random.nextInt()
      val packet = oncRpcCallPacket(sport, dport, prog, progVer, procNum, payload, xid)
      if (packetDumpWorkspace.nonEmpty) {
        val dumper = getDumper(packetDumpWorkspace.get)
        dumper.dump(packet)
        dumper.flush()
      }
      (packet, payload, xid)
    }

    (funcPtr, getPacket _, pid)
  }

  /** Check oncRpcCall: funcPtr & payload  */
  def checkOncRpcCall[D <: HostPacketDescSim](hostDesc: D, overflowLen: Int, funcPtr: Long, payload: List[Byte], overflowData: => List[Byte]): Unit = {
    assert(hostDesc.isInstanceOf[OncRpcCallPacketDescSim], s"unexpected descriptor type received")
    val desc = hostDesc.asInstanceOf[OncRpcCallPacketDescSim]
    assert(funcPtr == desc.funcPtr, s"funcPtr mismatch: got ${desc.funcPtr}, expected $funcPtr")

    val inlineMaxLen = pionic.Global.ONCRPC_INLINE_BYTES.get

    // check inline data
    // TODO: check if args is endian-swapped correctly
    // XXX: spinal.lib.LiteralRicher.toBytes adds an extra byte for positive BigInts
    val inlinedWords = spinal.core.sim.SimBigIntPimper(desc.args).toBytes().toList
    check(payload.take(inlinedWords.length), inlinedWords)

    payload.length match {
      case l if l > inlineMaxLen =>
        assert(overflowLen == l - inlineMaxLen, s"overflow payload length mismatch: got $overflowLen, expected ${l - inlineMaxLen}")
        // check data
        check(payload.drop(inlineMaxLen), overflowData)
      case l =>
        assert(overflowLen == 0, s"payload shorter than inline length but still overflowed: got $l bytes")
    }
  }
}
