# Scheduler design pivot (Sep 2026)

## Decomposition of what Lauberhorn actually does:

- A: NIC can wake up and suspend domains precisely on:
  - Incoming network messages (RPC requests, nested RPC calls)
  - Host-side events; *HOST_WAKE* (proposal, see below)
- B: NIC can offload L7 processing (full unmarshal/marshal)
  - Unmarshal func name -> func ptr
  - Unmarshal func args
- C: NIC can have the CPU invoke the handler very fast:
  - If the domain is running from A: direct jump using B

Different applications can exercise different capabilities (A, AB, ABC) i.e. adoption level

- Microbenchmarks, NFS server, Raft: ABC
- Dandelion: AB* (only outer ONCRPC envelope in Sven's current impl; could just be A)
  - Main issue: handler dispatch must setup sandbox
- Memcached: A
  - not RPC at all (libevent loop)

## HOST_WAKE to incorporate host-side events (timers, disk, normal NIC, etc.)

Inject a "ctrl request" (same thing as *NACK*; i.e. not actually a network request) to application domain when:

- Kernel-side wakeup requests (device IRQs from disk/normal NIC, IPIs, global timer device, etc.)
  - Must run on another core (current core is stalled in RX LD)
  - Register?  (kernel doesn't have access to 2f2f TX endpoint mapped to user)
- app-programmed deadline elapses
  - Set by SW into NIC (as TX ctrl descriptor?  user can't touch regs)
  - Useful for managing timers (earliest elapsed timer)
    - Can't use direct kernel wake-up since hrtimers are core-local

## CAS replacement with PREEMPT->yield syscall, keeping interrupt only for killing

CAS critical section ensures that
- When NIC decides to preempt a domain, CPU can’t run 2F2F
- When CPU is running 2F2F, NIC can’t preempt

When NIC wants to preempt, instead of serving the next element in queue (network req or *NACK*):
- serve *PREEMPT* (also a "ctrl request")
  - future reads should also only get PREEMPT
- CPU does syscall `bool lauberhorn_yield()` (returns next parity and mailbox VA; see below DCU color section)
  - If CPU doesn’t do syscall in time, issue IRQ to kill process
- Kernel reads next PID and next parity
  - 2F2F pipe rewired to pop from new domain queue
- Return to lauberhorn_yield syscall of new domain with new parity and mailbox VA

## Yield syscall also solves the DCU color problem (HoL blocking):

We needed to keep VA of the QP the same, but we don't want to invalidate mapping on migration
- this is why we added EciThreadClRouter
- but router conflicts with the coloring problem of DCUs for progress
- we can now return the VA of the QP/mailbox from yield syscall
  - no more need to shootdown mapping, but still can read from correct mailbox
  - no more need for router
  - still can keep #threads = degree of parallelism
