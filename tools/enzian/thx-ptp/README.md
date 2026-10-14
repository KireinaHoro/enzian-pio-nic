# CN88xx CPU-NIC timestamp experiments

**Current result (October 14):** correcting the TX selector to 4 passed 10/10
paired RX/TX hardware timestamps, then 100/100 with diagnostics disabled.
The original watchdog period 1 works. See `ptp-port4-validation.json` and
`ptp-port4-control-validation.json` in `data/paper/nullrpc/acquisition/`.
The timeout discussion below records earlier tests and is superseded by this
result. Reusable modules and DPDK patches now live in
[`../thunderx-dpdk-ptp`](../../../../thunderx-dpdk-ptp/README.md); this directory
preserves the isolated experiment that produced the hardware evidence.

Board-specific Linux experiment for reserved zuestoll14 (DUT) and zuestoll07
(generator), kernel 6.8.0-64-generic on the DUT. No DPDK or firmware modification.
The management VF stays on stock nicvf. Do not load this test on another board
or VF: the fixed PTP address, data BGX1 and PCI addresses are board-specific.

## Build and mechanism

```
nix build --impure --file tools/enzian/thx-ptp/build.nix \
  --out-link out/thx-ptp-module -L
```

`prepare.py` copies the pinned Ubuntu kernel's ThunderX driver and PTP code,
renames its PTP symbols, exposes the fixed-address clock through a temporary
platform device, and restricts binding to the explicitly supplied data VF.
The final experimental build selects TX timestamp interface **4** (BGX1/LMAC0)
and includes message-mask-gated diagnostics (ethtool msglvl 0x500). The earlier
selector 1 meant BGX0/LMAC1 and caused the recorded timestamp timeouts. Module removal restores the saved PTP CFG
and COMP values; elapsed counter state is not restored.

`server.py` temporarily maps BGX1's RX packet-kind to 4, matching the stock PF
PTP mailbox configuration, and restores the original mapping afterward.
The original mapping was 0, shared with management; enabling PTP without this
correction caused L2 length errors. `packets.py` uses ordinary UDP and Linux
SO_TIMESTAMPING, preserving numbered raw hardware timestamps. This Python
probe is a functional validation, not a paper latency benchmark.

## Run

Verify reservations using `emg` and run root commands through
`tools/enzian/run.py`. Use RAM-backed output and fresh directories. Stage the
module plus `packets.py`, `server.py`, and `watchdog.py` together. The dated
`stage-dut.sh` and `restore-dut.sh` describe the original bind/restore procedure;
the staging helper expects a gateway HTTP server and matching SHA256SUMS.
Do not rerun its existing campaign directory. Preserve before/after interfaces,
routes, neighbors, kernel logs, TAD state, module hashes and register values.

On the DUT, after binding only 0002:01:00.2 to lh_nicvf, run:

```
python3 server.py --interface cpu40g1 --bind 192.168.129.193 \
  --peer 192.168.128.225 --count 100 --rx-only --output NEW_SERVER_DIR
```

After its `ready` file appears, on the generator run:

```
python3 packets.py client --interface cpu40g1 --bind 192.168.128.225 \
  --peer 192.168.129.193 --count 100 --output NEW_CLIENT_DIR
```

These are the observed campaign addresses, not permanent assignments. Check
live addresses first. Overlapping FPGA/CPU subnets caused ARP flux; this test
used temporary static data-interface neighbors: DUT c1 MAC
`0c:53:31:03:01:c1`, generator e1 MAC `0c:53:31:03:00:e1`. Preserve and restore
original neighbor entries. Remove `--rx-only` to require paired RX/TX timestamps.
Only one timestamped TX is outstanding. Failure reports preserve received RX
values. TX error-queue polling is nonblocking with a two-second deadline.

For a one-request watchdog test, run on the DUT:

```
python3 watchdog.py --period 8 --output NEW_WATCHDOG_DIR
```

Wait for `NEW_WATCHDOG_DIR/packets/ready`, then run the client with `--count 1`.
Repeat with period 15 in a new directory. The wrapper changes only bits 19:16
of data-interface SEND_CFG (PF BAR0 + 0x300), verifies readback, and restores
0x10009 afterward; management +0x200 remains 0x10008. It requires the known
initial value and test driver. Use message mask 0x500 to record TX request/CQE
status, then disable it. The timeout is `4 * 2^period * 1024` coprocessor cycles;
do not assume that clock equals the 800 MHz PTP reference.

## Results and current state

October 14: two RX-only cohorts passed 100/100 each with nonzero increasing
hardware timestamps and independent client completions. TX requests produce
SEND_PTP status 0x86 and raw timestamp 0, interpreted by the active driver and
manual as TSTMP_TIMEOUT. Periods 8 and 15 both failed despite correct replies.
Software request-to-CQE delays were 1.082 ms and 134.249 ms, consistent with a
roughly 1 GHz watchdog clock; these are not calibrated MAC measurements.
BGX1 SMU_TX_INT was 0x2 (link-change bit; fake-commit bit clear).
Kernel/TAD health passed. Paired MAC-to-MAC timestamps remain unavailable.

Evidence: `data/paper/nullrpc/acquisition/ptp-packet-validation.json` and
`ptp-watchdog-validation.json`. Full logs remain in ignored `out/thx-*` and
`/dev/shm/lauberhorn-thx-ptp-20261014` on the two machines.

After the watchdog follow-up, the test module/PHC and data interfaces remain
available for continued investigation. RX/TX timestamp mode is disabled,
BGX1 RX_ID_MAP is restored to 0, both watchdogs are at period 1, diagnostics are
off, and the temporary data-port neighbor entries remain. Recheck live state
before reuse. No reboot, FPGA programming, or private firmware source access
was involved. No commits or pushes were made.

### Descriptor and single-outstanding confirmation

A subsequent single-request trial after a fresh test-driver load captured the
actual header immediately before the TX doorbell: word 0
`0x150160220e000058`, word 1 zero. Decoding bits 56..59 gives TSTMP=1, DS=0,
PNC=1, TSO=0. PNC is Linux's `post_cqe` and was already enabled, so this test
requires no new PNC setting. Live PF QS1_CFG at offset 0x20210000 reads
`0x80020001`: SEND_TSTMP_ENA=1 and ENA=1, VNIC=1.

There was exactly one timestamp request and one SEND_PTP completion. The driver
initializes its outstanding counter to zero on open and uses the primary VF's
shared atomic gate across queues; the probe sends no subsequent response until
it consumes a timestamp completion. This one-request trial rules out overlap
between this probe's timestamp operations. The client received the response,
but the completion remained status 0x86 with timestamp zero. Health passed.
Raw diagnostics and provenance: `ptp-descriptor-validation.json` in the paper
acquisition directory. The descriptor diagnostic build remains loaded with
logging and timestamp mode disabled. No firmware or FPGA state was changed.

### Public support/errata search (October 14)

Searched upstream Linux commit history, Cavium's timestamp patch/review threads,
ThunderX/ptp4l reports, DPDK ThunderX documentation, and the publicly released
Cavium BDK code/CSR definitions in coreboot. No public CN88xx TX-PTP disablement,
matching persistent SEND_PTP timeout report, or known corrective workaround was
located. This is a bounded negative search, not proof that unpublished errata
do not exist or that CN88xx 40G TX was validated by the original authors.
No private BDK/ATF repositories were accessed.

- Cavium's [upstream timestamp commit](https://github.com/torvalds/linux/commit/4a8755096466d1002236ac7293eae821b6de87ba)
  explicitly adds RX and TX timestamp support. It limits TX to one outstanding
  operation, delivers timestamps in separate CQEs, and skips timestamp requests
  for TSO packets. Our single-packet test satisfies these restrictions.
- The [PTP PCI-ID correction](https://github.com/torvalds/linux/commit/aa3afccc9adfff27dbb8f1b92912adbdffa86561)
  explicitly retains CN88xx, CN81xx and CN83xx subsystem IDs and excludes the
  architecturally different OcteonTX2 block despite its reused vendor/device ID.
  Thus this is not merely an OcteonTX2 implementation mistaken for CN88xx support.
- [CN88xx pass-2 TSO completion erratum](https://github.com/torvalds/linux/commit/7ceb8a1319ec64954459d474dd4a8c3c60ff0999):
  extra completions per TSO segment; the workaround changes post-CQE behavior
  and adds a dummy descriptor. Our measured TSO bit is zero.
- [CQ-full erratum](https://github.com/torvalds/linux/commit/4c0b6eaf373a5323f03a3a20c42fc435715b073d)
  concerns high-rate reception (>20 Mpps) and CQ_WR_FULL stalls. Another
  [TX queue fix](https://github.com/torvalds/linux/commit/bd3ad7d3a14b07aeeb4f92abc757672719e2a0eb)
  reserves completion space. These do not match a delivered, isolated response
  followed by an explicit timestamp-timeout CQE.
- [BGX TX-underflow workaround](https://github.com/torvalds/linux/commit/971617c3b761c876d686a2188220a33898c90e99)
  reports SGMII transmission wedging and toggles LMAC enable. Our link is 40G,
  replies arrive, and SMU_TX_INT underflow/fake-commit bits were clear; no LMAC
  reset is justified by this report.
- Public [Cavium BGX CSR definitions](https://github.com/coreboot/coreboot/blob/main/src/vendorcode/cavium/include/bdk/libbdk-arch/bdk-csrs-bgx.h)
  describe SPU_DBG_CONTROL[TIMESTAMP_NORM_DIS], bit 28: 40G RX timestamps can be
  normalized for lane skew; normalization trades off alignment-marker granularity
  versus skew compensation. This affects RX accuracy, not a missing TX CQE
  timestamp. The same source describes SMU_TX_INT[FAKE_COMMIT] when SPU fails
  to acknowledge a timestamped transmit within 256 cycles; that bit was clear
  in our recorded 0x2 status. Neither is evidence of a general PTP prohibition.

The public implementation establishes intended support, not a demonstrated
working CN88xx 40G TX result. The remaining focused investigation is the
CN88xx NIC-to-BGX timestamp request/return routing and any revision-specific
firmware/silicon requirements absent from these public sources.

### Live port-identity verification

The subsequent sysfs/CSR cross-check confirms cpu40g1 (MAC
0c:53:31:03:01:c1) is PCI 0002:01:00.2, virtfn1 of PF 0002:01:00.0.
Enabling hardware timestamps through that interface changes only BGX1 LMAC0
(PCI 0000:01:10.1) SMU_RX_FRM_CTL from 0x1f to 0x101f, setting PTP bit 12.
All eight BGX/LMAC slots were compared; management BGX0 is unchanged.
Disabling timestamps restores the full snapshot exactly. cpu40g0 is virtfn0,
PCI 0002:01:00.1, and remains bound to stock nicvf. Evidence is
`ptp-port-verification.json`. This confirms the intended port's RX PTP enable;
it does not resolve the remaining TX timestamp-return timeout.

### Upstream workflow audit

Compared the generated descriptor-test module directly against the exact pinned
Ubuntu 6.8.0-64 source, normalizing renamed PTP symbols. The VF open path,
queue-set mailbox configuration, timestamp ioctl, descriptor construction,
and completion handling are upstream code. Differences in those files are
explicit data-VF binding, module/clock lifetime, TX timestamp interface 0→1,
and diagnostic logs. The stock PF/BGX drivers handle their normal mailbox path.

Upstream PTP probe calls pcim_enable_device and pcim_iomap_regions on the
PCI-enumerated PTP function, initializes the software timecounter, reads the
clock reference, enables CLOCK_CFG.PTP_EN, writes CLOCK_COMP, and registers
the PHC. Our fixed-address platform device performs the same PTP register
writes and PHC setup, but cannot perform normal PTP PCI enable/probe because
that function is absent. Our timecounter initialization follows clock enable
rather than preceding it; that changes software epoch anchoring, not raw
hardware SEND_PTP generation. No other TX-specific PTP hardware initialization
was found in the upstream probe. The missing PCI/firmware exposure remains an
unresolved platform difference, not established as the timeout's cause.

Upstream VF open sets SEND_TSTMP_ENA when it has a PHC, resets the outstanding
counter, configures queues and RX timestamp state. SIOCSHWTSTAMP accepts TX_ON
but does not perform another TX-enable register write; its hardware mailbox
change controls RX prefix insertion. SO_TIMESTAMPING TX_HARDWARE requests the
per-packet descriptor bit; RAW_HARDWARE requests reporting. SEND_PTP success
is converted with the PHC timecounter and passed through skb_tstamp_tx to
MSG_ERRQUEUE. Our request reaches hardware but returns timeout before that
success path. No ptp4l/phc2sys synchronization step is needed merely to capture
a TX timestamp or subtract same-clock RX/TX values. No additional upstream
socket/driver enable step was found missing. The board-specific RX packet-kind
correction and experimental TX interface selector remain explicit deviations,
not demonstrated upstream requirements for every CN88xx system.
