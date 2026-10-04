# Worker-issued GSYNC behind a peer RX hold

On October 4, an application-only probe on matched pipeline **516112**
(`b2117e9625cca7ce256b44db34a5f2a46a952622`) reproducibly generated RPC tails by
changing a populated page's permissions on a worker while other workers waited
in coherent RX reads. All **240 RPC replies** validated. All **80 mprotect
requests** generated two GSYNCs with PPVID **47**, matching the probe's reported
Linux CPU 47. The 80 explicit `getpid` syscall controls generated none.

This strengthens the [historical two-outlier interpretation](worker-hol-evidence.md):
a worker's synchronization can wait behind a peer's blocked RX even though the
FPGA answers GSYNC immediately. It does not establish which interrupt or
instruction initiated the historical GSYNCs. The experiment intentionally invokes
TLB maintenance through `mprotect`; no transport barrier, RTL, address-layout,
PREEMPT or kernel implementation was changed.

## Controlled results

The one-thread probe changes an already populated private anonymous page from
read/write to read-only, then restores read/write. CPU CLOCK_MONOTONIC_RAW
measures the two calls separately. Twenty repetitions of each mode (no-op,
`syscall(SYS_getpid)`, and permission change) ran in every phase.

| Programmed RX hold | First mprotect median | Restore mprotect median | Permission-change RPC E2E median |
| --- | ---: | ---: | ---: |
| 50 ms, first phase | 40.333 ms | 50.004 ms | 90.450 ms |
| 10 ms | 9.071 ms | 10.004 ms | 19.188 ms |
| 1 ms | 0.358 ms | 1.004 ms | 1.472 ms |
| 50 ms, repeated | 40.314 ms | 50.004 ms | 90.424 ms |

The first call encounters the remaining peer hold, whereas the restoration can
encounter a newly issued full-length peer RX. These are measured durations, not
an assumption that every call must cost exactly one hold interval. All 160
explicit permission-change GSYNCs overlap two or three fully observed peer RX
holds. The final TX commit follows the latest outstanding peer's NACK in every
permission-change RPC: minimum **4.51 us**, median **4.64 us**, maximum **40.18 us**.
CPU-side global retirement itself is not directly traced.

The first no-op request also had a PPVID-47 GSYNC and a **44.412 ms**
RX-descriptor-to-TX-commit interval. Commit followed the last peer NACK by
**6.03 us**. Its explicitly measured no-op took only 70 ns, so that delay is
outside the measured no-op. The remaining 79 no-op requests contain no GSYNC.
This additional cold-request tail remains visible; its initiating instruction
is unresolved. The syscall controls take at most **10.32 us** inside getpid.

## Hardware and preservation

Zuestoll12 was released through `emg` without taking over the attached BMC
console. Zuestoll07 was reserved with a new generated volume; `emg -g`, `-k`,
`-i`, and `-a` selected the image, validated client kernel/initrd and CPU-6
isolation. No direct PXE or iSCSI edits were made. Its DPDK loopback validated
1,000 measured plus 100 warmup exchanges and restored the data VF. Missing
VFIO modules and libfdt were installed in the fresh client filesystem.

Zuestoll14 passed the matched 100-RPC baseline on the third bounded cold-boot
attempt; the first two attempts lacked the CCPI lane report and loaded no
module. The successful attempt verified QLM8..13 and CCPI lanes 0..23. RT
bandwidth throttling was disabled. The initial add worker remained on datapath
1; a bounded mul burst activated datapaths 2 and 3, and the probe activated
datapath 4 / Linux CPU 47. No ordinary PREEMPT was intentionally induced.

An unsupported setup command stopped before starting the probe; the harness
saved the full 137,679,488-byte trace before correcting it. The final capture
is 184,771,456 bytes, unwrapped, with no DMA error. One loss marker records the
intentional earlier freeze/resume gap; it precedes probe activation. There is
**zero decoded loss during all 240 measured probe requests**, whose count
matches the trace descriptor-to-commit pairs exactly.

The first post-test health comparison used different dmesg implementations:
identical boot messages appeared with priority 30 versus 7, failing the strict
prefix check. Both originals are retained. Repeating with the deployment's
same dmesg binary passed kernel-log continuity and all eight TAD checks, with
no detected kernel fault or TAD interrupt/timeout flag. This was a tooling
mismatch, not evidence of a board reset or an overwritten log.

The board remains reserved with the services running, RX hold restored to
50 ms, and tracing frozen after capture. No reset followed the capture.

## Reproduction

Use [gsync_probe.c](../../tools/enzian/gsync_probe.c) with the exact tested
runtime and public headers, not an arbitrary current runtime. This campaign
extracted `liblauberhorn.so` from the 516112 deployment image and cross-compiled
only the probe; public headers had no diff from the pipeline revision. It uses
the deployment's existing AArch64 glibc and runtime paths. Build with `-O2 -g
-pthread -Wall -Wextra -Werror`, the matched public and libtirpc includes, and
`-L MATCHED_RUNTIME/lib -llauberhorn` plus its DUT-visible rpath. Do not pass a
relative shared-library filename directly: that library has no SONAME and the
linker would embed the relative path as DT_NEEDED. Verify dependencies before
staging. The probe does not replace the deployed module or runtime.

The probe registers port 22346 and one worker. Start it once; individual domain
teardown still requires the documented recovery policy. Allocate the peer mul
workers with a bounded burst before sending probe RPCs, then verify actual
placement from tracing. Prefault the probe's private page and thread-local
message buffer before activation. Once the peer holds are configured and tracing
is armed, run on the reserved traffic board:

```sh
python3 gsync_probe_client.py --bind 192.168.128.225 --count 20 \
  --modes 0,1,2 --output NEW_RUN
```

The client saves every completed reply's CPU, operation/restore timestamps and
E2E interval; timeout or malformed response fails the run without retries.
These socket-client E2E values are reproduction evidence, not the pending DPDK
latency benchmark. Repeat with 10,000,000, 2,000,000 and 200,000 hold cycles at
200 MHz, then repeat the first setting. Preserve failures before any reset and
use the same dmesg implementation for both health snapshots.

The [evidence ledger](../../data/eci/board-evidence/gsync-reproduction-516112-20261004.json)
contains hashes, matched boot/capture/health reports, distributions and limits.
Raw CSVs, exact executed scripts and trace analysis remain under
`out/gsync-repro-20261004/` and shared scratch. Formatting and client argument
validation were improved after execution; the ledger retains the tested binary
and source hashes. FPGA timing remains open. The four-worker DPDK breakdown and
an interrupt-origin reproduction remain separate outstanding measurements.

## One-shot early NACK implementation

The GSYNC monitor observes accepted VC6/VC7 requests from **all PPVIDs** without
changing either immediate GSDN responder. A coalescing request/acknowledgement
CDC transfers notifications from the system clock to the application clock;
each delivery emits one application-clock pulse. Arrivals during a CDC
handshake are retained/coalesced for a subsequent delivery, not counted as
individual GSYNC responses. The handshake never backpressures the responder.

All five RX routers (bypass and four workers) use that pulse alongside their
normal timeout condition, **only in `waitDesc`**. An available descriptor still
wins, just as at timeout. Otherwise the router selects an ordinary NACK with
zero payload, retaining the existing invalidation and AXI response sequencing.
No per-router sticky release is retained: idle notifications, accepted
responses and same-line replays are unaffected, and later RX reads retain the
configured timeout. PREEMPT/fatal-release priority is unchanged.

This addresses the observed case of peers already waiting for RX descriptors.
It does not promise to catch a read that only reaches `waitDesc` after the pulse,
or bound DCS/AXI/invalidation backpressure. CPU global synchronization retirement
is still not directly observed. Hardware confirmation must repeat the controlled
permission-change experiment on the new matched image and check whether the
hold-dependent tails disappear; no such new-image result is recorded yet.

Local validation covers all five routers, idle pulses, subsequent normal waits,
descriptor priority, replay, invalidation, response backpressure and zero NACK
payload. The standalone GHDL CDC regression exercises isolated pulses at varied
clock phases and bursts during acknowledgement; it runs in the existing
`eci-toolkit-rx` CI gate. The full-engine independent-progress tests additionally
pulse GSYNC after their observation window and require every stalled worker to
return within 1000 cycles rather than the configured 50000-cycle timeout.

The October 4 local run passed `GsyncReleaseSim`, `RxPayloadIsolationSim`, the
GHDL CDC test, ECI RTL/trace-map generation, `OncRpcSim -- -t rx-allcores`, and all three
`OncRpcSim -- -z rx-independent-progress` cases.
These are simulation/elaboration results, not a new routed timing or board
result. Logs are retained under `out/gsync-early-nack-validation/`.
