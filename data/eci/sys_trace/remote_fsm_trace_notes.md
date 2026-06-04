# Remote FSM Trace Checker Notes

## Inputs

The per-cache-line trace CSVs produced by `export_trace_pcap.py --output-mode
eci-state` contain chronological visible OCI/ECI link messages for one cache
line:

```text
time,opcode_name,dmask
35633139698,ECI_CMD_MREQ_RLDD,15
35633139978,ECI_CMD_MRSP_PSHA,15
35633161598,ECI_CMD_MFWD_SINV_H,15
35633161641,ECI_CMD_MRSP_HAKD,0
```

The ThunderX FSM specs in `deps/thx-fsms/specifications/*.json` are Mealy
machines. Each transition consumes one input on a port, emits zero or more
outputs, and enters a new state.

For this task the ThunderX/CPU is the `remote` node and Lauberhorn is the home
node. `deps/thx-fsms/specifications/system.json` marks the link channels as
`BAG`, so strict CSV order can produce false positives when the trace projection
observes link reordering.

## Port Polarity

For the remote FSM:

- `InReq`: home to remote request/forward.
- `InResp`: home to remote response.
- `OutReq`: remote to home request.
- `OutResp`: remote to home response/ack/victim.
- `SW`: unobserved CPU-side local trigger.

The JSON specs were produced from `deps/thx-fsms/legacy/custom-to-json.py`;
that script is the authoritative port classification for FSM message names.

## OCI To FSM Mapping Used Initially

This first checker intentionally maps only the OCI opcodes needed by the current
trace and common adjacent cases.

Visible incoming link messages:

- `ECI_CMD_MRSP_PSHA` -> `InResp AS_d`
- `ECI_CMD_MRSP_PEMD` with nonzero `dmask` -> `InResp AE_d`
- `ECI_CMD_MRSP_PEMD` with `dmask=0` -> `InResp AE`. The OCI spec calls this
  no-data form equivalent to old `PEMN`.
- `ECI_CMD_MFWD_SINV_H` -> `InReq IV`
- `ECI_CMD_MFWD_SINV` -> `InReq IV`
- `ECI_CMD_MFWD_FLDRS_E`, `ECI_CMD_MFWD_FLDRS_O`,
  `ECI_CMD_MFWD_FLDRS_EH`, `ECI_CMD_MFWD_FLDRS_OH` -> `InReq FS_d`
- `ECI_CMD_MFWD_FLDX_E`, `ECI_CMD_MFWD_FLDX_O`,
  `ECI_CMD_MFWD_FEVX_EH`, `ECI_CMD_MFWD_FEVX_OH` -> `InReq FE_d` when their
  `dmask` requests data, otherwise `InReq FE`

Visible outgoing link messages that can be produced by hidden `SW` transitions:

- `ECI_CMD_MREQ_RLDD` -> `OutReq IS_d`, usually from hidden `SW LS`
- `ECI_CMD_MREQ_RLDI` -> `OutReq IS_d`, usually from hidden `SW LS`
- `ECI_CMD_MREQ_RLDX` -> `OutReq IE_d`, usually from hidden `SW LE`
- `ECI_CMD_MREQ_RC2D_S` -> `OutReq SE_d`, usually from hidden `SW LE`

Visible outgoing responses:

- `ECI_CMD_MRSP_VICS` -> `OutResp SI`
- `ECI_CMD_MRSP_VICD`, `ECI_CMD_MRSP_VICC` -> `OutResp EI`
- `ECI_CMD_MRSP_VICDHI` -> `OutResp FA_d` when it carries data, otherwise
  `OutResp FA`. The OCI spec describes `VICDHI` as the response to `FEVX_2H`
  and effectively a combination of `VICD` and `HAKI`; the remote FSM has only
  the abstract forward-ack symbols.
- `ECI_CMD_MRSP_HAKD` with `dmask=0` is ambiguous in OCI naming. The OCI PDF
  says `HAKN`/`HAKD.N` is a no-data synonym, while the FSM distinguishes the
  reason for the ack. The checker therefore lets this opcode match any currently
  expected `OutResp` in `{FA, FA_d, FAI, FAS, IAI, SI, EI}`.

## Trace 0x80 First Observation

`addr_0x0000000080.csv` contains almost entirely:

```text
RLDD -> PSHA -> SINV_H -> HAKD.N
```

That corresponds to a remote read miss installing S, followed by a home-side
invalidation and remote ack. The final visible event in this CL trace is a
`SINV_H` with no following ack in the same file, which matches the reported
symptom that the CPU did not respond to a `SINV_H` for `0x80`.

## Updated Findings On The Iperf TX Timeout Trace

After adding `PEMD dmask=0` and `VICDHI` handling, all `addr_0x*.csv` traces in
`iperf-tx-0x0-timeout` conform to the remote FSM except `addr_0x0000000080.csv`.

The previously failing TX control CLs were valid:

- `addr_0x0000008000.csv` and `addr_0x0000008080.csv` contain
  `RC2D_S -> PEMD dmask=0 -> FEVX_EH -> VICDHI` sequences.
- `PEMD dmask=0` is the no-data exclusive response (`AE`) in the FSM.
- `VICDHI` is bridged to the abstract data-bearing forward ack (`FA_d`) used by
  the FSM for `FEVX_2H`.

The final global per-CL window shows the crash-specific sequence:

```text
39276726081  addr_0x0000000000  ECI_CMD_MREQ_RLDD
39276726182  addr_0x0000000080  ECI_CMD_MFWD_SINV_H
```

`0x0` and `0x80` are the two RX control CLs. A CPU read of one control CL is the
2F2F trigger that makes Lauberhorn invalidate the previous control CL. Thus the
final `SINV_H` to `0x80` is the expected follow-up to the CPU's `RLDD` of `0x0`.
The FSM accepts the `SINV_H` and then requires an outgoing ack; none appears.

Normal `SINV_H -> HAKD` latency on the RX control CLs is stable right up to the
failure:

- `0x0`: 28,978/28,978 invalidations acked, last latencies about 40-42 ticks.
- `0x80`: all prior invalidations acked, last latencies about 41-44 ticks, then
  the final invalidation has no ack.

The trace is not truncated immediately after the missing ack. `addr_none.csv`
contains a later `GSYNC/GSDN` pair at time `39307526850`, about 30.8 million
ticks after the final `0x80` `SINV_H`.

The kernel panic reports an uncorrected ThunderX L2C LFB entry timeout before
the external abort. This is consistent with a ThunderX-side in-flight coherence
entry that stopped making progress.

Current root-cause hypothesis:

- Lauberhorn's visible coherence behavior conforms to the remote FSM up to the
  final invalidation.
- The final missing ack is likely a ThunderX microarchitectural stuck condition,
  not a malformed Lauberhorn message for that CL.
- The TX workload is still implicated because it creates sustained ownership and
  eviction traffic on TX overflow CLs (`RLDX/PEMD/VICD` triples) and the software
  TX path explicitly executes `CVMCACHEWBIL2` (`cl_hit_wb_inv`) on those overflow
  CLs. That code path has an existing FIXME saying to remove it after verifying
  it is not the issue.

## GSYNC/GSDN Interleaving Check

`addr_none.csv` contains the address-less GSYNC stream. In this trace:

- `ECI_CMD_MREQ_GSYNC`: 2,495,097 events.
- `ECI_CMD_MRSP_GSDN`: 2,495,097 events.
- Every GSYNC has a following GSDN; there are no unmatched requests.
- `GSYNC -> GSDN` latency is 0-4 trace ticks.

The GSYNC path in `vivado/eci/rtl/lauberhorn_eci.vhd` uses dedicated GSYNC
channels:

- VC7 GSYNC -> VC11 GSDN for odd.
- VC6 GSYNC -> VC10 GSDN for even.

Both instantiate `loopback_vc_resp_nodata`, which accepts a GSYNC only when it
has no pending response and then holds GSDN valid until the response VC accepts
it. In the trace, that path never appears backpressured.

There is no tight GSYNC/coherence interleaving near the `0x80` failure:

```text
bad SINV_H          39276726182
previous GSYNC      39060528307   (-216197875 ticks)
next GSYNC          39307526850   (+30800668 ticks)
```

The last dense GSYNC burst around `39060528307` is isolated from address-bearing
coherence traffic in this trace. The nearest following CL event is about 439k
ticks later, and the following RX-control sequence still behaves normally:

```text
39060987430  addr_0x0000000000  ECI_CMD_MREQ_RLDD
39060987531  addr_0x0000000080  ECI_CMD_MFWD_SINV_H
39060987573  addr_0x0000000080  ECI_CMD_MRSP_HAKD
39060987681  addr_0x0000000000  ECI_CMD_MRSP_PSHA
```

Across the whole per-address CSV set, the closest observed GSYNC to any
address-bearing coherence event is 14,092 ticks; none is within 2,000 ticks.

Conclusion from this trace: GSYNC serialization is not supported as the
proximate cause of the missing `0x80` `SINV_H` response. The simple loopback
responds promptly and GSYNC does not appear interleaved with the failing
coherence sequence. This does not prove the loopback semantics are globally
correct, but this capture does not show the peculiar GSYNC/coherence ordering
change we were looking for.
