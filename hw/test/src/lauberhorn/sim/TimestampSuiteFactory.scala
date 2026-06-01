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

  private def latestTraceCycle(
                                trace: TraceEventConsumer,
                                eventNames: Seq[String],
                                data: Map[String, BigInt],
                                since: Int,
                              ): BigInt =
    eventNames.flatMap(eventName => trace.latest(eventName, data, since).map(_.cycle)).reduceOption(_ max _).getOrElse {
      latestTraceCycle(trace, eventNames.head, data, since)
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

    val timestamps = RxTraceTimestamps(
      entry = globalCycle("RxCmacEntry"),
      afterRxQueue = globalCycle("RxAfterCdcQueue"),
      readPending = coreCycle("RxCoreReadPending", "EciRxReadStart", "EciRxReadNew"),
      readStart = coreCycle("RxCoreReadStart", "EciRxDescSent", "EciRxNackSent"),
      afterRead = coreCycle("RxCoreReadFinish", "EciRxReadNew"),
      enqueueToHost = globalCycle("RxEnqueueToHost"),
      afterRxCommit = if (requireCommit) coreCycle("RxCoreCommit", "EciRxCtrlUnlocked") else optionalCoreCycle("RxCoreCommit", "EciRxCtrlUnlocked"),
    )

    println(s"Packet entered CMAC:\t${timestamps.entry}")
    println(s"Packet after CDC:\t${timestamps.afterRxQueue}")
    println(s"Packet enqueued:\t${timestamps.enqueueToHost}")
    println(s"Core read blocked:\t${timestamps.readPending}")
    println(s"Core read unblocked:\t${timestamps.readStart}")
    println(s"Core read finished:\t${timestamps.afterRead}")
    println(s"Core freed packet:\t${timestamps.afterRxCommit}")

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
