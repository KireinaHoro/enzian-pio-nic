package lauberhorn.sim

import jsteward.blocks.DutSimFunSuite
import lauberhorn.NicEngine

trait TimestampSuiteFactory { this: DutSimFunSuite[NicEngine] with DbFactory =>
  case class RxTraceTimestamps(
                                entry: BigInt,
                                afterRxQueue: BigInt,
                                readPending: BigInt,
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
    activeTraceConsumer.getOrElse(TraceEventConsumer(dut))

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

  private def latestTraceCycle(
                                trace: TraceEventConsumer,
                                eventNames: Seq[String],
                                data: Map[String, BigInt],
                                since: Int,
                              ): BigInt =
    eventNames.flatMap(eventName => trace.latest(eventName, data, since).map(_.cycle)).reduceOption(_ max _).getOrElse {
      latestTraceCycle(trace, eventNames.head, data, since)
    }

  private def firstTraceCycle(
                               trace: TraceEventConsumer,
                               eventNames: Seq[String],
                               data: Map[String, BigInt],
                               since: Int,
                             ): BigInt =
    eventNames.flatMap(eventName => trace.first(eventName, data, since).map(_.cycle)).reduceOption(_ min _).getOrElse {
      firstTraceCycle(trace, eventNames.head, data, since)
    }

  def getRxTimestamps(
                       trace: TraceEventConsumer,
                       coreId: Option[Int] = None,
                       since: Int = 0,
                       requireCommit: Boolean = true,
                     ): RxTraceTimestamps = {
    def coreData = coreId.map(id => Map("CoreID" -> BigInt(id))).getOrElse(Map.empty[String, BigInt])
    def globalCycle(eventName: String) = latestTraceCycle(trace, eventName, Map.empty, since)
    def coreCycle(eventNames: String*) = latestTraceCycle(trace, eventNames, coreData, since)
    def optionalCoreCycle(eventNames: String*) =
      eventNames.flatMap(eventName => trace.latest(eventName, coreData, since).map(_.cycle)).reduceOption(_ max _).getOrElse(BigInt(0))

    def rxReadPendingCycle(default: BigInt): BigInt =
      trace.first("RxCoreReadStart", coreData, since).map(_.cycle).orElse {
        Seq("EciRxReadStart", "EciRxAckRead", "EciRxNackReady").flatMap { eventName =>
          trace.first(eventName, coreData, since).map(_.cycle)
        }.reduceOption(_ min _)
      }.getOrElse(default)

    def rxReadStartCycle(afterRead: BigInt): BigInt = {
      val oldReadStart = trace.first("RxCoreReadStart", coreData, since).map(_.cycle)
      val eciReadStart = Seq("EciRxReadStart", "EciRxAckRead").flatMap { eventName =>
        trace.eventsSince(since)
          .filter(event => event.eventName == eventName && coreData.forall { case (key, value) => event.data.get(key).contains(value) })
          .map(_.cycle)
          .filter(_ <= afterRead)
      }.reduceOption(_ max _)

      oldReadStart.orElse(eciReadStart).getOrElse(afterRead)
    }

    val afterRead = coreCycle("RxCoreReadFinish", "EciRxDescReady")
    val timestamps = RxTraceTimestamps(
      entry = globalCycle("RxCmacEntry"),
      afterRxQueue = globalCycle("RxAfterCdcQueue"),
      readPending = rxReadPendingCycle(afterRead),
      readStart = rxReadStartCycle(afterRead),
      afterRead = afterRead,
      enqueueToHost = globalCycle("RxEnqueueToHost"),
      afterRxCommit = if (requireCommit) coreCycle("RxCoreCommit", "EciRxCtrlUnlocked") else optionalCoreCycle("RxCoreCommit", "EciRxCtrlUnlocked"),
    )

    println(s"RxCmacEntry: ${timestamps.entry}")
    println(s"RxAfterCdcQueue: ${timestamps.afterRxQueue}")
    println(s"RxCoreReadPending: ${timestamps.readPending}")
    println(s"RxCoreReadStart: ${timestamps.readStart}")
    println(s"RxCoreReadFinish: ${timestamps.afterRead}")
    println(s"RxEnqueueToHost: ${timestamps.enqueueToHost}")
    println(s"RxCoreCommit: ${timestamps.afterRxCommit}")

    timestamps
  }

  def getTxTimestamps(trace: TraceEventConsumer, coreId: Option[Int] = None, since: Int = 0): TxTraceTimestamps = {
    val coreData = coreId.map(id => Map("CoreID" -> BigInt(id))).getOrElse(Map.empty[String, BigInt])
    def coreCycle(eventNames: String*) = latestTraceCycle(trace, eventNames, coreData, since)
    def globalCycle(eventName: String) = latestTraceCycle(trace, eventName, Map.empty, since)

    val timestamps = TxTraceTimestamps(
      acquire = coreCycle("TxCoreAcquire", "EciTxAcquire"),
      afterTxCommit = coreCycle("TxCoreCommit", "EciTxSubmit"),
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
