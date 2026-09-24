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
