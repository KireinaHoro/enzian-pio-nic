# Recorded EMG use cases

Follow the [EMG command safety rules](../SKILL.md#emg-command-safety): only recorded
command forms/options are allowed; never use `--help`, `-h`, guessed commands or
bare invocations to discover usage. EMG is a fragile Bash script and a help flag
can trigger provisioning. These examples record syntax, not authorization to
interrupt a board. Run the commands on `enzian-gateway` as the reservation owner;
replace uppercase placeholders before execution.

Provision reservations, boot selection and root volumes exclusively through EMG.
Never edit PXE/GRUB, iSCSI/TGT exports or their backing volumes by hand. Use the
repository harness for power sequencing, FPGA programming, boot and acceptance.

## Inventory and ownership

```sh
emg list-machines
emg list-images
```

From the build host, use `ssh enzian-gateway 'emg list-machines'` and
`ssh enzian-gateway 'emg list-images'`. Check both inventories immediately before
acquisition and again afterwards. Confirm ownership of both DUT and peer before
board access. `is_up` is not proof of Linux readiness or hardware acceptance.
Leave user-designated busy boards and their volumes untouched.

## Boot bundle and CPU isolation profiles

Build and stage the matching kernel/initrd using the
[boot bundle workflow](../../../../../docs/development/workflow.md#thunderx-cpu-nic-timestamps).
The recorded bundle is kernel `6.8.0-146-generic`; modules and the deployment
manifest must match it. In the gateway shell, the recorded settings are:

```sh
boot_kernel="$USER/lauberhorn-6.8.0-146-ptp/vmlinuz"
boot_initrd="$USER/lauberhorn-6.8.0-146-ptp/initrd.img"
worker_args='isolcpus=nohz,domain,managed_irq,44-47 nohz_full=44-47 rcu_nocbs=44-47 irqaffinity=0-43 kthread_cpus=0-43 rcu_nocb_poll copymods=force'
generator_args='isolcpus=nohz,domain,managed_irq,16-47 nohz_full=16-47 rcu_nocbs=16-47 irqaffinity=0-15 kthread_cpus=0-15 rcu_nocb_poll copymods=force'
```

The DUT profile reserves CPUs 44–47 for RPC workers. The 32-core generator
profile reserves CPUs 16–47, leaving 0–15 for housekeeping. Select the profile
for the actual role; do not copy DUT isolation onto the generator.
`copymods=force` selects the boot bundle's module tree. The DPDK runner also
checks and temporarily corrects IRQ, RPS and workqueue placement.

## Acquire a fresh root volume

For a new test requiring a fresh root, choose an available golden-image date and
a checked-unused, purpose/board/date-specific volume name. The recorded golden
selection is `2025-07-28`; check that it is available before using it. For example,
after setting the profile above:

```sh
emg acquire zuestoll14 -n NEW_UNUSED_VOLUME -g SELECTED_GOLDEN_DATE \
  -a "$generator_args" -k "$boot_kernel" -i "$boot_initrd"
```

The machine must be unreserved and the new volume name absent from the owner's
inventory. `-n` reuses an existing volume: `-g` does not force a fresh clone.
Never reuse a golden image's name or another experiment's volume name. If the
name exists, choose another and recheck; do not delete or overwrite it.

## Reacquire an existing volume

When reacquiring a released board, restore its recorded named volume, kernel,
initrd and arguments. Create a new volume only when requested. Confirm that the
volume exists and is not in use by another reservation. Omit `-g` when reusing it.
The recorded hot-echo assignments are:

| Role | Board | Existing volume | Arguments |
| --- | --- | --- | --- |
| DUT | zuestoll07 | `lauberhorn-linux-6.8` | `worker_args` |
| Generator | zuestoll14 | `echo-generator32-z14-20261021` | `generator_args` |

These names record prior assignments, not live reservations. After inventory and
ownership checks, the recorded command forms with the settings above are:

DUT reacquisition:

```sh
emg acquire zuestoll07 -n lauberhorn-linux-6.8 \
  -a "$worker_args" -k "$boot_kernel" -i "$boot_initrd"
```

Generator reacquisition:

```sh
emg acquire zuestoll14 -n echo-generator32-z14-20261021 \
  -a "$generator_args" -k "$boot_kernel" -i "$boot_initrd"
```

Execute only the command for the requested board. Require EMG's output to confirm
the named volume already exists and creation was skipped. The generator volume
was originally cloned from golden `2025-07-28`; reacquisition retains its contents.
Restore an accidentally released reservation without rebooting unless boot or
configuration recovery is needed. Inspect campaign failure/restoration evidence
before resuming; preserve accepted acquisitions and failed attempts.

## Change boot selection or board role

For an intentional boot/profile change, preserve measurements and failure captures,
stop workloads and shut down using the repository harness before release. Recheck
both inventories, release the owned reservation, then reacquire its intended
volume using the explicit `-a`, `-k` and `-i` settings above. A role swap must not
leave the same root volume attached to two active boards. Boot with the normal
harness and validate the new kernel, command line and matching artifacts.

For reservation-only recovery, preserve the existing boot selection and avoid an
unnecessary reset. Reacquisition changes provisioning; it does not itself prove
that a running machine has booted the selected kernel or arguments.

## Release and retain provenance

```sh
emg release MACHINE
```

Release only an owned board whose workload has finished or whose release was
requested. Named volumes persist after release. Their iSCSI export is shared by
owner and volume name, not reservation: releasing one board can remove another
board's root storage if they share a name. Never share an active volume name.

Retain the actual command/arguments and EMG output, before/after inventories,
board/owner/role, named volume, golden date for a fresh clone, kernel/initrd paths
and hashes, kernel arguments, and boot/restoration results in run provenance.
Keep transient ownership and dated observations in `agents/` or ignored run
evidence; this reference owns the reusable provisioning procedure.
