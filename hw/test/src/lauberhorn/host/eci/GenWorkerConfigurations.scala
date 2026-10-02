package lauberhorn.host.eci

import lauberhorn.{Config, GenEngineVerilog}
import lauberhorn.Global._

/** Elaborate every supported worker count against the production composition. */
object GenWorkerConfigurations {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "output directory required")
    for (workers <- 1 to EciAddressMap.max_workers) {
      val out = os.Path(args(0), os.pwd) / workers.toString
      // The production CLI elaborates once per JVM; this matrix reuses it.
      EciDecoupledRxTxProtocol.emittedMackerel = false
      val config = Config.spinal(out.toString)
      val report = config.generateVerilog(GenEngineVerilog.engine(workers, "eci"))
      os.makeDir.all(out / "headers")
      os.makeDir.all(out / "devices")
      report.toplevel.database on {
        ALLOC.writeMackerel(out / "devices", "lauberhorn_eci")
        ALLOC.writeHeader("lauberhorn_eci", out / "headers" / "regblock_bases.h")
        writeConfigs(out / "headers" / "config.h", config)
      }
      println(s"PASS production ECI composition with $workers workers")
    }
  }
}
