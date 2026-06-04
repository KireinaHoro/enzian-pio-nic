from dataclasses import dataclass
from typing import List, Optional, Sequence, Tuple, TypeVar


T = TypeVar("T")


@dataclass(frozen=True)
class SampleWindow:
    start: Optional[int] = None
    stop: Optional[int] = None


def last_samples_window(count: int) -> SampleWindow:
    if count < 0:
        raise ValueError("sample count must be non-negative")
    if count == 0:
        return SampleWindow(0, 0)
    return SampleWindow(-count, None)


def normalize_sample_window(length: int, window: Optional[SampleWindow]) -> Tuple[int, int]:
    if window is None:
        return 0, length

    start = 0 if window.start is None else window.start
    stop = length if window.stop is None else window.stop

    if start < 0:
        start += length
    if stop < 0:
        stop += length

    start = min(max(start, 0), length)
    stop = min(max(stop, 0), length)
    if stop < start:
        stop = start
    return start, stop


def apply_sample_window(items: Sequence[T], window: Optional[SampleWindow]) -> List[T]:
    start, stop = normalize_sample_window(len(items), window)
    return list(items[start:stop])


def parse_sample_window(value: Optional[str]) -> Optional[SampleWindow]:
    if value is None:
        return None

    spec = value.strip()
    if not spec:
        raise ValueError("empty sample window")

    if spec.startswith("[") or spec.endswith(")"):
        if not (spec.startswith("[") and spec.endswith(")")):
            raise ValueError("sample range must use [start,stop) syntax")
        body = spec[1:-1]
        parts = body.split(",")
        if len(parts) != 2:
            raise ValueError("sample range must contain one comma")
        return SampleWindow(_parse_optional_int(parts[0]), _parse_optional_int(parts[1]))

    if ":" in spec:
        parts = spec.split(":")
        if len(parts) != 2:
            raise ValueError("sample range must contain one colon")
        return SampleWindow(_parse_optional_int(parts[0]), _parse_optional_int(parts[1]))

    count = int(spec, 0)
    return last_samples_window(count)


def _parse_optional_int(value: str) -> Optional[int]:
    text = value.strip()
    if text == "":
        return None
    return int(text, 0)
