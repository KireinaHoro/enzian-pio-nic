from pathlib import Path
from typing import Optional


def bits(value: int, offset: int, width: int) -> int:
    return (value >> offset) & ((1 << width) - 1)


def bit_range(value: int, hi: int, lo: int) -> int:
    return bits(value, lo, hi - lo + 1)


def parse_hex_int(text: str) -> Optional[int]:
    text = text.strip().lower()
    if text.startswith("0x"):
        text = text[2:]
    if not text:
        return None
    try:
        return int(text, 16)
    except ValueError:
        return None


def fmt_hex_no0x(value: Optional[int]) -> str:
    return "" if value is None else format(int(value), "x")


def platform_root() -> Path:
    return Path(__file__).resolve().parents[3]
