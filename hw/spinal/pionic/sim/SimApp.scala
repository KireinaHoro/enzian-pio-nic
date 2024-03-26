package pionic.sim

import mainargs._
import org.apache.commons.io.output.TeeOutputStream
import pionic.NicEngine
import spinal.core.sim._
import spinal.sim._

import java.io.{BufferedOutputStream, FileOutputStream, PrintStream}
import java.util.zip.GZIPOutputStream
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

trait SimApp extends DelayedInit {
  def dut: SimCompiled[NicEngine]

  def sleepCycles(n: Int)(implicit dut: NicEngine) = dut.clockDomain.waitActiveEdge(n)

  @main
  def run(
           @arg(doc = "regex of test names to include")
           testPattern: String = ".*",
           @arg(doc = "simulation setup random seed")
           setupSeed: Option[Int],
           @arg(doc = "simulation random seed")
           simSeed: Option[Int],
           @arg(doc = "print simulation log (transcript) to stdout")
           printSimLog: Flag,
         ): Unit = {
    val gseed = setupSeed.getOrElse(Random.nextInt)
    Random.setSeed(gseed)

    // initialize TB (elaborate component, etc.)
    initCodes.foreach(_.apply())

    val p = testPattern.r
    tests.view.map { case (name, body) =>
      (p.findFirstIn(name) match {
        case Some(_) =>
          // write simulation log to a file
          val sc = dut.simConfig
          val testWorkspace = os.pwd / os.RelPath(sc._workspacePath) / sc._workspaceName / name
          os.makeDir.all(testWorkspace)

          val lseed = simSeed.getOrElse(Random.nextInt)
          Random.setSeed(lseed)

          val logFilePath = testWorkspace / s"sim_transcript.log.gz"
          val logFileStream = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(logFilePath.toString)))

          println(s"[info] simulation transcript at $logFilePath")

          val simOutStream = if (printSimLog.value) new TeeOutputStream(System.out, logFileStream) else logFileStream
          val printStream = new PrintStream(simOutStream) {
            // prepend simulation timestamp
            override def println(x: Object): Unit = {
              if (SimManagerContext.current != null) {
                print(s"[${SimManagerContext.current.manager.time}] ")
              }
              super.println(x)
            }
          }

          val res = Console.withOut(printStream) {
            println(s">>>>> Simulation transcript ${getClass.getCanonicalName} for test $name")
            println(s">>>>> To reproduce: mill ${sc._workspaceName}.runMain ${getClass.getCanonicalName.init} --testPattern $name --printSimLog --setupSeed $gseed --simSeed $lseed\n")

            Try(dut.doSim(name, seed = lseed)(body)).recoverWith {
              case e =>
                e.printStackTrace(Console.out)
                // prevent other tests from running
                Failure(e)
            }
          }

          printStream.flush()

          if (printSimLog.value)
            println(s"[info] simulation transcript at $logFilePath")

          res
        case None =>
          println(s"[info] skipping test $name")
          Success()
      }, name)
    }.dropWhile(_._1.isSuccess).headOption match {
      case Some((_, name)) =>
        println(s"[info] test $name failed")
        System.exit(1)
      case None =>
        System.exit(0)
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
