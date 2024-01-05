package axi.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim._
import spinal.lib.bus.amba4.axis.Axi4Stream._

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

case class Axi4StreamSlave(axis: Axi4Stream, clockDomain: ClockDomain) {
  private val busConfig = axis.config
  private val queue = mutable.Queue[Axi4StreamBundle => Unit]()

  private def log(msg: String) = {
    println(s"Axi4StreamSlave\t: $msg")
  }

  def recv(): Array[Byte] = {
    val res = new AtomicReference[Array[Byte]](null)
    recvCB()(res.set)
    clockDomain.waitActiveEdgeWhere(res.get() != null)
    res.get()
  }
  def recvCB()(callback: Array[Byte] => Unit): Unit = {
    val builder = new mutable.ArrayBuilder.ofByte

    log(s"initiating recv")

    def handleBeat(bundle: Axi4StreamBundle): Unit = {
      // XXX: keep + strb has a special meaning, but we are not handling that
      val strb = if (busConfig.useKeep) {
        bundle.keep.toBooleans
      } else if (busConfig.useStrb) {
        bundle.strb.toBooleans
      } else {
        Array.fill(busConfig.dataWidth)(true)
      }

      builder ++= bundle.data.toBytes.zip(strb).filter(_._2).map(_._1)

      if (bundle.last.toBoolean) {
        callback(builder.result)
      } else {
        queue += handleBeat
      }
    }

    queue += handleBeat
  }

  StreamReadyRandomizer(axis, clockDomain)
  StreamMonitor(axis, clockDomain) { b =>
    if (queue.nonEmpty) {
      queue.dequeue()(b)
    }
  }

  def reset(): Unit = {
    queue.clear
  }
}
