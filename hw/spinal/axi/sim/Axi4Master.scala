package axi.sim

import pionic.RichByteArray
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc._
import spinal.lib.sim._

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util._

object Axi4Bursts extends Enumeration {
  type Axi4Burst = Value

  val Fixed, Incr, Wrap, Reserved = Value
}

object Axi4Resps extends Enumeration {
  type Axi4Resp = Value

  val Okay, ExOkay, SlvErr, DecErr = Value
}

import Axi4Bursts._
import Axi4Resps._

// TODO: upstream
case class Axi4Master(axi: Axi4, clockDomain: ClockDomain) {
  private val busConfig = axi.config

  val arQueue = mutable.Queue[Axi4Ar => Unit]()
  val awQueue = mutable.Queue[Axi4Aw => Unit]()

  private val idCount = if (busConfig.useId) (1 << busConfig.idWidth) else 1
  val rQueue = Array.fill(idCount)(mutable.Queue[Axi4R => Unit]())
  val wQueue = mutable.Queue[Axi4W => Unit]()
  val bQueue = Array.fill(idCount)(mutable.Queue[Axi4B => Unit]())

  def readIdle = arQueue.isEmpty && rQueue.map(_.isEmpty).reduce(_ && _)

  def writeIdle = awQueue.isEmpty && wQueue.isEmpty && bQueue.map(_.isEmpty).reduce(_ && _)

  def idle = readIdle && writeIdle

  private val maxSize = log2Up(busConfig.bytePerWord)

  private def log(chan: String, msg: String): Unit = {
    println(s"Axi4Master [$chan]\t: $msg")
  }

  def read(address: BigInt, totalBytes: Int, id: Int = 0, burst: Axi4Burst = Incr, len: Int = 0, size: Int = maxSize)(callback: Array[Byte] => Unit) = {
    val bytePerBeat = 1 << size
    val bytes = (len + 1) * bytePerBeat // FIXME: 4K limitation?
    val numTransactions = (totalBytes.toDouble / bytes).ceil.toInt
    val builder = new mutable.ArrayBuilder.ofByte

    if (numTransactions > 1) {
      log("..", f"read $address%#x in $numTransactions transactions")
    }

    def run(addr: BigInt, totBytes: Int, numTransactions: Int) = {
      val transactionSize = if (totBytes > bytes) bytes else totBytes
      readSingle(addr, transactionSize, id, burst, len, size)(handleTransaction(addr, totBytes, numTransactions))
    }

    def handleTransaction(addr: BigInt, tot: Int, numTransactions: Int)(data: Array[Byte]): Unit = {
      builder ++= data
      if (numTransactions == 1) {
        // we are the last one
        callback(builder.result())
      } else {
        run(addr + data.length, tot - data.length, numTransactions - 1)
      }
    }

    run(address, totalBytes, numTransactions)
  }

  def readSingle(address: BigInt, totalBytes: Int, id: Int = 0, burst: Axi4Burst = Incr, len: Int = 0, size: Int = maxSize)(callback: Array[Byte] => Unit): Unit = {
    assert(size <= maxSize, s"requested beat size too big: $size vs $maxSize")
    if (burst != Incr) {
      assert(len <= 15, s"max fixed/wrap burst in one transaction is 16")
    }
    assert(len <= 255, s"max burst in one transaction is 256")
    val bytePerBeat = 1 << size
    val bytes = (len + 1) * bytePerBeat // FIXME: 4K limitation?
    assert(totalBytes <= bytes, s"requested length $totalBytes could not be completed in one transaction")
    val bytePerBus = 1 << log2Up(busConfig.dataWidth / 8)

    val roundedAddress = address - (address & (busConfig.bytePerWord - 1))
    val dropFront = (address - roundedAddress).toInt

    arQueue += { ar =>
      ar.addr #= address
      if (busConfig.useId) ar.id #= id
      if (busConfig.useBurst) ar.burst #= burst.id
      if (busConfig.useLen) ar.len #= len
      if (busConfig.useSize) ar.size #= size
      log("AR", f"addr $address%#x size $size len $len burst $burst")

      val builder = new mutable.ArrayBuilder.ofByte

      for (beat <- 0 to len) {
        rQueue(id) += { r =>
          if (busConfig.useLast) assert(r.last.toBoolean == (beat == len), "bad last beat")
          if (busConfig.useResp) assert(r.resp.toInt == Okay.id, s"bad resp ${r.resp.toInt}")
          val data = r.data.toBigInt
          val beatAddress = burst match {
            case Fixed => address
            case Incr => address + bytePerBeat * beat
            case Wrap =>
              val base = address & ~BigInt(bytes - 1)
              base + ((address + bytePerBeat * beat) & BigInt(bytes - 1))
          }
          val accessAddress = beatAddress & ~BigInt(busConfig.bytePerWord - 1)

          val start = ((beatAddress & ~BigInt(bytePerBeat - 1)) - accessAddress).toInt
          val end = start + bytePerBeat
          for (i <- 0 until bytePerBus) {
            val _byte = ((data >> (8 * i)).toInt & 0xFF).toByte
            if (start <= i && i < end) {
              builder += _byte
            }
          }
          if (beat == len) {
            val response = builder.result().slice(dropFront, dropFront + totalBytes)
            log("R", f"got data ${response.toByteString}")
            callback(response)
          }
        }
      }
    }
  }

  private val arDriver = StreamDriver(axi.ar, clockDomain) { ar =>
    if (arQueue.isEmpty) false else {
      arQueue.dequeue()(ar)
      true
    }
  }

  StreamReadyRandomizer(axi.r, clockDomain)
  StreamMonitor(axi.r, clockDomain) { r =>
    val id = if (busConfig.useId) r.id.toInt else 0
    if (rQueue(id).nonEmpty) {
      rQueue(id).dequeue()(r)
    }
  }

  private def padData(address: BigInt, data: Array[Byte]) = {
    val roundedAddress = address - (address & (busConfig.bytePerWord - 1))
    val padFront = (address - roundedAddress).toInt
    val totalLen = roundUp(padFront + data.length, busConfig.bytePerWord).toInt
    val paddedData = (Array.fill(padFront)(0.toByte) ++ data).padTo(totalLen, 0.toByte)
    val padBack = totalLen - padFront - data.length

    (roundedAddress, padFront, padBack, paddedData)
  }

  // FIXME: this can only handle one-transaction writes
  def write(address: BigInt, data: Array[Byte], id: Int = 0, burst: Axi4Burst = Incr, len: Int = 0, size: Int = maxSize)(callback: => Unit): Unit = {
    val bytePerBeat = 1 << size
    val bytes = (len + 1) * bytePerBeat

    val (_, padFront, _, paddedData) = padData(address, data)

    val numTransactions = paddedData.length / bytes
    if (numTransactions > 1) {
      log("..", f"write $address%#x in $numTransactions transactions")
    }

    def run(addr: BigInt, data: Array[Byte], transactionId: Int): Unit = {
      val slice = transactionId match {
        case 0 => data.take(bytes - padFront)
        case tid if tid == numTransactions - 1 => data
        case _ => data.take(bytes)
      }
      val remaining = data.drop(slice.length)
      writeSingle(addr, slice, id, burst, len, size)(handleTransaction(addr, transactionId, remaining))
    }

    def handleTransaction(addr: BigInt, transactionId: Int, remaining: Array[Byte])() = {
      if (transactionId == numTransactions - 1) {
        // we are the last one
        assert(remaining.isEmpty, s"left over ${remaining.length} bytes unsent!")
        callback
      } else {
        val addrInc = if (transactionId == 0) bytes - padFront else bytes
        run(addr + addrInc, remaining, transactionId + 1)
      }
    }

    run(address, data, 0)
  }

  def writeSingle(address: BigInt, data: Array[Byte], id: Int = 0, burst: Axi4Burst = Incr, len: Int = 0, size: Int = maxSize)(callback: => Unit): Unit = {
    assert(size <= maxSize, s"requested beat size too big: $size vs $maxSize")
    if (burst != Incr) {
      assert(len <= 15, s"max fixed/wrap burst in one transaction is 16")
    }
    assert(len <= 255, s"max burst in one transaction is 256")
    val bytePerBeat = 1 << size
    val bytes = (len + 1) * bytePerBeat
    val bytePerBus = 1 << log2Up(busConfig.dataWidth / 8)

    val (roundedAddress, padFront, padBack, paddedData) = padData(address, data)
    val realLen = data.length
    assert(paddedData.length <= bytes, s"requested length ${data.length} (${paddedData.length} with padding) could not be completed in one transaction")

    awQueue += { aw =>
      aw.addr #= roundedAddress
      if (busConfig.useId) aw.id #= id
      if (busConfig.useLen) aw.len #= len
      if (busConfig.useSize) aw.size #= size
      if (busConfig.useBurst) aw.burst #= burst.id
      log("AW", f"addr $roundedAddress%#x size $size len $len burst $burst")

      for (beat <- 0 to len) {
        wQueue += { w =>
          val data = paddedData.slice(beat * bytePerBeat, (beat + 1) * bytePerBeat)
          w.data #= data
          val strb = if (len == 0) {
            ((BigInt(1) << realLen) - 1) << padFront
          } else beat match {
            case 0 => (BigInt(1) << (bytePerBeat - padFront)) - 1
            case `len` => ~((BigInt(1) << padBack) - 1)
            case _ => BigInt(1) << busConfig.bytePerWord
          }
          if (busConfig.useStrb) w.strb #= strb
          if (busConfig.useLast) w.last #= beat == len
          log("W", f"data ${data.toByteString} strb $strb%#x last ${beat == len}")
        }
      }

      bQueue(id) += { b =>
        if (busConfig.useResp) assert(b.resp.toInt == Okay.id, s"bad resp ${b.resp.toInt}")
        log("B", s"transaction finished resp ${b.resp.toInt}")
        callback
      }
    }
  }

  private val awDriver = StreamDriver(axi.aw, clockDomain) { aw =>
    if (awQueue.isEmpty) false else {
      awQueue.dequeue()(aw)
      true
    }
  }

  private val wDriver = StreamDriver(axi.w, clockDomain) { w =>
    if (wQueue.isEmpty) false else {
      wQueue.dequeue()(w)
      true
    }
  }

  StreamReadyRandomizer(axi.b, clockDomain)
  StreamMonitor(axi.b, clockDomain) { b =>
    if (bQueue.nonEmpty) {
      val id = if (busConfig.useId) b.id.toInt else 0
      bQueue(id).dequeue()(b)
    }
  }

  def reset(): Unit = {
    arQueue.clear
    rQueue.foreach(_.clear)
    awQueue.clear
    wQueue.clear
    bQueue.foreach(_.clear)

    arDriver.reset
    awDriver.reset
    wDriver.reset
  }
}
