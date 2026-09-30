#!/usr/bin/env python3
"""Exact color-capacity search within an explicitly bounded layout family.

Addresses are slice-relative; acceptance of this arithmetic is not evidence that
production routing preserves a new domain prefix. No third-party dependencies.
"""
import argparse
import itertools
import json
from dataclasses import dataclass


def alias(cli):
    return ((cli & ~8191) | (((cli >> 8 ^ cli >> 13) & 31) << 8)
            | (((cli >> 5 ^ cli >> 18) & 7) << 5)
            | (((cli >> 3 ^ cli >> 16 ^ cli >> 5) & 3) << 3)
            | ((cli ^ cli >> 13 ^ cli >> 5) & 7))


def unalias(cli):
    high = cli & ~8191
    mid = ((cli >> 5 ^ cli >> 18) & 7) << 5
    return (high | (((cli >> 8 ^ cli >> 13) & 31) << 8) | mid
            | (((cli >> 3 ^ cli >> 18 ^ cli >> 16 ^ cli >> 5) & 3) << 3)
            | ((cli ^ cli >> 18 ^ cli >> 13 ^ cli >> 5) & 7))


def color(pa, bits):
    return alias(pa >> 7) & ((1 << bits) - 1)


@dataclass(frozen=True)
class Layout:
    rx: int = 6  # cache-line offsets
    tx: int = 38
    overflow: int = 12

    def lines(self, direction):
        start = self.rx if direction == 0 else self.tx
        return range(start, start + 2 + self.overflow)

    def signature(self, base, direction, bits):
        c = [color(base + 128 * i, bits) for i in self.lines(direction)]
        return tuple(c[:2]), frozenset(c[2:])


def compatible(signatures):
    controls = [c for ctrl, _ in signatures for c in ctrl]
    overflow = set().union(*(ov for _, ov in signatures))
    return len(set(controls)) == len(controls) and not set(controls) & overflow


def encoding(width, stride, bits):
    """Linear color map: canonical quotient representatives and kernel basis.

    Each physical block is uniquely rep[color] XOR sum(kernel basis subset).
    This uses all color-neutral encodings, including compensated high bits.
    """
    reps = {0: 0}
    kernel = []
    for i in range(stride, width):
        p = 1 << i
        c = color(p, bits)
        if c in reps:
            kernel.append(p ^ reps[c])
        else:
            reps.update({m ^ c: a ^ p for m, a in list(reps.items())})
    return reps, kernel


def prefixes(basis):
    values = [0]
    for b in basis:
        values += [x ^ b for x in values]
    return values


def frontier(layout, bits=6, width=38, stride=17, reserved=()):
    if not (1 <= bits <= 7 and 7 <= stride <= width <= 38):
        raise ValueError('require 1..7 color bits and 7 <= stride <= width <= 38')
    rx, tx = set(layout.lines(0)), set(layout.lines(1))
    if layout.overflow < 0 or min(rx | tx) < 0 or rx & tx or max(rx | tx) * 128 >= 1 << stride:
        raise ValueError('directions must occupy disjoint, nonnegative lines within a block')
    if any(not 0 <= lo < hi <= 1 << width for lo, hi in reserved):
        raise ValueError('reserved intervals must lie in the aperture')
    reps, basis = encoding(width, stride, bits)
    # Reserve entire blocks: conservative page/alias mapping contract.
    ps = prefixes(basis) if reserved else None
    valid = {}
    for m, base in reps.items():
        if ps is None:
            valid[m] = None
        else:
            valid[m] = sum(1 << d for d, p in enumerate(ps)
                           if all((base ^ p) + (1 << stride) <= lo or (base ^ p) >= hi
                                  for lo, hi in reserved))
    sig = {m: [layout.signature(base, d, bits) for d in (0, 1)] for m, base in reps.items()}
    workers = [m for m in reps if all(compatible([s]) for s in sig[m])]
    edges = {m: {n for n in workers if n != m and all(compatible([a, b])
              for a in sig[m] for b in sig[n])} for m in workers}
    best = {}
    all_domains = (1 << len(basis))

    def visit(chosen, candidates, domains, bypass):
        count = all_domains if domains is None else domains.bit_count()
        k = len(chosen)
        if count and (k not in best or count > best[k]['domains']):
            best[k] = dict(workers=k, domains=count, bypass_mask=bypass,
                           worker_masks=chosen[:], worker_bases=[reps[m] for m in chosen],
                           bypass_base=reps[bypass],
                           domain_indices=None if domains is None else
                           [i for i in range(all_domains) if domains >> i & 1])
        if count == 0:
            return  # intersections cannot regain excluded domain prefixes
        # A proper greedy coloring of the compatibility graph bounds its clique
        # size. This is an upper bound, not a heuristic maximum-clique claim.
        buckets = []
        for m in sorted(candidates, key=lambda v: -len(edges[v])):
            for bucket in buckets:
                if not edges[m].intersection(bucket):
                    bucket.add(m)
                    break
            else:
                buckets.append({m})
        if all(j in best and best[j]['domains'] >= count
               for j in range(k + 1, k + len(buckets) + 1)):
            return
        for i, m in enumerate(candidates):
            ds = domains if domains is None else domains & valid[m]
            visit(chosen + [m], [n for n in candidates[i+1:] if n in edges[m]], ds, bypass)

    for b in reps:
        if not compatible(sig[b]) or (valid[b] is not None and not valid[b] & 1):
            continue  # bypass is at domain-prefix index zero
        candidates = [m for m in workers if all(compatible(sig[b] + [s]) for s in sig[m])]
        visit([], sorted(candidates), None if ps is None else (1 << all_domains) - 1, b)
    return dict(layout=layout.__dict__, color_bits=bits, address_bits=width,
                stride_bits=stride, reserved=list(reserved), kernel_basis=basis,
                family='canonical quotient representatives; shared XOR kernel prefixes; whole-block reservations',
                status='exact within this family; color compatibility only',
                frontier=[best[k] for k in sorted(best)])


def verify(result):
    """Independent witness enumeration, including all mappings and active pairs."""
    layout = Layout(**result['layout'])
    bits, width, stride = (result[k] for k in ('color_bits', 'address_bits', 'stride_bits'))
    ps = prefixes(result['kernel_basis'])
    for row in result['frontier']:
        ids = row['domain_indices'] if row['domain_indices'] is not None else range(len(ps))
        assert len(ids) == row['domains']
        bases = row['worker_bases']
        bypass = [layout.signature(row['bypass_base'], d, bits) for d in (0, 1)]
        assert compatible(bypass)
        assert all(row['bypass_base'] + (1 << stride) <= lo or row['bypass_base'] >= hi
                   for lo, hi in result['reserved'])
        seen = {row['bypass_base']}
        signatures = []
        for base in bases:
            ref = [layout.signature(base, d, bits) for d in (0, 1)]
            assert all(compatible(bypass + [s]) for s in ref)
            for old in signatures:
                assert all(compatible([a, b]) for a in ref for b in old)
            signatures.append(ref)
            for d in ids:
                addr = base ^ ps[d]
                assert addr not in seen and addr % (1 << stride) == 0
                seen.add(addr)
                assert 0 <= addr < 1 << width
                assert all(addr + (1 << stride) <= lo or addr >= hi for lo, hi in result['reserved'])
                for direction in (0, 1):
                    assert layout.signature(addr, direction, bits) == ref[direction]
                    for line in layout.lines(direction):
                        pa = addr + 128 * line
                        assert pa < 1 << width
                        assert unalias(alias(pa >> 7)) << 7 == pa
    return True


def configuration(result, workers, domains):
    """Emit the replacement decoder contract, never thread-router registers."""
    row = next((r for r in result['frontier'] if r['workers'] == workers), None)
    if row is None or domains < 1 or domains > row['domains']:
        raise ValueError('requested worker/domain combination is outside the color frontier')
    layout = Layout(**result['layout'])
    indices = row['domain_indices']
    return dict(schema='lauberhorn.fixed-worker-domain-map.v1',
                status='candidate; color-checked; hardware progress/refinement not established',
                address_space='DCS slice-relative byte address (at most 38 bits); external aperture base is not encoded',
                usable_address_bits=result['address_bits'], color_bits=result['color_bits'],
                block_stride_bytes=1 << result['stride_bits'], cache_line_bytes=128,
                worker_count=workers, domain_count=domains,
                domain_encoding=dict(operation='XOR selected kernel vectors with worker base',
                                     kernel_basis_bytes=result['kernel_basis'],
                                     null_prefix_indices='consecutive indices 0 through domain_count-1',
                                     prefix_indices=None if indices is None else indices[:domains]),
                workers=[dict(worker=w, base_bytes=base) for w, base in enumerate(row['worker_bases'])],
                bypass=dict(base_bytes=row['bypass_base'], full_duplex=True, domain_qualified=False),
                directions={name: dict(control_offsets_bytes=[128*i for i in list(layout.lines(d))[:2]],
                                       overflow_offsets_bytes=[128*i for i in list(layout.lines(d))[2:]])
                            for d, name in enumerate(('rx', 'tx'))},
                decode=dict(algorithm='split block offset; decode block color to worker; invert XOR kernel for domain',
                            reject='unknown worker color, unallocated prefix index, or domain != installed owner',
                            worker_color_masks=row['worker_masks']),
                coherence=dict(retain='full original domain-qualified address through read, invalidate, ack and unlock',
                               retirement='drain old transactions and retire cache state before prefix reuse'),
                reserved_regions=result['reserved'])


def encode(config, worker, domain, offset=0):
    if not 0 <= worker < config['worker_count']:
        raise ValueError('worker outside configuration')
    if not 0 <= domain < config['domain_count'] or not 0 <= offset < config['block_stride_bytes']:
        raise ValueError('domain or offset outside configuration')
    base = config['workers'][worker]['base_bytes']
    indices = config['domain_encoding']['prefix_indices']
    index = domain if indices is None else indices[domain]
    for bit, vector in enumerate(config['domain_encoding']['kernel_basis_bytes']):
        if index >> bit & 1:
            base ^= vector
    return base + offset


def decode(config, address):
    if not 0 <= address < 1 << config['usable_address_bits']:
        raise ValueError('address exceeds preserved aperture')
    stride = config['block_stride_bytes']
    block, offset = address & -stride, address % stride
    mask = color(block, config['color_bits'])
    try:
        worker = config['decode']['worker_color_masks'].index(mask)
    except ValueError:
        raise ValueError('unassigned worker color') from None
    residual = block ^ config['workers'][worker]['base_bytes']
    index = 0
    basis = config['domain_encoding']['kernel_basis_bytes']
    for bit in reversed(range(len(basis))):
        if residual & (1 << (basis[bit].bit_length()-1)):
            residual ^= basis[bit]
            index |= 1 << bit
    try:
        indices = config['domain_encoding']['prefix_indices']
        domain = index if indices is None else indices.index(index)
        if domain >= config['domain_count']:
            raise ValueError('unallocated domain prefix')
    except ValueError:
        raise ValueError('unallocated domain prefix') from None
    if residual:
        raise ValueError('invalid domain encoding')
    return worker, domain, offset


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rx', type=int, nargs='+', default=[6])
    p.add_argument('--tx', type=int, nargs='+', default=[38])
    p.add_argument('--overflow', type=int, default=12)
    p.add_argument('--stride-bits', type=int, nargs='+', default=[17])
    p.add_argument('--color-bits', type=int, default=6)
    p.add_argument('--address-bits', type=int, default=38)
    p.add_argument('--reserved', type=lambda s: tuple(int(x, 0) for x in s.split(':')), action='append', default=[])
    p.add_argument('--verify', action='store_true')
    p.add_argument('--workers', type=int, help='emit a new-design decoder configuration for this worker count')
    p.add_argument('--domains', type=int, default=16)
    a = p.parse_args()
    results = []
    for rx, tx, stride in itertools.product(a.rx, a.tx, a.stride_bits):
        r = frontier(Layout(rx, tx, a.overflow), a.color_bits, a.address_bits, stride, a.reserved)
        if a.verify:
            verify(r)
        if a.workers is not None:
            r['configuration'] = configuration(r, a.workers, a.domains)
        else:
            r['configurations'] = [configuration(r, row['workers'], row['domains'])
                                   for row in r['frontier'] if row['workers'] > 0]
        results.append(r)
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
