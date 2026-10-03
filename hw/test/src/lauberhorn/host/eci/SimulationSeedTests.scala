package lauberhorn.host.eci

import jsteward.blocks.DutSimFunSuite
import lauberhorn.Config
import org.scalatest.{Args, ConfigMap, Reporter}
import org.scalatest.events.{Event, TestFailed, SuiteAborted}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

class SimulationSeedTests extends AnyFunSuite {
  test("printed simulation seed is independent of global RNG and previous simulations") {
    class ProbeDut extends Component {
      val value = out UInt(8 bits)
      value := 0
    }
    val compiled = Config.sim.workspaceName("SimulationSeedProbe").compile(new ProbeDut)
    val failures = ArrayBuffer.empty[Event]
    val reporter = new Reporter {
      def apply(event: Event): Unit = event match {
        case _: TestFailed | _: SuiteAborted => failures += event
        case _ =>
      }
    }
    class Probe(samples: ArrayBuffer[Long]) extends DutSimFunSuite[ProbeDut] {
      val dut: SimCompiled[ProbeDut] = compiled
      override def newInstance: Probe = new Probe(samples)
      test("sample") { _ =>
        // A different simulation/library may consume the global RNG. It must
        // not change this simulation's stimulus or its interpretation of seed.
        scala.util.Random.setSeed(123)
        for (_ <- 0 until 37) scala.util.Random.nextLong()
        samples ++= Vector.fill(32)(simRandom.nextLong())
      }
    }
    def sample(seed: Int, setup: Int): Vector[Long] = {
      val samples = ArrayBuffer.empty[Long]
      val status = new Probe(samples).run(Some("sample"), Args(reporter,
        configMap = ConfigMap("simSeed" -> seed.toString, "setupSeed" -> setup.toString)))
      status.waitUntilCompleted()
      assert(status.succeeds(), failures.mkString("\n"))
      samples.toVector
    }
    val seed = 1699615821
    val expected = new scala.util.Random(seed)
    val first = sample(seed, 1)
    assert(first == Vector.fill(32)(expected.nextLong()), "printed simSeed was not passed to Spinal")
    assert(sample(seed, 999) == first, "suite history/global RNG changed a fixed seed")
    assert(sample(seed + 1, 1) != first, "different simulation seeds were ignored")
  }
}
