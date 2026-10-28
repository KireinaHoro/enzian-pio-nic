"""Locate external measurement evidence without making it a Nix build input."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

PLATFORM = Path(__file__).resolve().parents[2]


def checkout_root():
    common = subprocess.check_output(
        ['git', '-C', str(PLATFORM), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
        text=True).strip()
    return Path(common).parent


def data_root():
    if value := os.environ.get('LAUBERHORN_DATA_ROOT'):
        return Path(value).expanduser().resolve()
    return checkout_root().parent / 'data'


def paper_directory():
    return checkout_root().parent / 'paper/plots'


def resolve_input(source):
    source = Path(source)
    if source.parts[:2] == ('..', 'data'):
        return data_root().joinpath(*source.parts[2:])
    return source if source.is_absolute() else PLATFORM / source


def source_record(source):
    source = resolve_input(source).resolve()
    result = {'dataset': os.path.relpath(source, PLATFORM)}
    try:
        relative = source.relative_to(data_root())
    except ValueError:
        return result  # Live acquisition under out/ retains platform provenance.
    result['data_repository'] = 'https://gitlab.inf.ethz.ch/project-openenzian/applications/lauberhorn/data'
    result['data_git'] = subprocess.check_output(
        ['git', '-C', str(data_root()), 'rev-parse', 'HEAD'], text=True).strip()
    result['data_path'] = relative.as_posix()
    result['data_dirty'] = bool(subprocess.check_output(
        ['git', '-C', str(data_root()), 'status', '--porcelain', '--', str(relative)], text=True))
    with source.open('rb') as stream:
        result['sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
    return result
