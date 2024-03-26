package pionic.sim

import mainargs._
import pionic.NicEngine
import spinal.core.sim._

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
    initCodes.foreach(_.apply())

    val globalSeed = seed.getOrElse(Random.nextInt)
    Random.setSeed(globalSeed)

    val p = testPattern.r

    tests.map { case (name, body) =>
      (p.findFirstIn(name) match {
        case Some(_) =>
          Try(dut.doSim(name, seed = globalSeed)(body))
        case None =>
          println(s"[info] skipping test $name")
          Success()
      }, name)
    }.dropWhile(_._1.isSuccess).head match {
      case (Failure(e), name) =>
        println(s"[info] test $name failed with seed $globalSeed")
        throw e
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
