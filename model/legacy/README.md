# Historical sources

`tla/lauberhorn.tla` is Jasmin's original canonical model. The two subdirectories
contain frozen Toolbox-generated snapshots that differ from it. They use the
older READY/BUSY and interrupt-driven scheduling design, set-valued links and
no finite DCU resource model. Keep them as research history, not active modules.

Their configurations moved to `../config/legacy` and historical outputs to
`../evidence/legacy`; original bytes are preserved. Reproducing a snapshot means
copying its matching `.tla` files and `.cfg` into one isolated working directory.
No current TLC verification result is claimed for them. The canonical source is
included in the SANY syntax/semantic check only.

The removed `LateUnlock`, `DcsDirectoryCycle` and auto-lock configuration variants
are available with the pre-review tree at commit `4cb1e8a`. Historical evidence
retains the paths and source hashes used by those runs. The active source does
not retain the superseded UL protocol to replay those tests.
