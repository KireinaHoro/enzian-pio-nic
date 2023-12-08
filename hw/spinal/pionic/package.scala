import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._

package object pionic {
  class RichBusSlaveFactory(busCtrl: BusSlaveFactory) {
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

  implicit def augmentBusSlaveFactory(busCtrl: BusSlaveFactory): RichBusSlaveFactory = new RichBusSlaveFactory(busCtrl)
}
