# Hot echo measurement methodology

The paper compares Lauberhorn, DPDK on zuestoll07's ConnectX-5, and
caladan-simple on that ConnectX-5 once its port is ready. All use schema echo
payloads of 8, 128 and 1432 bytes. NICVF data is not a ConnectX-5 baseline.

## Measurement boundaries

Unloaded CDFs use compiled-out software instrumentation, one resident worker
and one outstanding request. Tofino measures request departure at the
server-facing egress MAC and reply arrival at the server-facing ingress MAC.
Both timestamps use the same switch clock. The resulting wire-to-wire interval
includes both switch–server link delays; retain these rather than silently
subtracting a cable estimate. Timestamp units do not establish accuracy.
Do not substitute an egress-pipeline timestamp or collector observation time.

Unloaded breakdowns use separate instrumented acquisitions and their own
wire-to-wire totals. CPU clocks are not synchronized to the switch. Subtract
CPU **durations**, never timestamps from different clocks. Report one combined
CPU–wire residual, not separate RX/TX transport intervals. No mlx5 hardware
RX/TX timestamping or completion timestamp extension is required.

The contiguous CPU interval starts when a valid RX CQE is observed and ends
after the reply SQ publication and required doorbell. Partition it into:

| Component | CPU-NIC implementations | Lauberhorn |
| --- | --- | --- |
| RX queue processing | Valid CQE observation through packet delivery, including CQ advancement and receive-queue bookkeeping on this path | No software CQ processing |
| RX network processing | Ethernet/IP/UDP parsing and validation | Hardware work remains in the residual |
| RPC receive | Unmarshaling and dispatch | Unmarshaling and dispatch |
| Handler | Echo handler execution | Echo handler execution |
| RPC transmit | Reply marshaling | Reply marshaling |
| TX network processing | Ethernet/IP/UDP construction and checksum preparation | Hardware work remains in the residual |
| TX submission | Packet ready through SQ construction, publication, ordering and doorbell | TX doorbell read through return |

RX instrumentation belongs inside the selected mlx5/Caladan receive path;
wrapping rx_burst includes empty polling and misses the required CQE boundary.
TX instrumentation must end after submission, not before tx_burst or at later
TX completion. Record the exact selected PMD fast path, compiler configuration,
clock conversion and probe overhead. Cover CQ pointer updates, replenishment,
mbuf construction and TX reclamation wherever actually executed. Attribute
batched/deferred work explicitly; do not omit it or claim a per-packet duration
by dividing a batch without identifying that convention. The unloaded queue
path must be exercised by acceptance tests before collection.

For Lauberhorn use CPU receive return through TX doorbell return as the outer
interval; retain RPC/handler boundaries and the doorbell read/return interval.
Any unclassified CPU gap must be explicit, not silently assigned to the wire.
For each instrumented request, residual = wire duration − CPU duration.
Reject unexplained negative residuals; do not clamp. Report clock-rate and
probe uncertainty. These elapsed components are not CPU utilization estimates.

Loaded cases use compiled-out probes and client RTT, without switch capture,
FPGA readout or NIC PTP. Keep the existing 32-core paced batches, deadline,
loss/rejection accounting, aggregate histograms and complete overload grid.

## Capture and collection acceptance

Both rehearsal and full collection require the same validated Tofino backend.
It must identify pipeline/SDE/build hashes, physical ports, MAC timestamp
semantics, clock domain and tick size. Capture exact RPC flow/XID/direction,
exclude priming/setup traffic explicitly and reject duplicate or missing pairs,
FIFO/export loss and ambiguous wraparound. Preserve raw exports and hashes.
Single-outstanding traffic alone does not guarantee a control-plane TX timestamp
FIFO cannot overflow: bound export latency or handshake capture readiness
between calls, outside the measured request interval, and record that pacing.

Use a locked session with bounded snapshot, arm, drain, export and restore
operations. Snapshot before mutation and restore after failure as well as
success. Fail closed if noninteractive access, MAC departure export or restoration
is unavailable. Never install a replacement forwarding pipeline blindly on a
shared leaf. The integration stub is `tools/paper/campaign/wire.py`. Tofino capture is
being explored in a separate project; no switch backend is implemented here.
The controller blocks launches before hardware changes. Local duration-join
fixtures remain usable but cannot authorize long rehearsal or collection.

A fresh rehearsal must verify all three payloads and both instrumentation modes,
exact pairing, boundary semantics, CPU queue coverage, wrap/loss failure paths,
cleanup and plots. Old MAC/RTT acquisitions cannot satisfy wire-to-wire gates.
Full collection remains gated on that rehearsal. Caladan is not marked complete
until its port and instrumentation pass the same checks.
