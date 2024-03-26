package pionic.sim

import mainargs._
import org.apache.commons.io.output.TeeOutputStream
import pionic.NicEngine
import spinal.core.sim._
import spinal.sim._

import java.io.{FileOutputStream, PrintStream}
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

trait SimApp extends DelayedInit {
  def dut: SimCompiled[NicEngine]

  def sleepCycles(n: Int)(implicit dut: NicEngine) = dut.clockDomain.waitActiveEdge(n)

  @main
  def run(
           @arg(doc = "regex of test names to include")
           testPattern: String = ".*",
           @arg(doc = "simulation random seed")
           seed: Option[Int]
         ): Unit = {
    val globalSeed = seed.getOrElse(Random.nextInt)
    Random.setSeed(globalSeed)

    // initialize TB (elaborate component, etc.)
    initCodes.foreach(_.apply())

    // write simulation log to a file
    val sc = dut.simConfig
    val logFilePath = s"${sc._workspacePath}/${sc._workspaceName}/sim_transcript_$globalSeed.log"
    val logFileStream = new FileOutputStream(logFilePath)

    Console.withOut(new PrintStream(new TeeOutputStream(System.out, logFileStream)) {
      // prepend simulation timestamp
      override def println(x: Object): Unit = {
        if (SimManagerContext.current != null) {
          print(s"[${SimManagerContext.current.manager.time}] ")
        }
        super.println(x)
      }
    }) {
      println(s"====== simulation transcript ${getClass.getCanonicalName} (seed: $globalSeed) =====\n")

      val p = testPattern.r

      tests.view.map { case (name, body) =>
        (p.findFirstIn(name) match {
          case Some(_) =>
            Try(dut.doSim(name, seed = globalSeed)(body))
          case None =>
            println(s"[info] skipping test $name")
            Success()
        }, name)
      }.dropWhile(_._1.isSuccess).headOption match {
        case Some((Failure(e), name)) =>
          println(s"[info] test $name failed with seed $globalSeed, exception:\n")
          e.printStackTrace(Console.out)
        case None =>
      }
    }
  }

  private[this] val initCodes = mutable.ListBuffer[() => Unit]()

  override def delayedInit(x: => Unit): Unit = initCodes += (() => x)

  def test(name: String)(body: NicEngine => Unit): Unit = {
    tests.append((name, body))
  }

  val tests = mutable.ListBuffer[(String, NicEngine => Unit)]()

  final def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
