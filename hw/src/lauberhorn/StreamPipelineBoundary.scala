package lauberhorn

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** Two-record elastic boundary using Spinal's pipeline links. Both forward
  * payload/valid and backward ready cross registers; capacity is two records.
  */
case class StreamPipelineBoundary[T <: Data](payloadType: HardType[T]) extends Component {
  val input = slave Stream(payloadType())
  val output = master Stream(payloadType())
  val ingress, buffered, egress = Node()
  val DATA = Payload(payloadType())
  ingress.arbitrateFrom(input)
  ingress(DATA) := input.payload
  egress.driveTo(output) { case (payload, node) => payload := node(DATA) }
  Builder(List(S2MLink(ingress, buffered), StageLink(buffered, egress)))
}
