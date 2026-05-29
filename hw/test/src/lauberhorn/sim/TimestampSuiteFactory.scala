package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import lauberhorn.NicEngine

trait TimestampSuiteFactory { this: DutSimFunSuite[NicEngine] =>
  case class RxTraceTimestamps(
                                entry: BigInt,
                                afterRxQueue: BigInt,
                                readStart: BigInt,
                                afterRead: BigInt,
                                enqueueToHost: BigInt,
                                afterRxCommit: BigInt,
                              )

  case class TxTraceTimestamps(
                                acquire: BigInt,
                                afterTxCommit: BigInt,
                                afterDmaRead: BigInt,
                                exit: BigInt,
                              )

  def traceConsumer(implicit dut: NicEngine): TraceEventConsumer =
    TraceEventConsumer(dut)

  private def latestTraceCycle(
                                trace: TraceEventConsumer,
                                eventName: String,
                                data: Map[String, BigInt],
                                since: Int,
                              ): BigInt = {
    trace.latest(eventName, data, since).map(_.cycle).getOrElse {
      val seen = trace.eventsSince(since).map { event =>
        val fields =
          if (event.data.isEmpty) ""
          else event.data.map { case (key, value) => s"$key=$value" }.mkString(" ", " ", "")
        s"${event.cycle}:${event.portName}:${event.eventName}$fields"
      }.mkString(", ")
      throw new AssertionError(s"trace event $eventName${if (data.isEmpty) "" else s" $data"} not captured; saw [$seen]")
    }
  }

  private def firstTraceCycle(
                               trace: TraceEventConsumer,
                               eventName: String,
                               data: Map[String, BigInt],
                               since: Int,
                             ): BigInt =
    trace.first(eventName, data, since)
      .map(_.cycle)
      .getOrElse(latestTraceCycle(trace, eventName, data, since))

  def getRxTimestamps(
                       trace: TraceEventConsumer,
                       coreId: Option[Int] = None,
                       since: Int = 0,
                       requireCommit: Boolean = true,
                     ): RxTraceTimestamps = {
    def coreData = coreId.map(id => Map("CoreID" -> BigInt(id))).getOrElse(Map.empty[String, BigInt])
    def globalCycle(eventName: String) = latestTraceCycle(trace, eventName, Map.empty, since)
    def coreCycle(eventName: String) = latestTraceCycle(trace, eventName, coreData, since)
    def firstCoreCycle(eventName: String) = firstTraceCycle(trace, eventName, coreData, since)
    def optionalCoreCycle(eventName: String) = trace.latest(eventName, coreData, since).map(_.cycle).getOrElse(BigInt(0))

    val timestamps = RxTraceTimestamps(
      entry = globalCycle("RxCmacEntry"),
      afterRxQueue = globalCycle("RxAfterCdcQueue"),
      readStart = firstCoreCycle("RxCoreReadStart"),
      afterRead = coreCycle("RxCoreReadFinish"),
      enqueueToHost = globalCycle("RxEnqueueToHost"),
      afterRxCommit = if (requireCommit) coreCycle("RxCoreCommit") else optionalCoreCycle("RxCoreCommit"),
    )

    println(s"RxCmacEntry: ${timestamps.entry}")
    println(s"RxAfterCdcQueue: ${timestamps.afterRxQueue}")
    println(s"RxCoreReadStart: ${timestamps.readStart}")
    println(s"RxCoreReadFinish: ${timestamps.afterRead}")
    println(s"RxEnqueueToHost: ${timestamps.enqueueToHost}")
    println(s"RxCoreCommit: ${timestamps.afterRxCommit}")

    timestamps
  }

  def getTxTimestamps(trace: TraceEventConsumer, coreId: Option[Int] = None, since: Int = 0): TxTraceTimestamps = {
    val coreData = coreId.map(id => Map("CoreID" -> BigInt(id))).getOrElse(Map.empty[String, BigInt])
    def coreCycle(eventName: String) = latestTraceCycle(trace, eventName, coreData, since)
    def globalCycle(eventName: String) = latestTraceCycle(trace, eventName, Map.empty, since)

    val timestamps = TxTraceTimestamps(
      acquire = coreCycle("TxCoreAcquire"),
      afterTxCommit = coreCycle("TxCoreCommit"),
      afterDmaRead = globalCycle("TxAfterDmaRead"),
      exit = globalCycle("TxCmacExit"),
    )

    println(s"TxCoreAcquire: ${timestamps.acquire}")
    println(s"TxCoreCommit: ${timestamps.afterTxCommit}")
    println(s"TxAfterDmaRead: ${timestamps.afterDmaRead}")
    println(s"TxCmacExit: ${timestamps.exit}")

    timestamps
  }
}
