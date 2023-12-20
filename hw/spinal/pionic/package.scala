import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._

package object pionic {
  implicit class RichUInt(v: UInt) {
    def toPacketLength(implicit config: PioNicConfig) = {
      val len = PacketLength()
      len.bits := v
      len
    }
  }

  implicit class RichByteArray(arr: Array[Byte]) {
    def toByteString: String = {
      arr.map(v => f"$v%02x").mkString
    }

    // with status bit
    def toPacketDesc = {
      val d = BigInt(arr.reverse).toLong
      if ((d & 1) == 0) {
        None
      } else {
        val desc = (d >> 1).toInt
        Some(PacketDescSim(desc & 0xffff, (desc >> 16) & 0xffff))
      }
    }
  }

  implicit class RichBusSlaveFactory(busCtrl: BusSlaveFactory) {
    def readStreamBlockCycles[T <: Data](that: Stream[T], address: BigInt, blockCycles: UInt, maxBlockCycles: BigInt): Unit = {
      // almost a copy of multiCycleRead
      val counter = Counter(maxBlockCycles)
      val wordCount = (1 + that.payload.getBitsWidth - 1) / busCtrl.busDataWidth + 1
      busCtrl.onReadPrimitive(SizeMapping(address, wordCount * busCtrl.wordAddressInc), haltSensitive = false, null) {
        counter.increment()
        when(counter.value < blockCycles && !that.valid) {
          busCtrl.readHalt()
        } otherwise {
          counter.clear()
        }
      }

      busCtrl.readStreamNonBlocking(that, address)
    }

    // block bus until stream ready
    def driveStream[T <: Data](that: Stream[T], address: BigInt, bitOffset: Int = 0): Unit = {
      val wordCount = (bitOffset + widthOf(that.payload) - 1) / busCtrl.busDataWidth + 1
      busCtrl.onWritePrimitive(SizeMapping(address, wordCount * busCtrl.wordAddressInc), haltSensitive = false, null) {
        when(!that.ready) {
          busCtrl.writeHalt()
        }
      }
      val flow = busCtrl.createAndDriveFlow(that.payloadType(), address, bitOffset)
      that << flow.toStream
    }
  }
}
