#!/usr/bin/env python3
"""Check one per-cache-line OCI trace against a ThunderX remote FSM."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import dataclass
from itertools import islice
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Sequence, Tuple


IO = Tuple[str, str]


@dataclass(frozen=True)
class Event:
    index: int
    line: int
    time: str
    opcode_name: str
    dmask: str


@dataclass(frozen=True)
class Transition:
    current: str
    input: IO
    outputs: Tuple[IO, ...]
    next: str


@dataclass(frozen=True)
class Config:
    state: str
    pending: Tuple[IO, ...] = ()


@dataclass(frozen=True)
class TraceResult:
    ok: bool
    configs: Tuple[Config, ...]
    index: int
    message: str


OCI_TO_OBS: Mapping[str, Tuple[IO, ...]] = {
    # Remote -> home requests.
    "ECI_CMD_MREQ_RLDD": (("OutReq", "IS_d"),),
    "ECI_CMD_MREQ_RLDI": (("OutReq", "IS_d"),),
    "ECI_CMD_MREQ_RLDX": (("OutReq", "IE_d"),),
    "ECI_CMD_MREQ_RC2D_S": (("OutReq", "SE_d"),),
    # Home -> remote responses.
    "ECI_CMD_MRSP_PSHA": (("InResp", "AS_d"),),
    # Remote -> home victim/eviction responses.
    "ECI_CMD_MRSP_VICS": (("OutResp", "SI"),),
    "ECI_CMD_MRSP_VICD": (("OutResp", "EI"),),
    "ECI_CMD_MRSP_VICC": (("OutResp", "EI"),),
    # Home -> remote forwards.
    "ECI_CMD_MFWD_SINV": (("InReq", "IV"),),
    "ECI_CMD_MFWD_SINV_H": (("InReq", "IV"),),
    "ECI_CMD_MFWD_FLDRS_E": (("InReq", "FS_d"),),
    "ECI_CMD_MFWD_FLDRS_O": (("InReq", "FS_d"),),
    "ECI_CMD_MFWD_FLDRS_EH": (("InReq", "FS_d"),),
    "ECI_CMD_MFWD_FLDRS_OH": (("InReq", "FS_d"),),
}


# OCI names HAKD.N/HAKN as a no-data home ack. The FSM uses more precise ack
# symbols based on the current state and the forward that caused the ack.
HAKD_COMPATIBLE_OBS: Tuple[IO, ...] = tuple(
    ("OutResp", msg) for msg in ("FA", "FA_d", "FAI", "FAS", "IAI", "SI", "EI")
)

HAKI_COMPATIBLE_OBS: Tuple[IO, ...] = (("OutResp", "IAI"), ("OutResp", "FAI"))


class RemoteFsm:
    def __init__(self, initial: str, transitions: Iterable[Transition]) -> None:
        self.initial = initial
        self.by_input: Dict[Tuple[str, IO], List[Transition]] = {}
        self.sw_by_output: Dict[Tuple[str, IO], List[Transition]] = {}
        self.states = {initial}
        for transition in transitions:
            self.states.add(transition.current)
            self.states.add(transition.next)
            self.by_input.setdefault((transition.current, transition.input), []).append(transition)
            if transition.input[0] == "SW" and transition.outputs:
                self.sw_by_output.setdefault((transition.current, transition.outputs[0]), []).append(transition)

    @classmethod
    def load(cls, path: Path) -> "RemoteFsm":
        with path.open() as f:
            raw = json.load(f)
        transitions = []
        for item in raw["fsmTransitions"]:
            transition = Transition(
                current=item["current"],
                input=(item["input"]["port"], item["input"]["msg"]),
                outputs=tuple((out["port"], out["msg"]) for out in item["outputs"]),
                next=item["next"],
            )
            transitions.append(transition)
        return cls(raw["fsmInit"], transitions)

    def advance(self, config: Config, observations: Sequence[IO]) -> Tuple[Config, ...]:
        out: List[Config] = []
        for observation in observations:
            if config.pending:
                if observation == config.pending[0]:
                    out.append(Config(config.state, config.pending[1:]))
                continue

            port, _ = observation
            if port in {"InReq", "InResp"}:
                for transition in self.by_input.get((config.state, observation), ()):
                    out.append(Config(transition.next, transition.outputs))
            elif port in {"OutReq", "OutResp"}:
                for transition in self.sw_by_output.get((config.state, observation), ()):
                    out.append(Config(transition.next, transition.outputs[1:]))
        return tuple(out)

    def expected(self, config: Config) -> Tuple[IO, ...]:
        if config.pending:
            return (config.pending[0],)
        expected = []
        for (state, io), transitions in self.by_input.items():
            if state == config.state and io[0] in {"InReq", "InResp"} and transitions:
                expected.append(io)
        for (state, io), transitions in self.sw_by_output.items():
            if state == config.state and transitions:
                expected.append(io)
        return tuple(sorted(set(expected)))


def observations_for(event: Event) -> Tuple[IO, ...]:
    if event.opcode_name == "ECI_CMD_MRSP_PEMD":
        return (("InResp", "AE"),) if int(event.dmask, 0) == 0 else (("InResp", "AE_d"),)
    if event.opcode_name in {
        "ECI_CMD_MFWD_FLDX_E",
        "ECI_CMD_MFWD_FLDX_O",
        "ECI_CMD_MFWD_FLDX_EH",
        "ECI_CMD_MFWD_FLDX_OH",
    }:
        return (("InReq", "FE"),) if int(event.dmask, 0) == 0 else (("InReq", "FE_d"),)
    if event.opcode_name in {"ECI_CMD_MFWD_FEVX_EH", "ECI_CMD_MFWD_FEVX_OH"}:
        return (("InReq", "FE"),) if int(event.dmask, 0) == 0 else (("InReq", "FE_d"),)
    if event.opcode_name == "ECI_CMD_MRSP_VICDHI":
        return (("OutResp", "FA"),) if int(event.dmask, 0) == 0 else (("OutResp", "FA_d"),)
    if event.opcode_name == "ECI_CMD_MRSP_HAKD":
        return HAKD_COMPATIBLE_OBS
    if event.opcode_name == "ECI_CMD_MRSP_HAKI":
        return HAKI_COMPATIBLE_OBS
    return OCI_TO_OBS.get(event.opcode_name, ())


def read_trace(path: Path, limit: int | None) -> List[Event]:
    events: List[Event] = []
    with path.open(newline="") as f:
        rows = csv.DictReader(f)
        for index, row in enumerate(islice(rows, limit)):
            events.append(
                Event(
                    index=index,
                    line=index + 2,
                    time=row["time"],
                    opcode_name=row["opcode_name"],
                    dmask=row["dmask"],
                )
            )
    return events


def dedup(configs: Iterable[Config], max_configs: int) -> Tuple[Config, ...]:
    seen = []
    used = set()
    for config in configs:
        key = (config.state, config.pending)
        if key in used:
            continue
        used.add(key)
        seen.append(config)
        if len(seen) >= max_configs:
            break
    return tuple(seen)


def run_direct(fsm: RemoteFsm, events: Sequence[Event], max_configs: int) -> TraceResult:
    configs: Tuple[Config, ...] = (Config(fsm.initial),)
    for event in events:
        observations = observations_for(event)
        if not observations:
            return TraceResult(False, configs, event.index, f"unmapped opcode {event.opcode_name}")
        previous_configs = configs
        next_configs: List[Config] = []
        for config in configs:
            next_configs.extend(fsm.advance(config, observations))
        configs = dedup(next_configs, max_configs)
        if not configs:
            return TraceResult(False, previous_configs, event.index, "no matching transition")

    complete = tuple(config for config in configs if not config.pending)
    if complete:
        return TraceResult(True, complete, len(events), "trace accepted")
    return TraceResult(False, configs, len(events), "trace ended with pending FSM output")


@dataclass(frozen=True)
class SearchState:
    config: Config
    buffer: Tuple[int, ...]
    next_index: int


def run_reordered(
    fsm: RemoteFsm,
    events: Sequence[Event],
    window: int,
    max_configs: int,
) -> TraceResult:
    if window <= 1:
        return run_direct(fsm, events, max_configs)

    initial_buffer = tuple(range(min(window, len(events))))
    states = (SearchState(Config(fsm.initial), initial_buffer, len(initial_buffer)),)
    consumed = 0

    while states:
        if all(not state.buffer for state in states):
            configs = dedup((state.config for state in states), max_configs)
            complete = tuple(config for config in configs if not config.pending)
            if complete:
                return TraceResult(True, complete, len(events), "trace accepted with bounded reordering")
            return TraceResult(False, configs, len(events), "trace ended with pending FSM output")

        next_states: List[SearchState] = []
        stalled_events = []
        for state in states:
            if not state.buffer:
                next_states.append(state)
                continue
            advanced_this_state = False
            for pos, event_index in enumerate(state.buffer):
                event = events[event_index]
                observations = observations_for(event)
                if not observations:
                    stalled_events.append(event_index)
                    continue
                for config in fsm.advance(state.config, observations):
                    replacement = list(state.buffer[:pos] + state.buffer[pos + 1 :])
                    next_index = state.next_index
                    if next_index < len(events):
                        replacement.append(next_index)
                        next_index += 1
                    next_states.append(SearchState(config, tuple(replacement), next_index))
                    advanced_this_state = True
            if not advanced_this_state and state.buffer:
                stalled_events.append(state.buffer[0])

        states = dedup_search(next_states, max_configs)
        consumed += 1
        if not states:
            index = min(stalled_events) if stalled_events else consumed
            return TraceResult(False, (), index, f"no matching transition within reorder window {window}")

    return TraceResult(False, (), consumed, "search exhausted")


def dedup_search(states: Iterable[SearchState], max_states: int) -> Tuple[SearchState, ...]:
    seen = []
    used = set()
    for state in states:
        key = (state.config, state.buffer, state.next_index)
        if key in used:
            continue
        used.add(key)
        seen.append(state)
        if len(seen) >= max_states:
            break
    return tuple(seen)


def fmt_io(io: IO) -> str:
    return f"{io[0]} {io[1]}"


def fmt_config(config: Config) -> str:
    pending = ", ".join(fmt_io(io) for io in config.pending) if config.pending else "-"
    return f"state={config.state} pending={pending}"


def print_context(events: Sequence[Event], index: int, width: int) -> None:
    if not events:
        return
    start = max(0, min(index, len(events) - 1) - width)
    end = min(len(events), min(index, len(events) - 1) + width + 1)
    print("context:", file=sys.stderr)
    for event in events[start:end]:
        marker = ">" if event.index == index else " "
        obs = ", ".join(fmt_io(io) for io in observations_for(event)) or "unmapped"
        print(
            f"{marker} #{event.index} line {event.line} time {event.time} "
            f"{event.opcode_name} dmask={event.dmask} -> {obs}",
            file=sys.stderr,
        )


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace_csv", type=Path)
    parser.add_argument(
        "fsm_json",
        type=Path,
        nargs="?",
        default=Path("deps/thx-fsms/specifications/remote_fsm.json"),
    )
    parser.add_argument("--reorder-window", type=int, default=1)
    parser.add_argument(
        "--max-reorder-events",
        type=int,
        default=20000,
        help="refuse bounded global reorder search above this trace length",
    )
    parser.add_argument("--max-configs", type=int, default=4096)
    parser.add_argument("--max-events", type=int)
    parser.add_argument("--context", type=int, default=5)
    args = parser.parse_args(argv)

    fsm = RemoteFsm.load(args.fsm_json)
    events = read_trace(args.trace_csv, args.max_events)
    result = run_direct(fsm, events, args.max_configs)
    if (
        not result.ok
        and args.reorder_window > 1
        and result.index < len(events)
        and len(events) <= args.max_reorder_events
    ):
        result = run_reordered(fsm, events, args.reorder_window, args.max_configs)
    elif (
        not result.ok
        and args.reorder_window > 1
        and result.index < len(events)
        and len(events) > args.max_reorder_events
    ):
        print(
            f"note: not attempting reorder-window {args.reorder_window} on "
            f"{len(events)} events; raise --max-reorder-events or use --max-events",
            file=sys.stderr,
        )

    if result.ok:
        print(f"PASS: {result.message}; consumed {len(events)} events")
        for config in result.configs[:5]:
            print(f"  {fmt_config(config)}")
        if len(result.configs) > 5:
            print(f"  ... {len(result.configs) - 5} more final configs")
        return 0

    print(f"FAIL: {result.message}", file=sys.stderr)
    if result.index < len(events):
        event = events[result.index]
        print(
            f"at event #{event.index}, CSV line {event.line}, time {event.time}: "
            f"{event.opcode_name} dmask={event.dmask}",
            file=sys.stderr,
        )
        print_context(events, result.index, args.context)
    else:
        print(f"at end of trace after {len(events)} events", file=sys.stderr)
        print_context(events, len(events) - 1, args.context)

    if result.configs:
        print("frontier:", file=sys.stderr)
        for config in result.configs[:10]:
            expected = ", ".join(fmt_io(io) for io in fsm.expected(config)) or "-"
            print(f"  {fmt_config(config)}; expected next: {expected}", file=sys.stderr)
        if len(result.configs) > 10:
            print(f"  ... {len(result.configs) - 10} more configs", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
