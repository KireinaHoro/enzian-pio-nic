package lauberhorn.host.eci

import lauberhorn.{Config, GenEngineVerilog}

/** Elaborate every supported worker count against the production composition. */
object GenWorkerConfigurations {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "output directory required")
    for (workers <- 1 to EciAddressMap.max_workers) {
      Config.spinal(s"${args(0)}/$workers").generateVerilog(GenEngineVerilog.engine(workers, "eci"))
      println(s"PASS production ECI composition with $workers workers")
    }
  }
}
