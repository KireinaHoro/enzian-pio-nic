"""One deterministic, self-contained archive per coherent experiment dataset."""
from contextlib import contextmanager
import gzip
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile

from .import_loaded import digest


INDEX = 'archive-files.json'


def pack(source, destination):
    """Pack a frozen dataset, preserving file bytes and indexing every member."""
    source, destination = Path(source), Path(destination)
    if not destination.name.endswith('.tar.gz'):
        raise ValueError('Experiment archives must end in .tar.gz')
    entries = sorted(source.rglob('*'))
    paths = [p for p in entries if not p.is_dir()]
    if any(p.is_symlink() for p in entries) or any(not p.is_file() for p in paths):
        raise ValueError('Experiment evidence must be regular files')
    if (source / INDEX).exists():
        raise ValueError('Reserved archive index filename')
    hashes = {p.relative_to(source).as_posix(): digest(p) for p in paths}
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Publish only a completed archive; never replace an existing experiment.
    with tempfile.NamedTemporaryFile(dir=destination.parent) as output:
        with gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as archive:
                import io
                for name, sha in hashes.items():
                    path = source / name
                    info = tarfile.TarInfo(name)
                    info.size = path.stat().st_size
                    info.mode = 0o644
                    with path.open('rb') as stream:
                        archive.addfile(info, stream)
                    if digest(path) != sha:
                        raise ValueError('Evidence changed during packing: ' + name)
                data = (json.dumps(hashes, sort_keys=True, indent=2) + '\n').encode()
                info = tarfile.TarInfo(INDEX)
                info.size = len(data)
                info.mode = 0o644
                archive.addfile(info, io.BytesIO(data))

        output.flush()
        os.link(output.name, destination)


@contextmanager
def input_file(source, name):
    """Accept live JSON or validate and unpack a frozen experiment temporarily."""
    source = Path(source).resolve()
    if not source.name.endswith('.tar.gz'):
        yield source
        return
    with tempfile.TemporaryDirectory(prefix='lauberhorn-experiment-') as directory:
        root = Path(directory)
        hashes = {}
        with tarfile.open(source, 'r:gz') as archive:
            for member in archive:
                path = PurePosixPath(member.name)
                if (not member.isfile() or path.is_absolute() or '..' in path.parts
                        or path.as_posix() != member.name or member.name in hashes):
                    raise ValueError('Unsafe or duplicate experiment member: ' + member.name)
                target = root / member.name
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as original, target.open('xb') as output:
                    shutil.copyfileobj(original, output)
                hashes[member.name] = digest(target)
        hashes.pop(INDEX, None)
        if json.loads((root / INDEX).read_text()) != hashes:
            raise ValueError('Experiment archive hash mismatch')
        if not (root / name).is_file():
            raise ValueError('Experiment archive has no ' + name)
        yield root / name


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Frozen experiment directory')
    parser.add_argument('destination', type=Path, help='New .tar.gz archive')
    args = parser.parse_args()
    pack(args.source, args.destination)
