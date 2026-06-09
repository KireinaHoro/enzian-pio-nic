package lauberhorn

import jsteward.blocks.axi._
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import spinal.lib.fsm._

import scala.language.postfixOps

object LauberhornTraceDump {
  val HeaderBytes = 14 + 20 + 8
  val ResponseHeaderBytes = 32
  val MaxPayloadBytes = 1408
  val EtherTypeIpv4 = 0x0800
  val IpProtoUdp = 17
  val CommandMagic = BigInt("4c485452", 16) // "LHTR"
  val ResponseMagic = BigInt("4c485444", 16) // "LHTD"
  val Version = 1
  val OpcodeRead = 1
  val StatusOk = 0
  val StatusBadCommand = 1
  val StatusBadAlignment = 2
  val DefaultGatewayMac = BigInt("f4521463a091", 16)
  val DefaultDestIp = BigInt("81846608", 16) // 129.132.102.8
  val DefaultDestUdpPort = 55555
}

case class LauberhornTraceDumpConfig() extends Bundle {
  val gatewayMacOverrideValid = Bool()
  val gatewayMacOverride = Bits(48 bits)
  val destIpOverrideValid = Bool()
  val destIpOverride = Bits(32 bits)
  val destUdpPortOverrideValid = Bool()
  val destUdpPortOverride = Bits(16 bits)
}

case class LauberhornTraceDumpStatus(writeSlotWidth: Int) extends Bundle {
  val writeSlot = UInt(writeSlotWidth bits)
  val wrapped = Bool()
  val sampleLost = Bool()
  val dmaError = Bool()
}

case class LauberhornTraceDump(
                                axisConfig: Axi4StreamConfig,
                                dmaConfig: AxiDmaConfig,
                                axiBufferBase: BigInt,
                              ) extends Component {
  import LauberhornTraceDump._

  require(axisConfig.dataWidth >= 64, "trace dump responder expects at least one 64-byte AXIS beat")
  require(axisConfig.useKeep, "trace dump responder needs TKEEP")
  require(axisConfig.useLast, "trace dump responder needs TLAST")
  require(dmaConfig.axiConfig.dataWidth / 8 == axisConfig.dataWidth,
    "trace dump DMA stream width must match the CMAC stream width")

  val rx = slave(Axi4Stream(axisConfig))
  val tx = master(Axi4Stream(axisConfig))
  val cfg = in(LauberhornTraceDumpConfig())
  val status = in(LauberhornTraceDumpStatus(LauberhornTraceDma.WriteSlotWidth))
  val readDesc = master(dmaConfig.readDescBus)
  val readData = slave(Axi4Stream(dmaConfig.axisConfig))
  val readDescStatus = slave(dmaConfig.readDescStatusBus)

  private def byte(bits: Bits, idx: Int): Bits =
    bits(8 * idx + 7 downto 8 * idx)

  private def be16(bits: Bits, idx: Int): UInt =
    (byte(bits, idx) ## byte(bits, idx + 1)).asUInt

  private def be32(bits: Bits, idx: Int): UInt =
    (byte(bits, idx) ## byte(bits, idx + 1) ## byte(bits, idx + 2) ## byte(bits, idx + 3)).asUInt

  private def be64(bits: Bits, idx: Int): UInt =
    (byte(bits, idx) ## byte(bits, idx + 1) ## byte(bits, idx + 2) ## byte(bits, idx + 3) ##
      byte(bits, idx + 4) ## byte(bits, idx + 5) ## byte(bits, idx + 6) ## byte(bits, idx + 7)).asUInt

  private def putByte(bits: Bits, idx: Int, value: Bits): Unit =
    bits(8 * idx + 7 downto 8 * idx) := value

  private def putConst(bits: Bits, idx: Int, value: Int): Unit =
    putByte(bits, idx, B(value, 8 bits))

  private def putBe16(bits: Bits, idx: Int, value: UInt): Unit = {
    putByte(bits, idx, value.asBits(15 downto 8))
    putByte(bits, idx + 1, value.asBits(7 downto 0))
  }

  private def putBe32(bits: Bits, idx: Int, value: UInt): Unit = {
    putByte(bits, idx, value.asBits(31 downto 24))
    putByte(bits, idx + 1, value.asBits(23 downto 16))
    putByte(bits, idx + 2, value.asBits(15 downto 8))
    putByte(bits, idx + 3, value.asBits(7 downto 0))
  }

  private def putBe64(bits: Bits, idx: Int, value: UInt): Unit = {
    putByte(bits, idx, value.asBits(63 downto 56))
    putByte(bits, idx + 1, value.asBits(55 downto 48))
    putByte(bits, idx + 2, value.asBits(47 downto 40))
    putByte(bits, idx + 3, value.asBits(39 downto 32))
    putByte(bits, idx + 4, value.asBits(31 downto 24))
    putByte(bits, idx + 5, value.asBits(23 downto 16))
    putByte(bits, idx + 6, value.asBits(15 downto 8))
    putByte(bits, idx + 7, value.asBits(7 downto 0))
  }

  private def putMac(bits: Bits, idx: Int, mac: Bits): Unit = {
    for (i <- 0 until 6) {
      putByte(bits, idx + i, mac(47 - i * 8 downto 40 - i * 8))
    }
  }

  private def putIp(bits: Bits, idx: Int, ip: Bits): Unit = {
    for (i <- 0 until 4) {
      putByte(bits, idx + i, ip(31 - i * 8 downto 24 - i * 8))
    }
  }

  private def checksum(words: Seq[UInt]): Bits = {
    val sum = words.map(_.resize(20)).reduceBalancedTree(_ + _)
    val folded1 = sum(15 downto 0).resize(17) + sum(19 downto 16).resize(17)
    val folded2 = folded1(15 downto 0).resize(16) + folded1(16).asUInt.resize(16)
    ~folded2.asBits
  }

  val gatewayMac = Bits(48 bits)
  val destIp = Bits(32 bits)
  val destUdpPort = Bits(16 bits)
  gatewayMac := Mux(cfg.gatewayMacOverrideValid, cfg.gatewayMacOverride, B(DefaultGatewayMac, 48 bits))
  destIp := Mux(cfg.destIpOverrideValid, cfg.destIpOverride, B(DefaultDestIp, 32 bits))
  destUdpPort := Mux(cfg.destUdpPortOverrideValid, cfg.destUdpPortOverride, B(DefaultDestUdpPort, 16 bits))

  val extractor = AxiStreamExtractHeader(axisConfig, HeaderBytes)()
  extractor.io.input << rx
  val requestAligner = AxiStreamAligner(axisConfig)
  extractor.io.output >> requestAligner.io.input

  val injector = AxiStreamInjectHeader(axisConfig, HeaderBytes)
  injector.io.output >> tx

  extractor.io.header.ready := False
  extractor.io.outputAck := requestAligner.io.input.fire
  requestAligner.io.output.ready := False

  injector.io.header.valid := False
  injector.io.header.payload.clearAll()
  injector.io.input.valid := False
  injector.io.input.payload.clearAll()

  readDesc.valid := False
  readDesc.payload.clearAll()
  readData.ready := False

  val rxHeader = Reg(Bits(HeaderBytes * 8 bits)) init 0
  val haveRxHeader = RegInit(False)
  val reqSeq = Reg(UInt(32 bits)) init 0
  val reqOffset = Reg(UInt(64 bits)) init 0
  val reqLength = Reg(UInt(32 bits)) init 0
  val respStatus = Reg(UInt(8 bits)) init StatusBadCommand
  val respDataBytes = Reg(UInt(16 bits)) init 0
  val readStatusError = RegInit(False)

  val srcMac = Bits(48 bits)
  val srcIp = Bits(32 bits)
  val srcUdpPort = Bits(16 bits)
  srcMac := byte(rxHeader, 0) ## byte(rxHeader, 1) ## byte(rxHeader, 2) ## byte(rxHeader, 3) ## byte(rxHeader, 4) ## byte(rxHeader, 5)
  srcIp := byte(rxHeader, 30) ## byte(rxHeader, 31) ## byte(rxHeader, 32) ## byte(rxHeader, 33)
  srcUdpPort := (byte(rxHeader, 36) ## byte(rxHeader, 37)).asBits

  when(readDescStatus.valid && readDescStatus.error =/= 0) {
    readStatusError := True
  }

  when(extractor.io.header.fire) {
    rxHeader := extractor.io.header.payload
    haveRxHeader := True
  }

  val responsePayloadBytes = (U(ResponseHeaderBytes, 16 bits) + respDataBytes).resized
  val udpLength = (responsePayloadBytes + U(8, 16 bits)).resized
  val ipLength = (udpLength + U(20, 16 bits)).resized

  val ipChecksum = checksum(Seq(
    U(0x4500, 16 bits),
    ipLength,
    U(0, 16 bits),
    U(0x4000, 16 bits),
    U((64 << 8) | IpProtoUdp, 16 bits),
    U(0, 16 bits),
    (srcIp(31 downto 16)).asUInt,
    (srcIp(15 downto 0)).asUInt,
    (destIp(31 downto 16)).asUInt,
    (destIp(15 downto 0)).asUInt,
  ))

  val netHeader = Bits(HeaderBytes * 8 bits)
  netHeader.clearAll()
  putMac(netHeader, 0, gatewayMac)
  putMac(netHeader, 6, srcMac)
  putConst(netHeader, 12, 0x08)
  putConst(netHeader, 13, 0x00)
  putConst(netHeader, 14, 0x45)
  putConst(netHeader, 15, 0x00)
  putBe16(netHeader, 16, ipLength)
  putBe16(netHeader, 18, U(0, 16 bits))
  putConst(netHeader, 20, 0x40)
  putConst(netHeader, 21, 0x00)
  putConst(netHeader, 22, 64)
  putConst(netHeader, 23, IpProtoUdp)
  putByte(netHeader, 24, ipChecksum(15 downto 8))
  putByte(netHeader, 25, ipChecksum(7 downto 0))
  putIp(netHeader, 26, srcIp)
  putIp(netHeader, 30, destIp)
  putByte(netHeader, 34, srcUdpPort(15 downto 8))
  putByte(netHeader, 35, srcUdpPort(7 downto 0))
  putByte(netHeader, 36, destUdpPort(15 downto 8))
  putByte(netHeader, 37, destUdpPort(7 downto 0))
  putBe16(netHeader, 38, udpLength)
  putBe16(netHeader, 40, U(0, 16 bits))

  val traceHeader = Bits(axisConfig.dataWidth * 8 bits)
  traceHeader.clearAll()
  putConst(traceHeader, 0, 0x4c)
  putConst(traceHeader, 1, 0x48)
  putConst(traceHeader, 2, 0x54)
  putConst(traceHeader, 3, 0x44)
  putConst(traceHeader, 4, Version)
  putByte(traceHeader, 5, respStatus.asBits)
  putByte(traceHeader, 6, readStatusError.asBits.resized)
  putConst(traceHeader, 7, 0)
  putBe32(traceHeader, 8, reqSeq)
  putBe64(traceHeader, 12, reqOffset)
  putBe16(traceHeader, 20, respDataBytes)
  putBe32(traceHeader, 22, status.writeSlot.resize(32))
  putByte(traceHeader, 26, (status.wrapped.asBits ## status.sampleLost.asBits ## status.dmaError.asBits).resized)
  putConst(traceHeader, 27, 0)
  putConst(traceHeader, 28, 0)
  putConst(traceHeader, 29, 0)
  putConst(traceHeader, 30, 0)
  putConst(traceHeader, 31, 0)

  val readLengthOk = reqLength =/= 0 && reqLength <= MaxPayloadBytes
  val aligned = reqOffset(log2Up(axisConfig.dataWidth) - 1 downto 0) === 0 &&
    reqLength(log2Up(axisConfig.dataWidth) - 1 downto 0) === 0
  val readAddress = (U(axiBufferBase, dmaConfig.axiConfig.addressWidth bits) +
    reqOffset.resized).resized

  val fsm = new StateMachine {
    val idle: State = new State with EntryPoint {
      whenIsActive {
        extractor.io.header.ready := !haveRxHeader
        requestAligner.io.output.ready := haveRxHeader
        when(requestAligner.io.output.fire) {
          val body = requestAligner.io.output.data
          reqSeq := be32(body, 8)
          reqOffset := be64(body, 12)
          reqLength := be32(body, 20)
          respDataBytes := 0
          respStatus := StatusBadCommand
          readStatusError := False

          val goodMagic = be32(body, 0) === CommandMagic
          val goodVersion = byte(body, 4).asUInt === Version
          val goodOpcode = byte(body, 5).asUInt === OpcodeRead
          when(goodMagic && goodVersion && goodOpcode) {
            when(be32(body, 20) === 0) {
              respStatus := StatusOk
            } elsewhen(be32(body, 20) <= MaxPayloadBytes &&
              be64(body, 12)(log2Up(axisConfig.dataWidth) - 1 downto 0) === 0 &&
              be32(body, 20)(log2Up(axisConfig.dataWidth) - 1 downto 0) === 0) {
              respStatus := StatusOk
              respDataBytes := be32(body, 20).resized
            } otherwise {
              respStatus := StatusBadAlignment
            }
          }

          when(requestAligner.io.output.last) {
            haveRxHeader := False
            goto(issueRead)
          } otherwise {
            goto(drainRequest)
          }
        }
      }
    }

    val drainRequest: State = new State {
      whenIsActive {
        requestAligner.io.output.ready := True
        when(requestAligner.io.output.lastFire) {
          haveRxHeader := False
          goto(issueRead)
        }
      }
    }

    val issueRead: State = new State {
      whenIsActive {
        when(respStatus === StatusOk) {
          readDesc.valid := True
          readDesc.payload.payload.addr := readAddress
          readDesc.payload.payload.len := respDataBytes.resize(dmaConfig.lenWidth)
          readDesc.payload.payload.tag.clearAll()
          when(readDesc.fire) {
            goto(sendNetHeader)
          }
        } otherwise {
          goto(sendNetHeader)
        }
      }
    }

    val sendNetHeader: State = new State {
      whenIsActive {
        injector.io.header.valid := True
        injector.io.header.payload := netHeader
        when(injector.io.header.fire) {
          goto(sendTraceHeader)
        }
      }
    }

    val sendTraceHeader: State = new State {
      whenIsActive {
        injector.io.input.valid := True
        injector.io.input.data := traceHeader
        injector.io.input.keep := ((U(1, axisConfig.dataWidth bits) |<< ResponseHeaderBytes) - 1).asBits
        injector.io.input.last := respDataBytes === 0
        when(injector.io.input.fire) {
          when(respDataBytes === 0) {
            goto(idle)
          } otherwise {
            goto(sendData)
          }
        }
      }
    }

    val sendData: State = new State {
      whenIsActive {
        injector.io.input << readData
        when(injector.io.input.lastFire) {
          goto(idle)
        }
      }
    }
  }
}
