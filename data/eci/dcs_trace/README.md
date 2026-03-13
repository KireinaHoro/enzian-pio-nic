## DCS and ECI traces from L2C_TAD TIMEOUT kernel panics

Due to the manner of the timeout triggering after a lot of traffic, the ILAs
are not sufficient to capture enough debugging information, since you can
trigger them only a limited number of times (e.g. 64 windows).

This directory holds various traces captured to debug the TIMEOUT issues we
have on the bypass path of Lauberhorn.

### Trace collection

To assist debugging, I built a `TraceBuffer` that can capture messages on
`Stream[_]` interfaces.  Three types of traces exist for every failure:

- `dcs.csv`: DCS events, aggregated from the 4 tracing ports, 2 for each DC
    slice.
- `even_app.csv` and `odd_app.csv`: ECI messages received and sent by each DC
    slice, aggregated from the 6 ECI ports to the gateway.  Captured in the
    `app` clock domain @ 200 MHz, between the DC and the SLR/CDC FIFOs and
    width converters.
- `even_sys.csv` and `odd_sys.csv`: same contents as the above, but captured in
    the `sys` clock domain @ ~322 MHz, between the SLR/CDC xings and the gateway.

The panic dmesg (on the CPU console) can be relevant, so we also save the panic
message as `panic.txt`.  This is sadly neglected earlier, so some traces lack
this part.  It looks like there are at least two types of panic:

- `Asynchronous SError Interrupt`
- `synchronous external abort: Fatal exception in interrupt`

It's not entirely clear what is generated on which circumstance.  A rough
observation is that in the synchronous case, we can usually easily locate a
read request from the THX that was not responded to.  The asynchronous case,
usually looks like there are no apparent message that lacked a response.

### The traces

As of current, two timeout situations have been obeserved: _at link bringup_
and _during `iperf`_.  These have different manifestations and could be related.

#### At link bringup

This case is not reliably reproducible, but appears often enough (~2 times per
week).  The current hypothesis is that I need to program the FPGA but leave
the CPU held in firmware (before pressing N) for some hours.

Symptom is that a KP happens immediately at `ip link up` -- before it returns.
The following traces demonstrate this:

- [early-timeout-rd-0x100](./early-timeout-rd-0x100), potentially incomplete:
    - used old `TraceBuffer` that couldn't capture in the very first cycles
    - missing `panic.txt`

#### During `iperf`

This is the most common case and accounts for almost 100% of attempts -- no
`iperf` invocation has run through without panicking yet.

Symptom is that the link up process, along with a few diagnostic pings (as
scripted in `/scratch/pengxu/test-bypass.sh`), will succeed.  Then, launch
`iperf -s` on `enzian-gateway` and launch `iperf -c 192.168.191.254`.  A KP
will happen before the test finishes.

Two modes of operations have been attempted:

**With `cl_wb_hit_inv` on the TX overflow cachelines**, traces:

- async timeout on 0x8080: [first](./iperf-timeout-0x8080-2026-03-13)
    [second](./iperf-timeout-0x8080-2-2026-03-13)
    [third](./iperf-timeout-0x8080-3-2026-03-13)
- sync timeout on 0x8000: [first](./iperf-timeout-0x8000-2026-03-13)

**Without `cl_wb_hit_inv`**, traces:

- WIP
