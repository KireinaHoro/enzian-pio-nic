# B4 L2 timeout investigation: pipeline 512199

Status: September 18, 2026. Trace-localized stall plus reproduced crossbar bug;
software entry-order violation strongly indicated, exact wakeup not captured.
The failure was preserved during diagnosis. The subsequent kernel parking
correction below has now passed the incoming/process recovery campaign on the
same 512199 bitstream; crossbar correction and broader B4 acceptance remain open.

## Evidence and chronology

The tested hardware/software revision is `754ecb4`, with blocks `cc4b0a5` and
verilog-axi `c1facace`. The saved final 1 MiB DDR window covers the onset and
166.8 seconds after the unanswered read. It is not merely a post-failure window.
Frozen DMA status has no wrap, sample-loss or DMA-error flag. Raw timestamps
below are adjusted using the matching map; time conversion uses 200 MHz.

| Tick | Observation |
| ---: | --- |
| 156685872834 | CPU RLDD of unaliased `0x130000` (wire-aliased `0x138080`), the new service's prefix-9 preemption-control CL |
| 156685878684 | PSHA response to that read reaches the system ECI output |
| 156685879084 | Normal SINV_H of `0xa0080` |
| 156685879125 | CPU HAKD acknowledges it, 41 ticks later |
| 156685879198 | DCS consumes the local unlock of `0xa0080` |
| 156685889090 | PSHA for `0xa0000` reaches the system output |
| 156685889449 | CPU RLDD of `0xa0080`, the multiplication worker's other RX control CL |
| 156685889474 | DCS processes R12 with action RDD; no corresponding RX protocol read event or PSHA follows |
| 157108961441 | Scheduler begins bootstrap of service PID 1972 on datapath 2, **2.115 s after its preemption-control read** |

The CPU's L2 timeout register ends in `0xa0080`, matching the unanswered read.
This is not the historical pattern of an unacknowledged invalidation: the last
invalidation was acknowledged and unlocked normally. Later GSYNC/GSDN traffic
continues (908 of each in the remaining system-source window), so the trace does
not support a completely dead ECI link. No DCS error-flag events occur in this
window. A subsequent bypass read also fails to complete.

A current-state application ILA was captured without reset or reprogramming.
Both DCS AXI inputs have ARVALID=0/ARREADY=1 and RVALID=0/RREADY=1 throughout
2048 samples. All five RX routers are idle; all worker RX protocol FSMs are in
waitHostRead. The last even AR address remains `0xa0080`, ID 1, LEN 1.
This places the outstanding transaction between DCS acceptance and protocol
service, rather than waiting for an RX invalidation acknowledgment.
Decode packed core states using the matching **20-bit** VHDL layout, not the
older 19-bit `app_ila.tcl` convenience aliases.

## Why an error response is implicated

Thread prefix is `1 + process_slot * 4 + thread_index`; process slot 2's first
thread therefore owns prefix 9. With a `0x20000` stride and preemption offset
`0x10000`, its control line is `0x130000`. The kernel installs the thread router
mapping during deferred dispatch, before waking the thread. The trace shows
that dispatch has not started when this address is read.

`EciThreadClRouter` explicitly maps unmatched prefixes to an invalid downstream
prefix, invoking the AXI crossbar's DECERR responder. The DCS converts the AXI
read data into a PSHA; the saved header-only ECI trace does **not** expose AXI
RRESP. Thus the DECERR on the board is an inference from the mapping/dispatch
ordering and source behavior, not a captured RRESP=3 sample.

The likely premature reader is the new worker entering `core_eci_rx` and then
`enter_cs`, whose first operation reads this preemption-control line. Initial
registration calls `desched_worker_thread` inside mmap, but its process-context
branch only sets TASK_IDLE and NEED_RESCHED. It does not park at a checked
return-to-user boundary; the task-work path is used only for a subset of IRQ
cases. A task state set inside a syscall is not a durable routing permission.
The hardware chronology establishes a premature access; the saved kernel trace
does not identify the exact schedule/wakeup that allowed it. Do not claim that
specific kernel interleaving has been reproduced yet.

## Reproduced crossbar failure

In the pinned verilog-axi sources:

- `axi_crossbar_addr.v` does not assert `trans_start` for decode errors.
- `axi_crossbar_rd.v` nevertheless asserts `s_cpl_valid` for every RLAST
  handshake, including its internal DECERR response.
- The address unit consequently decrements `trans_count_reg` without a matching
  increment. With this design's S_ACCEPT=6, the three-bit count wraps 0→7.
- Admission then blocks subsequent valid reads (`count >= 6`). A buffered AR
  may already have been accepted upstream, explaining an idle input and no
  downstream service. Error completion can also affect per-ID tracking when
  an unrelated request with that ID is outstanding.

A standalone address-unit reproduction observes 0→7 and blocked forwarding.
An integrated `axi_crossbar_rd` Verilator reproduction sends an unmapped
**two-beat** read, accepts both DECERR beats, then sends a mapped read. It
reproduces the permanent block without injected completion signals. The
positive control changes only completion accounting to exclude the internal
DECERR response; the subsequent mapped read is forwarded. This control is a
local experiment, not a committed production fix or complete crossbar audit.
In particular, write-side completion and same-ID ordering need review too.

This deterministic bug explains how a premature/unmapped access can stall the
unrelated multiplication worker and then bypass traffic. It gives a concrete
mechanism independent of setup-timing violations, though it does not prove the
precise internal counter value of the FPGA at the instant of failure.

## Required corrections and validation

1. Make initial worker parking and later preemption enforce the routing condition
   at return to userspace, retaining lifetime references and checking the condition
   after wakeup. Do not sleep under mmap/control locks or rely on a bare task-state
   assignment surviving the rest of the syscall.
2. Correct crossbar decode-error accounting and audit read/write, repeated errors,
   mixed IDs, simultaneous valid transactions, and backpressure. An unmapped access
   must not prevent unrelated mapped traffic from completing.
3. Keep a regression for the observed unmapped preemption read → normal RX read
   sequence, then rerun the board recovery campaign on matching corrected artifacts.
   A kernel-only repair must not be presented as fixing the crossbar defect.

Evidence root: `out/enzian/b4-512199/`. `hang-capture/hang-failure-tail.jsonl`
contains decoded events; `hang-capture/timeout-app-ila.csv` is the new failed-state
capture. `analysis/` contains `decerr_repro.sv`, `crossbar_repro.sv`, the positive
control, Verilator build logs and result logs. None of these observations required
resetting, unfreezing the DDR trace, changing software, or programming the FPGA.

## Subsequent kernel correction

`sw/kmod/sched.c` now queues return-to-user task work for initial registration
and every preemption path. The callback sets TASK_IDLE before checking the
enabled/park-requested condition and repeats that check after every wakeup.
This prevents intervening syscall sleeps or unrelated wakeups from granting
userspace access before routing. Waiting takes place after mmap/control locks
are released. Dispatch still clears the condition after routing and preemption
cacheline setup; teardown clears it after hardware retirement. Callback file
references and requeueing during an active callback are retained.

A local deterministic C harness exercising the actual parking functions passed
initial deferred parking, unrelated wakeup, teardown, callback requeue, dispatch
before callback execution, exiting-task queue rejection, and dispatch between
sleep-state publication and the condition check. This uses kernel-boundary shims
(`out/worker-park-test/`), not a running Linux scheduler or coherence model.
The original board wakeup interleaving remains unobserved, and the independent
crossbar DECERR defect is fixed separately as recorded below.

Kernel fix `5ec614c` was then applied alone to the original `754ecb4` module
source, retaining the original RTL/ABI collateral, runtime and applications.
The module cross-build and seven local parking scenarios passed. On Enzian,
100 RPCs and all **20 no-reset process-recovery cases** passed; both repetitions
of the previously failing hung-handler case completed. Archived kernel traces
show every fixture retiring, with no trace loss or L2 timeout. This supports the
kernel-side repair without waiting for the independent crossbar fix. See the
[full board result and setup caveats](../../agents/runtime-applications.md#historical-b4-parking-and-recovery).

## DECERR repair accepted by simulation (2026-09-18)

The pinned verilog-axi dependency now excludes the internal DECERR responder
from read and write transaction completion accounting. Both paths have a
regression that completes an unmapped transaction and then requires a mapped
transaction to progress. `nix build .#checks.x86_64-linux.axi-decerr` passed.
The dependency commits are verilog-axi `5de055e` and blocks `b527f9c`.

Per maintainer direction, this closes the DECERR repair on simulation evidence;
further board reproduction is not a merge gate. Bitstream 512199 does not contain
this RTL repair. The kernel parking fix remains the independently board-tested
prevention of the premature access described above.
