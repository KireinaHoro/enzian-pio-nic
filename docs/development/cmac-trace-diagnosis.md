# CMAC trace readout diagnosis

Started 2026-09-24 from platform `7fbbaa6`, branch `fix/cmac-trace-readout`,
in the sibling `platform-cmac-trace` worktree. This effort is authorized to
run new RTL CI sparingly. After launching a pipeline, schedule a result fetch
five hours later; do not poll CI in the meantime. Timing optimization remains
outside this effort.

## Evidence and open questions

- The live September 24 VIO check used the restored older board image and its
  pipeline-512199 probes. Trace RX alignment/status were zero and local fault
  was asserted; all trace resets and loopback were zero. The main NIC link was
  healthy. This does not retest the later F_MAC3 polarity fix.
- The matched pipeline-512381 trial on September 23 included the polarity and
  ARP-padding fixes. RX aligned, local fault cleared, and remote fault varied.
  Unsolicited metadata arrived, but both a 64 KiB dump and a zero-length status
  query timed out; retriggered metadata still arrived afterward.
- Thus a DDR-read stall alone cannot explain the result. Packet loss, network
  forwarding, receive-path stalls/rejection, and reply loss remain candidates.
  Metadata delivery does not establish a reliable link or command round trip.
- RS-FEC was already enabled. Obtain switch port configuration and counters
  before changing FEC. The actual F_MAC3 switch port still needs confirmation.
- Three UDP receiver loopback tests passed on September 24. Historical RTL
  tests are described in [board results](hardware-test-results.md); they are
  not a new validation of this branch.

## Work sequence

1. Preserve and inspect the existing peer PCAP and receiver diagnostics. Check
   actual on-wire command fields, neighbor resolution, replies, and capture
   limitations. Audit RX framing, filtering, clock crossings and TX handling.
2. Obtain read-only leaf1 port/FEC/error-counter evidence if access is available.
   Reproduce with the already-built, matched polarity/ARP-fixed artifacts,
   using existing FPGA probes and a simultaneous peer capture first.
3. Locate the failing boundary: CMAC RX, RX FIFO, header/body parser, command
   acceptance, DMA descriptor/data, response generation, CMAC TX, peer receive.
   Use a focused simulation regression for a demonstrated logic defect.
4. If existing probes cannot locate the failure, add minimal boundary counters
   or probes and validate locally before one instrumentation CI build. Do not
   change unrelated datapaths or sweep speculative link settings.
5. Schedule a one-shot CI result/artifact fetch five hours after launch. Record
   branch SHA, launch time, pipeline identity and scheduler identity. Keep the
   project CI token local and read-only. If the job is still running at the
   scheduled check, arrange another delayed check rather than a polling loop.
6. Test repeated zero-length status requests, then 64-byte, 1408-byte and
   multi-chunk reads. Freeze tracing and compare a DDR region byte-for-byte
   against JTAG; check stable write slot and report loss/wrap/DMA flags. Test a
   larger dump and normal RPC operation before claiming the path works.

Use the [hardware routine](hardware-test.md) for reservation, matching artifacts,
verified cold boot, and failure capture. Preserve original VIO values and board
provenance. Capture a probe_versions failure before any reset. Keep generated
artifacts under ignored `out/`; read compact summaries rather than routine boot
transcripts. Merge only after validated completion; retain this branch while
work is unfinished.

## Initial evidence review, September 24

The old peer files were preserved from zuestoll12 `/dev/shm/cmac-20260923/`
to `/scratch/pengxu/cmac-evidence-20260924/` and retrieved into this worktree's
`out/hardware-tests/cmac-diagnosis-20260924/prior-capture/`. The PCAP contains
19 frames: one FPGA metadata packet, followed by nine pairs of 42/60-byte
ARP request observations for `192.168.129.201`. There is no ARP reply and no
UDP read command in that capture. Tcpdump reported no kernel capture drops.
Do not interpret the paired observations as proof of duplicate FPGA delivery.

This localizes the captured 64 KiB attempt to failed neighbor resolution,
before an observable UDP command transmission. The separate static-neighbor
status timeout has no corresponding PCAP here, so it does not yet identify
which FPGA boundary failed. The next board trial should capture both automatic
ARP and a temporary static-neighbor status query, restoring the neighbor state
afterward. Existing metadata retrigger evidence still argues against a
permanent complete TX stall.

Both zuestoll12 and zuestoll14 remain reserved to pengxu. Direct peer SSH
reported a changed host key; the existing serial-console helper was used
without changing SSH trust. Switch access details have been requested. No new
board programming, RTL changes or CI launch occurred in this initial review.
Dependencies are initialized in the sibling worktree for targeted simulation.

## Fresh reproduction in progress, September 24

The unchanged responder passed all eight tests locally. An additional test
sends unrelated padded ARP, LLDP, IPv6, wrong-port UDP and long broadcast frames
before valid status queries; all nine tests pass. This checks recovery from
those traffic classes, not the physical link or VLAN-tagged command support.

The pipeline-512381 matched artifacts passed checksum verification. The board
runner rejected the first boot's missing CCPI report, cold-reset and retried.
Attempt two passed QLM8..13 and lanes 0..23, driver version verification and
100 add RPCs. The static ECI edge ILA was armed before releasing BDK. No
probe_versions failure occurred. Before trace freezing, VIO reported no trace
sample loss or DMA error; the trace CMAC aligned with local fault clear and
intermittent remote fault. Evidence is under
`out/hardware-tests/cmac-live-20260924/` in this worktree. Packet-level trace
readout results remain pending at this point.


The metadata-trigger attempt subsequently timed out after 90 seconds. Its raw
socket saw 52 frames, retained three LLDP frames, and reported zero socket
drops. A separate direct probe captured correctly addressed, untagged 66-byte
UDP status requests after installing a temporary static neighbor (sequence
1001, five attempts), with no reply. Queued sequence-1000 requests also left
once that neighbor was installed. Broadcast/unicast ARP probes received no
reply. The static neighbor was removed and all nine original trace VIO outputs
were restored. The raw broadcast UDP probe was sent but excluded by this
capture's MAC filter; do not claim its transmission was captured. LLDP confirms
leaf1, peer port ZS12-CPU2, SONiC 202305.344993.

The current image has no trace RX/parser/TX packet counters. The branch now
adds counters, a 32-byte last-header snapshot, and explicit parser/handshake
state through the existing trace VIO to locate that missing boundary. All ten
responder tests pass, including a diagnostic check for tagged headers followed
by ARP and a valid untagged status command. Full ECI RTL generation succeeds.
Vivado integration validation and one diagnostic CI build are the next gates;
this is instrumentation, not a claimed fix. The matched pipeline-512381 board
and test-owned add service remain available for switch-side diagnosis.

Local integration validation at `e312046` built `eciVivadoInputs` from a clean
commit and passed the pinned Vivado 2025.1 Docker `project-only` flow on ba2
(`VIVADO_PROJECT_READY`, no reported errors or critical warnings). Inputs are
`/tmp/cmac-diag-e312046-inputs`, project output is
`/tmp/cmac-diag-e312046-project`, and the local log is
`out/hardware-tests/cmac-live-20260924/vivado-project.log`. This checks the
expanded block design and portable inputs; synthesis, routing and board
validation of the instrumentation remain pending.

The next single push of this branch launches the diagnostic pipeline. The
prepared one-shot collector under `out/ci/cmac-followup/` will be scheduled
five hours after that push, fetch GET-only status and matching hardware/software
artifacts, and queue a follow-up in the originating thread. Its launch record
will include the exact SHA, timer unit and earliest query time. No CI status
requests were made during this diagnosis. Leaf1 did not resolve from the
gateway; LLDP advertises 240.127.1.1/fd00::1, which has not established an SSH
endpoint. Await the maintainer's hostname rather than guessing port settings.

## First diagnostic CI result, September 24

The scheduled five-hour collection found pipeline **513584** at `c398803`
failed before hardware preparation. `trace-tests`, `trace-fifo-tests`,
`blocks-tests`, `eci-toolkit-rx`, workflow and interface gates all passed.
`fast-tests-eci` job **2846456** failed with `no_updates_running` after
5350 seconds. The collected trace records 31 passing cases and two ignored
cases, ending with
`TxBypassSim`'s `all-cores-no-inv`, without an assertion failure or subsequent
case result. The underlying cause is not established by that failure reason.
No diagnostic bitstream or deployment image was produced. Collected evidence
is `out/ci/cmac-c398803-5h/`; an exact local Nix fast-test gate run is retained
under `out/ci/cmac-c398803-local-check/`.

The exact local gate, `nix build .#checks.x86_64-linux.fast-tests-eci`,
passed: 32 ECI tests (two existing ignored cases) and all four recovery,
watchdog and reply-context tests. `TxBypassSim` completed both all-core
cases. This does not establish why the CI run stopped reporting; it supports
one replacement pipeline with unchanged RTL, without weakening the gate.
The successful output is linked at `out/cmac-fast-tests`. The next branch
push will launch that replacement, with another one-shot collection scheduled
five hours later; retain its exact revision and timer in `out/ci/cmac-followup/`.

Once an instrumented image is available, also use a controlled internal PMA
loopback probe if the external path still fails. Save trace CMAC controls as
well as trace-status VIO outputs, enable only the trace port's loopback, wait
for alignment and clear faults, then trigger one unsolicited metadata frame.
Keep its destination IP different from the trace local IP so the looped frame
is discarded rather than generating an error-response loop. After settling,
expect one additional TX-queued/TX-CMAC/RX-CMAC/RX-after-CDC/header event, one
responder output frame, no accepted command, no overflow, and an idle parser.
Inspect the last-header bytes and restore all controls afterward. This tests
the local stream path independently of switch forwarding; it does not certify
external signal integrity, ARP/status requests or DDR readout.
