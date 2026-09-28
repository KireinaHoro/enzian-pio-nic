# CMAC trace readout diagnosis

Started 2026-09-24 from platform `7fbbaa6`, branch `fix/cmac-trace-readout`,
in the sibling `platform-cmac-trace` worktree. This effort is authorized to
run new RTL CI sparingly. After launching a pipeline, schedule a result fetch
once after 30 minutes, then start the five-hour collector only after hardware
is observed running; do not poll CI in the meantime. Timing optimization remains
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

## Diagnostic bitstream and board result, September 25

Replacement pipeline **513714**, revision `cb2aedf4e52c5468a485c28142a10d6dc441a233`,
passed every gate and produced hardware job **2847212** plus software job
**2847211**. The scheduled collectors observed hardware still running at the
first five-hour check, then success at the next delayed collection; no direct
CI polling was used. The bitstream, LTX, trace map and deployment image were
checksum-verified locally and after staging. Artifact provenance is retained
in `out/ci/cmac-cb2aedf-hw-10h/summary.json`. Routed timing remains open:
WNS -0.152 ns, TNS -14.033 ns, 423 failing setup endpoints, no hold violations.
This is diagnostic hardware evidence, not timing closure.

On reserved zuestoll14, the matched image passed its first verified cold boot,
all QLM8..13 locks and CCPI lanes 0..23, driver verification, and 100 add RPCs.
The static ECI edge ILA was armed before BDK release and tracing stayed active
through module load. No `probe_versions` failure occurred.

The new counters localize the remaining failure:

- Peer zuestoll12 captured untagged ARP and correctly addressed UDP status
  requests, including a broadcast status probe. No frame reached trace CMAC RX;
  RX-after-CDC, parsed headers, ARP requests and commands remained zero.
- Internal near-end PMA loopback cleared remote fault and stayed aligned.
  One metadata trigger incremented responder frames, TX queued, CMAC TX,
  CMAC RX, RX-after-CDC and parsed headers by exactly one. The saved header
  matched the transmitted Ethernet/IPv4 bytes; the parser returned to idle
  (`0xa001`), with no overflow and no accepted command. This tests the metadata
  stream path, not UDP command execution or DDR reads.
- After restoring external mode, the peer received one 74-byte metadata frame
  from `192.168.129.201:55555`. Its status reported write slot 6,517,091,
  sample-lost set and no DMA error. Subsequent ARP and UDP status requests
  still timed out. Final counters showed two transmitted frames (loopback plus
  external metadata), but only the one looped receive frame. Remote fault was
  intermittent on the external link.

Thus external trace TX works for the captured metadata packet, and local
loopback traverses the stream/FIFO/parser path. External inbound delivery
remains unverified at the CMAC boundary; there is no evidence yet for fixing
UDP parsing or DDR reads. Obtain leaf1 SSH access, identify the trace-facing
port, and inspect forwarding/VLAN, FEC and physical/error counters before
choosing another RTL experiment. No new CI build was launched for this result.

Full compact evidence is `out/hardware-tests/cmac-diag-20260925/summary.json`,
with `vio-snapshots.json` and the `direct/` and `metadata/` packet captures.
Both temporary peer neighbor entries were removed. All nine trace VIO outputs
and six link outputs were restored; loopback is off and tracing resumed.
The matched diagnostic image and test-owned add service remain on the DUT.
Work remains on `fix/cmac-trace-readout`; CMAC DDR capture is not yet working.

## Leaf1 access and read-only port inspection, September 25

SSH access is confirmed through `enzian-gateway` to agg0 as
`admin@fe80::290:fbff:fe73:e4b6%cluster0.103`, then from agg0 to leaf1 as
`admin@fe80::290:fbff:fe73:e4a1%eth0`. Credentials are supplied separately;
do not store them in this repository. Agg0 LLDP identifies both leaves on its
management `eth0`; gateway `cluster0.103` is not their management link.
Leaf1 identifies itself as `centner-leaf1.in.barrelfish.org`.

Read-only SONiC inspection found:

| Interface | Alias | Status | Speed / FEC | VLAN |
| --- | --- | --- | --- | --- |
| Ethernet96 | ZS14-FPGA1 (main CMAC) | admin/oper up | 100G / RS | 102 untagged |
| Ethernet108 | ZS14-FPGA4 (expected F_MAC3 trace port) | admin/oper up | 100G / RS | 102 untagged |
| Ethernet164 | ZS12-CPU2 (peer cpu40g1) | admin/oper up | 40G / RS | 102 untagged |

ZS14-FPGA2/3 (Ethernet100/104) are admin up, oper down. Ethernet108 has
MTU 9100, autoneg off, and no reported RX loss of signal on any of four lanes.
Its cumulative counters show 12 good unicast RX packets, 12 RX errors,
10 RX discards, two undersize packets, and no TX errors or drops. The RX
error/drop totals did not change across the two inspection snapshots;
they do not establish an active fault. FEC corrected/uncorrected/symbol-error
counters report N/A, not zero. Main Ethernet96 and peer Ethernet164 show zero
RX errors/drops. The peer and main MACs were learned in VLAN 102 on their
expected ports; trace MAC `0c:53:31:03:01:d4` was absent at this snapshot.
An aged-out trace MAC is not proof of a forwarding failure.

The configured VLAN and RS-FEC agree with the intended path. A fresh bounded
probe correlated with switch TX counters and FPGA CMAC RX counters is the
next discriminating check; these cumulative switch counters alone do not
explain the absent FPGA receive frames. No switch configuration or counters
were changed or cleared.

Use `python3 tools/enzian/leaf1.py` for an interactive leaf1 shell. The helper
constructs the gateway → agg0 → leaf1 SSH chain and leaves password prompts
and host-key verification to SSH. It does not store credentials. For one command:
`python3 tools/enzian/leaf1.py -- show interfaces status`.
`--print-command` displays the quoted chain without connecting.

## Correlated switch probes and framing fixes, September 25

Using the SSH helper, a fresh read-only switch/FPGA comparison found the external
link receiving background traffic with no fault reported. Sixteen broadcast and
sixteen unicast UDP status probes from zuestoll12 advanced FPGA commands and
responses by exactly 32. Leaf1 Ethernet108 good RX unicast packets increased
by 32 with no new errors; its egress counts also included the 16 unicast and
16 broadcast requests (plus background broadcast traffic). Thus the earlier
zero-RX condition is intermittent, not a persistent VLAN or RS-FEC mismatch.
No switch configuration or counters were changed.

The normal UDP dumper then received metadata but could not resolve ARP.
The FPGA recognized three ARP requests and generated replies, while leaf1 RX
errors/discards increased. Source inspection found replies with only 42 valid
bytes, below the 60-byte Ethernet minimum excluding FCS. They now include 18
zero-padding bytes, covered by assertions on both length and padding content.
This outbound padding fix is distinct from the earlier inbound ARP-padding fix.

A temporary static neighbor allowed a zero-length status request to succeed,
but the first 1408-byte DDR read and its retries timed out. The FPGA completed
responses and returned idle; leaf1 RX errors and discards increased by four.
Adding the actual TX aligner to the responder test harness reproduced the bug:
both existing DDR response tests failed with `KEEP not fully set in the middle
of stream` (eight other tests passed). The old byte-list sink silently removed
invalid bytes and missed the illegal mid-packet gap. The response header had
occupied the low 32 bytes of a nonfinal 64-byte beat, leaving a hole before DMA
data. It now occupies the high 32 bytes, providing the leading gap supported
by the header injector/aligner. Tests now exercise the actual aligner, including
64-, 128- and 1408-byte data responses and byte-exact payload checks.

Evidence is in `out/hardware-tests/cmac-switch-20260925/summary.json`,
`switch-counters.json`, `vio-snapshots.json`, and the validation logs.
Switch and peer CPU wall clocks differ; use ordered counter deltas rather than
absolute timestamps. The failed capture produced no DDR data, so no UDP/JTAG
byte comparison was performed. The temporary neighbor was removed, saved trace
controls restored, and tracing resumed. No board reset or reprogramming occurred.
The first post-fix validation attempt stopped before simulation on a full disk;
the maintainer ran GC and validation was restarted. The fixes still need a new
matched bitstream/software build and board verification.

After GC, `nix develop --command mill --no-daemon gen.test.testOnly
lauberhorn.LauberhornTraceDumpTests` passed all 11 cases, and
`nix develop --command mill --no-daemon eci.generateVerilog` completed full
ECI generation and clock-domain checks. Logs are `aligned-after-gc.log` and
`generate-after-fix.log` in the evidence directory above. The next single
branch push builds these framing fixes; its one-shot 30-minute check starts
the five-hour collection only if hardware is running. On the new matched
image, retry normal ARP plus a bounded UDP DDR dump, then compare frozen data
against JTAG before claiming working capture. Preserve the intermittent link
observation separately from the now-reproduced deterministic framing bugs.

## Framing-fix image: UDP capture validated, link intermittent

The scheduled five-hour collector found pipeline **514053** at
`c5be158ae796ad7f8387ac834d47f2c36b80b392` successful. Hardware job **2849377**,
software job **2849376**, matching LTX and trace map were checksum-verified
locally and after staging. Timing remains open: WNS -0.005 ns, TNS -0.007 ns,
three failing setup endpoints, no hold violations. This is not timing closure.

On reserved zuestoll14, the image passed the first verified cold boot,
QLM8..13 CDR and CCPI lanes 0..23, driver verification and 100 add RPCs.
The static ECI edge ILA was armed and tracing stayed active through driver
load. No `probe_versions` failure occurred.

The first normal-ARP capture attempt timed out waiting for metadata. Peer
capture retained no trace/ARP frames and reported zero socket drops. The FPGA
accepted one transmitted metadata frame but saw no incoming frames. Leaf1
reported Ethernet108 (ZS14-FPGA4) admin up, oper down, with no packet-counter
advance. CMAC RX alignment/status were one, local fault zero, but remote fault
was intermittent. Those FPGA bits alone are insufficient to establish an
externally usable link.

Leaf1 vendor diagnostics are available inside the `syncd` container:
`docker exec -it syncd /opt/bfn/install/bin/bfshell`, then `ucli`, `pm`.
The trace port maps to front-panel **28/0**, device port **44**, MAC **4/0**.
Use `show -p 28/0`, `port-pcs 28/0`, `port-ctrs 28/0 fec`, and
`port-error-get 28/0` for bounded diagnosis. The ordinary SONiC FEC table's N/A
values do not mean the ASIC lacks diagnostics. Do not clear counters or use
wildcards for recovery operations.

The cable identifies as a two-metre FS Q28-PC02 passive 100GBASE-CR4 DAC
(serial F2034354605-1). No RX loss of signal was reported on any lane. Vendor
samples showed transient FEC alignment, then loss of alignment and increasing
corrected blocks: 2,604,734 then 2,934,603; uncorrected blocks read zero.
The latter sample reported symbol-error totals 2,480 / 1,656,625 / 1,237,068 /
66,197 across lanes 0..3, concentrated on lanes 1 and 2. These snapshots locate
a receive-side link-quality/initialization problem on the switch; they do not
prove which cable, FPGA transmitter or receiver setting is responsible.

Bounded recovery attempts did not restore external link-up: trace-only GT TX
datapath reset, an admin down/up cycle of **only Ethernet108**, trace CMAC TX/RX
core resets, and one switch receiver initial DFE calibration
(`port-sd-dfe-ical 28/0 all`). Port configuration returned to admin up,
100G/RS-FEC, autoneg off, MTU 9100; no persistent speed/FEC/VLAN or equalization
setting was changed. Do not repeat resets blindly. A known-good cable/port or
further lane-level investigation is still needed to establish dependable
external operation, despite the successful capture below.

Internal near-end PMA loopback on the new image aligned without faults. One
metadata trigger advanced TX queued, CMAC TX, CMAC RX, RX-after-CDC, headers
and responder output counters by exactly one; the parser returned idle
(`0xa001`) with no overflow. This confirms the local metadata stream path.
After returning to external mode, counters caught 16 external receive frames
before the switch port went down again, establishing intermittent link windows.

One bounded retry with ten metadata announcements then captured **1,048,576
bytes** over normal ARP and UDP; no static neighbor was installed. Packet
capture contains one correctly zero-padded 60-byte ARP reply, 745 unique read
requests, 744 data responses of 1482 bytes and one of 1098 bytes, with no
retransmitted requests, invalid IPv4 checksums or capture socket drops. Every
captured response payload matches the assembled dump. The dump SHA256 is
`e3337009c43e7e5589ab41193540857f3bd10d81cf65deab0c42287891810596`.
Independent JTAG reads of 4096 bytes at offset 0 and 4096 bytes at offset
`0xff000` matched the frozen UDP dump byte-for-byte. The rest of the one-MiB
range was not independently read over JTAG. Leaf1 RX error/discard totals
remained 18/15 across the trial; the corrected frames introduced no new MAC
errors. Metadata still reports sample-lost, so this validates readout integrity,
not a lossless ECI trace or full-buffer throughput.

The port was down again afterward. The framing fixes are hardware-validated,
but reliable link initialization/operation remains unresolved. Do not infer
that the resets, recalibration or loopback transition cured it. A known-good
physical path or further lane-level diagnosis remains the next step.

Evidence is `out/hardware-tests/cmac-fixed-514053/`, including the failed first
attempt, successful `retry/` PCAP/dump/packet summary, `jtag-comparison.json`,
and vendor PHY snapshots. CI provenance is in `out/ci/cmac-c5be158-hw-5h/`.
All trace controls were restored, loopback disabled and tracing resumed.
The matched c5be158 image and test-owned add service remain on the DUT.
Work stays on `fix/cmac-trace-readout` pending link-reliability diagnosis;
no additional CI was launched.

## Full-buffer RPC trial, September 28

Matched pipeline 514053 (`c5be158`) passed a fresh verified cold boot, driver
revision verification, and three batches of 100 add RPCs. All 300 arithmetic
results and echoed IDs passed; client XDR counters recorded no RPC retries.
The bitstream, LTX, trace map and software image hashes matched the archived
artifacts. No new CI was launched or polled.

Tracing stayed active through module load and all RPCs. Freezing afterward
left write slot 3,298,221: **211,086,144 bytes**, unwrapped, DMA error clear.
The unchanged UDP client was instrumented externally to count its sends,
timeouts and accepted responses, alongside an AF_PACKET PCAP:

- 6,607 reads succeeded, saving **9,302,656 bytes**. The next read received
  no reply despite five automatic retransmissions (six sends/timeouts).
  The tool failed explicitly and left its partial output file.
- Wire counts independently agree: 6,613 requests, 6,608 unique requests,
  6,607 data responses. No capture-socket drops, bad IPv4 checksums or
  discrepancies between response payloads and saved bytes were observed.
  All accepted responses reported the same frozen status. This validates
  assembly of the received prefix, not the unread remainder.
- A bounded resume from offset 9,302,656 waited 90 seconds but received no
  metadata, so it never issued a read. The extended 120-retry setting was
  therefore not exercised. Trace VIO showed RX alignment/status asserted
  and remote fault asserted; the precise point of packet loss is unresolved.
- JTAG independently matched the first 4 KiB. The final 1 MiB was preserved
  from offset 210,037,568. Neither that tail nor the UDP prefix contains a
  positive lost-sample marker. The middle was not downloaded, so full trace
  completeness and RPC-event coverage remain **unverified**.

The sticky `sampleLost` bit was set, but it alone cannot establish loss before
freeze: `traceStop` gates new DDR descriptors while trace inputs continue,
which can overflow the recorder after freezing. Count positive reserved-source
loss markers inside the saved buffer; zero-count markers are padding bubbles.
Loss before DDR recording cannot be repaired by network retransmission.

Evidence: `out/hardware-tests/cmac-full-20260928/summary.json`, `received/`,
`resume/received/`, `jtag-rpc-validation.json`, three client CSVs, and the
retained instrumentation/analysis scripts. Three existing UDP loopback tests
passed. Separate local fault injection verified successful identical-request
retry after dropping one response, and failure after six sends when all
responses were dropped.

The **full CMAC dump remains incomplete**. Temporary network overrides were
restored, but `trace_stop=1` is deliberately retained on zuestoll14 to preserve
the frozen buffer. Do not reset/reprogram/resume tracing before recovering or
explicitly abandoning it. Saved original controls are on ba2 at
`/tmp/cmac-full-20260928/original-vio.tcl`; the corresponding `trace-vio.tcl`
`restore` mode restores all of them, including restarting tracing. Branch
`fix/cmac-trace-readout` remains unmerged pending reliable external readout.

## CMAC, GT and pad placement audit, September 28

Read the exact pipeline-514053/job-2849377 routed checkpoint with Vivado
2025.1, using `tools/physical/checkpoint.tcl` followed by bounded hard-block
and package-pin queries. This is an offline audit, not a new implementation
or board run. The checkpoint was fetched directly as a known completed
artifact; no CI status polling or launch occurred.

| Path | CMAC site / clock region | GT channels / clock region | SLR / pad bank |
| --- | --- | --- | --- |
| Main F_MAC0 | CMACE4_X0Y1 / X0Y2 | X0Y8–11 / X0Y2 | SLR0 / 121 |
| Trace F_MAC3 | CMACE4_X0Y8 / X0Y14 | X1Y56–59 / X5Y14 | SLR2 / 233 |

**The trace CMAC-to-GT connection crosses the device horizontally.** The main
CMAC is in the same clock region as its GTs. Do not describe the two physical
topologies as identical, or dismiss this distinction using fabric STA alone.
However, the trace configuration satisfies AMD's CAUI-4
[transceiver selection rules](https://docs.amd.com/r/en-US/pg203-cmac-usplus/Transceiver-Selection-Rules):
all four GTs belong to one quad, share the CMAC's horizontal clock-region row,
and remain in the same SLR. Actual GT properties have `RXBUF_EN=TRUE` and
`TXBUF_EN=TRUE`; this is not the unsupported opposite-column RX-buffer-bypass
case. The CMAC/GT selections, 322.265625 MHz reference and polarity masks
match Enzian `interfaces-stub` revision `331c57c`.

Every F_MAC0/F_MAC3 package pin was checked against its actual connected hard
primitive. Trace TX positive pads E9/D7/C9/A9 are bank-233 channels 0/1/2/3,
connected respectively to GTYE4_CHANNEL_X1Y56/57/58/59. Their negative pads
and all RX pairs also match. Reference D11/D10 enters the same quad at
GTYE4_COMMON_X1Y14. Main pads and reference likewise match bank 121 and
GTYE4_COMMON_X0Y2. No GT-to-wrong-pad-bank or lane-index mismatch was found.
GT parameter-property comparison between main lane 0 and trace lane 0 found
only object identity/location differences; it does not inspect live DRP state
or establish electrical settings appropriate for the cable.

The checkpoint has zero routing errors and no CMAC/GT/pad placement violation
in default DRC; other design warnings remain. Fabric setup representatives
are main RX/TX +0.072/+0.026 ns and trace RX/TX +0.051/+0.276 ns. **Direct
CMAC↔GT setup/hold queries return no timing paths in either direction.**
Those positive fabric margins therefore must not be presented as timing
validation of the CMAC-to-GT or analog GT-to-pad connection. The three
remaining global setup failures are ECI paths (worst -0.005 ns).

Conclusion: a real cross-device placement difference exists, but no unsupported
pairing or misplaced pad was found. Supported placement and matching reference
design are evidence of legality, not proof of a healthy serial link or a
root-cause exclusion. The frozen trace was untouched. Evidence and a compact
per-pad mapping are in `out/physical/cmac-placement-514053/summary.json`, with
the complete reports and query script beside it.

## F_MAC1 comparison build, September 28

The maintainer requested a comparison bitstream using the physically local
F_MAC1 CMAC/GT pairing. The trace instance now selects CMACE4_X0Y3 and
GTYE4_CHANNEL_X0Y20..23 in SLR1, uses F_MAC1's serial/reference-clock ports,
and applies RX/TX polarity `0011`. Its reset synchronizers and TX FIFO
floorplan move to SLR1; trace DDR and its writer remain in SLR2. RS-FEC and
other CMAC link parameters are unchanged. This is a controlled port-migration
experiment, not a claim that horizontal CMAC-to-GT distance caused the faults.

New zuestoll14 defaults: trace MAC `0c:53:31:03:01:cc`, static IP
`192.168.129.204`, UDP 55555, switch leaf1 Ethernet100 (ZS14-FPGA2).
Main F_MAC0 remains `0c:53:31:03:01:c8` / `192.168.129.200`. Capture peer
zuestoll12 remains `0c:53:31:03:01:81` / `192.168.129.129`; the existing VIO
next-hop/server overrides still apply. These are bitstream defaults, not a
change to switch or DHCP configuration. F_MAC3 historical addresses and
captures above refer to their original images.

The focused `LauberhornTraceDumpTests` suite passed all 11 tests with the new
MAC/IP expectations. Generated project and implementation results belong to
this new build and must be checked before deployment. After push, perform a
one-shot check after 30 minutes; start the five-hour wait only if hardware
is running. No test gate is weakened or skipped.

The old 211,086,144-byte F_MAC3 capture is still intentionally frozen on
zuestoll14. Preparing/launching this build does not reprogram the board.
Preserve/recover that trace before any later reset or programming; match the
new bitstream, software, LTX and trace map for the F_MAC1 board test.

### F_MAC1 CI result

Revision `5cda9017fdae1177c002ad0ba51ec3176019c917` completed pipeline
[514561](https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/platform/-/pipelines/514561)
successfully. All eleven CI jobs passed, including hardware build 2852730
and software preparation 2852729. The scheduled five-hour collector saved
matching bitstream, LTX, trace map and `deploy.img` under
`out/ci/cmac-5cda901-hw-5h/`; all five collected artifact SHA-256 hashes
(including the physical summary) were independently verified locally.

The routed report is complete but does **not** establish timing closure:
WNS -0.062 ns, TNS -4.622 ns, 184 failing setup endpoints; hold and pulse-width
failures are zero. The bounded failing-path sample concerns ECI logic.
This image is available for experimental F_MAC1 board validation, not a
validated link fix. No replacement build or further CI timer is needed.
The existing frozen F_MAC3 trace remains untouched; recover/preserve it
before programming this image. No board result for F_MAC1 is claimed.

## F_MAC1 board validation, September 28

Tested revision `5cda9017fdae1177c002ad0ba51ec3176019c917`, pipeline 514561,
with matching hardware job 2852730, software job 2852729, LTX and trace map.
Both zuestoll14 and peer zuestoll12 were rechecked as reserved to pengxu.
Before resetting, the old F_MAC3 link had recovered: a bounded read with the
peer overrides restored recovered the entire 211,086,144-byte frozen buffer
with zero retransmissions, zero loss markers and zero packet-recorder drops.
Its head and tail matched the earlier JTAG samples. The old archive SHA-256 is
`671ee92e64b5d03fabca4e97fc5efbf6ddb5fa0df1eae4af23a408a488cede5e`.
The redundant slow full JTAG read was then stopped. Thus the previous capture
is preserved, and F_MAC3's fault is intermittent rather than an inability to
transfer a full buffer under all conditions.

F_MAC1 results:

- One verified cold boot passed on the first attempt: QLM8..13 locked and
  CCPI/ECI lanes 0..23 initialized. Matched module load and version verification
  passed without a `probe_versions` failure. Static edge ILA was armed before
  releasing boot; DDR tracing remained active through module load.
- Three batches of 100 add RPCs passed: 300/300 correct, zero RPC retransmissions.
- Before freeze: writeSlot 5,036,291, unwrapped, sampleLost=0, DMAerror=0.
  The sticky sampleLost bit became 1 only after freeze, while sources continued;
  the archived buffer has zero loss markers/reported lost samples.
- Three full UDP reads each returned **322,322,624 bytes**, each using 228,923
  accepted data responses, zero retransmissions/timeouts and stable status.
  All three SHA-256 hashes match:
  `b7532515fae2e5d4b500c302eb55d4bd75f2a80312130d9e18dc7532cb951c68`.
  Independent JTAG reads of the first and final 4 KiB match the UDP data.
- The first two auxiliary PCAP recordings dropped 155 and 356 packets locally;
  their UDP output files were nevertheless complete and identical. The final
  recording used temporary RAM storage and 200-us request pacing, giving zero
  socket drops, complete byte coverage, zero payload mismatches, zero invalid
  IPv4 checksums and a response for every request. Its actual receive buffer
  remained 212,992 bytes; do not attribute success to receive-buffer enlargement.
- ARP learned `.204` as `0c:53:31:03:01:cc` without a permanent neighbor.
  Leaf1 Ethernet100 / front-panel 26/0 stayed up at 100G/RS-FEC. Corrected and
  uncorrectable FEC counts and all four lane-error counters stayed zero before
  and after the reads; RX errors/drops were also zero. Main Ethernet96 was up
  with zero FEC corrections, uncorrectables, RX errors and drops after boot.
- Temporary VIO controls were restored: override_valid=0, network trigger=0,
  trace_stop=0, loopback=0, all CMAC/GT resets=0. The new image and RPC server
  remain running, with tracing resumed. Switch configuration was unchanged.

Evidence: `out/hardware-tests/cmac-fmac1-20260928/summary.json`, `board/`,
`received/`, `repeat/received/`, `final-capture/received/`, independent JTAG
samples, switch snapshots and `restore.log`. The old recovered capture is in
`recover-old/received/` under the same directory.

This validates F_MAC1 full-buffer readout in this run. It is one cold boot and
three reads of one frozen capture, not a long-duration reliability result or
proof that CMAC-to-GT distance caused F_MAC3's intermittent faults. Routed
WNS remains -0.062 ns with 184 failing setup endpoints; no timing gates were
changed and no further CI build was launched for this board test.
