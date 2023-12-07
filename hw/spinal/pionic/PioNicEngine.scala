package pionic

// alexforencich IPs

import axi._

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._

case class PioNicConfig(axiConfig: Axi4Config = Axi4Config(32, 64, 4),
                        axisConfig: Axi4StreamConfig = Axi4StreamConfig(64),
                        regWidth: Int = 32,
                        pktBufAddrWidth: Int = 16, // 64KB
                        pktBufSizePerCore: Int = 16 * 1024, // 16KB
                        mtu: Int = 9600,
                        maxRxPktsInFlight: Int = 128,
                       )

class PioNicEngine(config: PioNicConfig) extends Component {
  private val axiConfig = config.axiConfig
  private val axisConfig = config.axisConfig

  val io = new Bundle {
    val s_axi = slave(Axi4(axiConfig))
  }

  val pktBuffer = new AxiDpRam(axiConfig)
  val axiDma = new AxiDma(AxiDmaConfig(axiConfig, axisConfig))

  val hostXbar = Axi4CrossbarFactory()
    .addSlaves(

    )

  pktBuffer.io.s_axi_a << io.s_axi

  ramDp.io.s_axi_a << io.s_axi_a
  ramDp.io.s_axi_b << axiDma.io.m_axi
}

object PioNicEngineVerilog extends App {
  Config.spinal.generateVerilog(new PioNicEngine(PioNicConfig())).mergeRTLSource("Merged")
}
