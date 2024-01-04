import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc._

package object pionic {
  object CLZ {
    // https://electronics.stackexchange.com/a/649761
    def apply(v: Bits): UInt = new Composite(v, "clz") {
      val w = v.getWidth
      val ow = log2Up(w) + 1
      val olrw = ow - 1
      val value: UInt = w match {
        case 2 => v.mux(
          0 -> 2,
          1 -> 1,
          default -> 0,
        )
        case _ =>
          val clzL = CLZ(v(w / 2, w / 2 bits))
          val clzR = CLZ(v(0, w / 2 bits))
          ((clzL(olrw - 1) & clzR(olrw - 1)) ## Mux(~clzL(olrw - 1),
            U("0") ## clzL(0, olrw - 2 bits),
            ~clzR(olrw - 1) ## clzR(0, olrw - 2 bits))).asUInt
      }
    }.value
  }

  object CTZ {
    def apply(v: Bits): UInt = {
      v.getWidth - CLZ(~v)
    }
  }

  object StreamDispatcherWithEnable {
    def apply[T <: Data](input: Stream[T], outputCount: Int, enableMask: Bits): Vec[Stream[T]] = {
      // FIXME: same as OHMasking.roundRobin?
      // FIXME: first packet always goes to core 0
      val select = Reg(UInt(log2Up(outputCount) bits)) init 0
      val inc = CTZ(enableMask >> (select + 1)) + 1
      when(input.fire) {
        select := select + inc.resized
      }
      StreamDemux(input, select, outputCount)
    }
  }

  object RegAllocator {
    def apply(blockName: String, base: BigInt, blockLen: BigInt, defaultSize: BigInt) = {
      new {
        private var addr = base

        def apply(name: String, size: BigInt = defaultSize): BigInt = {
          val ret = addr
          addr += size
          assert(addr < base + blockLen, f"register alloc overflow block length [$base%#x - ${base + blockLen}%#x]")
          println(f"[$blockName] $ret%#x\t: $name")
          ret
        }
      }
    }
  }

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
    def toRxPacketDesc = {
      val d = BigInt(arr.reverse).toLong
      if ((d & 1) == 0) {
        None
      } else {
        val desc = (d >> 1).toInt
        Some(PacketDescSim(desc & 0xffff, (desc >> 16) & 0xffff))
      }
    }

    def toTxPacketDesc = {
      val d = BigInt(arr.reverse).toInt
      PacketDescSim(d & 0xffff, (d >> 16) & 0xffff)
    }
  }

  implicit class RichBooleanArray(arr: Array[Boolean]) {
    def toBigInt: BigInt = arr.foldRight(BigInt(0))((b, res) => (res << 1) + b.toInt)
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

    // tracking: https://github.com/SpinalHDL/SpinalHDL/issues/1265
    def driveFlowWithByteEnable[T <: Data](that: Flow[T],
                                           address: BigInt,
                                           bitOffset: Int = 0): Unit = {

      val wordCount = (bitOffset + widthOf(that.payload) - 1) / busCtrl.busDataWidth + 1
      val byteEnable = busCtrl.writeByteEnable()

      if (wordCount == 1) {
        that.valid := False
        busCtrl.onWrite(address) {
          if (byteEnable != null) {
            when(byteEnable =/= 0) {
              that.valid := True
            }
          } else {
            that.valid := True
          }
        }
        busCtrl.nonStopWrite(that.payload, bitOffset)
      } else {
        assert(bitOffset == 0, "BusSlaveFactory ERROR [driveFlow] : BitOffset must be equal to 0 if the payload of the Flow is bigger than the data bus width")

        val regValid = RegNext(False) init (False)
        busCtrl.onWrite(address + ((wordCount - 1) * busCtrl.wordAddressInc)) {
          if (byteEnable != null) {
            when(byteEnable =/= 0) {
              regValid := True
            }
          } else {
            regValid := True
          }
        }
        busCtrl.driveMultiWord(that.payload, address)
        that.valid := regValid
      }
    }

    // block bus until stream ready
    def driveStream[T <: Data](that: Stream[T], address: BigInt, bitOffset: Int = 0): Unit = {
      val wordCount = (bitOffset + widthOf(that.payload) - 1) / busCtrl.busDataWidth + 1
      busCtrl.onWritePrimitive(SizeMapping(address, wordCount * busCtrl.wordAddressInc), haltSensitive = false, null) {
        when(!that.ready) {
          busCtrl.writeHalt()
        }
      }
      val flow = Flow(that.payloadType())
      busCtrl.driveFlowWithByteEnable(flow, address, bitOffset)
      // busCtrl.driveFlow(flow, address, bitOffset)
      that << flow.toStream
    }
  }
}
