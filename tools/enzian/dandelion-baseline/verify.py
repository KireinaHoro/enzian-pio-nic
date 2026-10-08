#!/usr/bin/env python3
"""Audit immutable source trees and deployment ELF properties on the build host."""
import hashlib
import json
from pathlib import Path
import struct
import sys


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def tree(root):
    result = {}
    for path in root.rglob("*"):
        if path.is_symlink():
            result[str(path.relative_to(root))] = ("link", str(path.readlink()))
        elif path.is_file():
            result[str(path.relative_to(root))] = ("file", digest(path))
    return result


def elf(path):
    data = path.read_bytes()
    assert data[:6] == b"\x7fELF\x02\x01", path
    kind, machine = struct.unpack_from("<HH", data, 16)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    segments = [struct.unpack_from("<IIQQQQQQ", data, phoff + i*phsize) for i in range(phnum)]
    return {"sha256": digest(path), "machine": machine, "type": kind,
            "interpreter": any(s[0] == 3 for s in segments),
            "dynamic_segment": any(s[0] == 2 for s in segments),
            "lowest_load_address": min(s[3] for s in segments if s[0] == 1)}


def main():
    paths = json.loads(Path(sys.argv[1]).read_text())
    original, prepared = tree(Path(paths["baseline"])), tree(Path(paths["src"]))
    added_lock = prepared.pop("Cargo.lock")
    assert original == prepared, "Server source differs beyond added root Cargo.lock"
    original_client = tree(Path(paths["artifact"]) / "client")
    prepared_client = tree(Path(paths["clientSrc"]))
    expected_client = {p: v for p, v in original_client.items()
                       if p in ("Cargo.toml", "Cargo.lock") or p.startswith("src/")}
    assert prepared_client == expected_client, "Original client sources or lock changed"
    binaries = {
        "server": Path(paths["server"]) / "bin/.dandelion_server-wrapped",
        "server_timestamps": Path(paths["serverTimestamps"]) / "bin/.dandelion_server-wrapped",
        "worker": Path(paths["worker"]) / "bin/mmu_worker",
        "rload": Path(paths["client"]) / "bin/rload",
        "matmul": Path(paths["fixtures"]) / "share/dandelion-baseline/matmul",
    }
    metadata = {name: elf(path) for name, path in binaries.items()}
    assert all(info["machine"] == 183 for info in metadata.values()), "Non-AArch64 deployed ELF"
    worker = metadata["worker"]
    assert not worker["interpreter"] and not worker["dynamic_segment"], "Worker is not fully static"
    assert worker["lowest_load_address"] == 0xAAAAAAAA0000, "Unexpected worker load address"
    original_matmul = Path(paths["baseline"]) / "machine_interface/tests/data/test_elf_mmu_aarch64_matmul"
    assert digest(original_matmul) == metadata["matmul"]["sha256"], "Function ELF changed"
    runner = (Path(paths["runner"]) / "bin/dandelion-baseline").read_text()
    assert "/local/" not in runner and "@diagnostic@" not in runner, "Runner references build workspace"
    for script in [Path(paths["runner"]) / "bin/dandelion-baseline",
                   Path(paths["server"]) / "bin/dandelion_server",
                   Path(paths["serverTimestamps"]) / "bin/dandelion_server"]:
        interpreter = Path(script.read_text().splitlines()[0].removeprefix("#!").split()[0])
        assert elf(interpreter)["machine"] == 183, f"Non-AArch64 script interpreter: {script}"
    summary = {"passed": True, "server_source_files": len(original),
               "server_only_added_file": "Cargo.lock", "server_lock_sha256": added_lock[1],
               "unchanged_client_files": len(expected_client), "binaries": metadata,
               "image": {"path": paths["image"], "sha256": digest(Path(paths["image"]))}}
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
