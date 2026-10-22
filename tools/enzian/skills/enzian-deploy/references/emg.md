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

## Workflow-supplied configuration

The calling workflow owns the target board, existing/new volume choice, volume
name, golden-image selection, kernel/initrd artifacts and kernel command line.
Read those values from its documentation, manifest or preserved reservation
record. This skill defines how to apply them and does not select defaults.

## Create a volume from a golden image

For a workflow requiring a fresh root, select its golden image from the inventory
and confirm its requested new volume name is unused. Supply the workflow's board,
volume name, golden-image identifier, kernel/initrd paths and complete kernel
command line with the recorded options:

```sh
emg acquire MACHINE -n NEW_UNUSED_VOLUME -g GOLDEN_IMAGE \
  -a "$kernel_args" -k "$boot_kernel" -i "$boot_initrd"
```

`-n` names the root volume; `-g` selects the source for a new clone. The machine
must be unreserved and the new volume name absent from the owner's inventory.
`-n` reuses an existing volume: `-g` does not force a fresh clone. If a fresh-root
name exists, choose another and recheck; do not delete or overwrite it. Never
reuse a golden image's name or another experiment's active volume name.

## Select an existing volume

Confirm that the workflow's named volume exists and is not in use by another
reservation. Omit `-g` when reusing it:

```sh
emg acquire MACHINE -n EXISTING_VOLUME \
  -a "$kernel_args" -k "$boot_kernel" -i "$boot_initrd"
```

Require EMG's output to confirm the named volume already exists and creation was
skipped. Reacquisition restores the workflow's recorded volume and boot settings;
create a new volume only when requested. Preserve existing contents and avoid an
unnecessary reboot when recovering an accidentally released reservation. The
calling workflow owns its failure/restoration checks and resume policy.

## Select a kernel and supply its command line

`-k` selects the staged kernel and `-i` its matching initrd. Their values are paths
relative to the gateway's user-kernel directory, supplied by the artifact-building
workflow. Stage and verify those artifacts before acquisition. `-a` supplies the
complete kernel command line as one quoted argument. The calling workflow owns
all options in that string, including isolation, IRQ placement and module-loading
options. Do not substitute another workflow's kernel, image or CPU profile.

## Change boot selection or board role

For an intentional boot/profile change, preserve measurements and failure captures,
stop workloads and shut down using the repository harness before release. Recheck
both inventories, release the owned reservation, then reacquire its intended
volume using its workflow-supplied `-a`, `-k` and `-i` settings. A role swap must not
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
