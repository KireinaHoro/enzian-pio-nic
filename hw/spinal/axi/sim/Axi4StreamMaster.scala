package axi.sim

import pionic.{RichBooleanArray, RichByteArray}
import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axis.Axi4Stream._
import spinal.lib.sim._

import scala.collection.mutable

case class Axi4StreamMaster(axis: Axi4Stream, clockDomain: ClockDomain) {
  private val busConfig = axis.config
  private val queue = mutable.Queue[Axi4StreamBundle => Unit]()

  private def log(msg: String): Unit = {
    println(s"Axi4StreamMaster: $msg")
  }

  def send(data: Array[Byte]): Unit = {
    val mtx = SimMutex().lock()
    sendCB(data) {
      mtx.unlock()
    }
    mtx.await()
  }
  def sendCB(data: Array[Byte])(callback: => Unit): Unit = {
    val fullLength = roundUp(data.length, busConfig.dataWidth).toInt
    if (fullLength != data.length && !busConfig.useStrb && !busConfig.useKeep) {
      log(s"not using strb or keep but length not multiple of data width; data will be zero padded")
    }

    val beats = (data.map { byte => (byte, true) } padTo(fullLength, (0.toByte, false)) grouped busConfig.dataWidth).toList
    log(s"initiating send, ${beats.length} beats in total")
    beats.zipWithIndex.foreach { case (dataWithStrb, idx) =>
      val (data, strb) = dataWithStrb.unzip
      queue += { bundle =>
        val isLast = idx + 1 == beats.length

        bundle.data #= data
        if (busConfig.useId) bundle.id.randomize()
        if (busConfig.useStrb) bundle.strb #= strb
        if (busConfig.useLast) bundle.last #= isLast
        if (busConfig.useKeep) bundle.keep #= strb

        log(f"beat #$idx: data ${data.toByteString} strb ${strb.toBigInt}%#x last $isLast")

        if (isLast) callback
      }
    }
  }

  private val driver = StreamDriver(axis, clockDomain) { b =>
    if (queue.isEmpty) false else {
      queue.dequeue()(b)
      true
    }
  }

  def reset(): Unit = {
    queue.clear

    driver.reset
  }
}
