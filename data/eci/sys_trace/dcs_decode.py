from typing import Any, Dict, List

try:
    from .common import fmt_hex_no0x, parse_hex_int
except ImportError:
    from common import fmt_hex_no0x, parse_hex_int


DCS_REQUEST_NAMES = [
    "UNKNOWN_REQ", "A11", "A21", "A22", "A31", "A31d", "A32", "A32d",
    "F21", "F31", "F32", "ICI", "LC", "LCI", "LR", "LW", "R12", "R13",
    "R23", "RDDA", "RR", "RW", "UL", "V21", "V31", "V31d", "V32", "V32d",
    "WDDA",
]

DCS_STATE_NAMES = [
    "s1__1", "s1__1_A11", "s1__1_A22", "s1__1_V21", "s1__1_V32",
    "s1__1_WDDA", "s1__1pRRA", "s1__1pRWA", "s1__2", "s1__2_A22",
    "s1__2_WDDA", "s1__2pRA2", "s1__3", "s1__3pRA3", "s1pCI__1_A21",
    "s1pCI__1_A31d", "s1pCI__1_V31", "s1pCI__1_V32",
    "s1pCI__1_V32_A11", "s1pCI__1_WDDA", "s1pCI__1_WDDA_A11",
    "s1pCI__1_WDDA_A21", "s1pCI__1_WDDA_V21", "s1pC__1_A32",
    "s1pC__1_V31", "s1pC__1_V32", "s1pC__1_WDDA", "s1pC__1_WDDA_A11",
    "s1pC__1_WDDA_A22", "s1pC__1_WDDA_V21", "s1pC__2_A32d",
    "s1pC__2_V32", "s1pC__2_WDDA", "s1pC__2_WDDA_A22", "s1pICI__1_A21",
    "s1pICI__1_A31d", "s1pICI__1_V31", "s1pICI__1_V32",
    "s1pICI__1_V32_A11", "s1pICI__1_WDDA", "s1pICI__1_WDDA_A11",
    "s1pICI__1_WDDA_A21", "s1pICI__1_WDDA_V21", "s1pLRA__1",
    "s1pLRA__1_A11", "s1pLRA__1_A22", "s1pLRA__1_V21", "s1pLRA__2",
    "s1pLRA__2_A22", "s1pLWA__1", "s1pLWA__1_A11", "s1pLWA__1_V21",
    "s1pR__1_A32", "s1pR__1_V31", "s1pR__1_V32", "s1pR__1_WDDA",
    "s1pR__1_WDDA_A11", "s1pR__1_WDDA_A22", "s1pR__1_WDDA_V21",
    "s1pR__2_A32d", "s1pR__2_V32", "s1pR__2_WDDA",
    "s1pR__2_WDDA_A22", "s1pUL__1", "s1pUL__1_A11", "s1pUL__1_A22",
    "s1pUL__1_V21", "s1pUL__2", "s1pUL__2_A22", "s1pW__1_A21",
    "s1pW__1_A31d", "s1pW__1_V31", "s1pW__1_V32", "s1pW__1_V32_A11",
    "s1pW__1_WDDA", "s1pW__1_WDDA_A11", "s1pW__1_WDDA_A21",
    "s1pW__1_WDDA_V21",
]

DCS_ACTION_NAMES = [
    "NO_ACTION", "NOT_ALLOWED", "RDD", "SEND_F21", "SEND_F31", "SEND_F32",
    "SEND_LCA", "SEND_LCIA", "SEND_LRA", "SEND_LWA", "SEND_RA2",
    "SEND_RA3", "SEND_RRA", "SEND_RWA", "STALL", "WDD",
]


def indexed_name(names: List[str], value: Any, prefix: str) -> str:
    try:
        index = int(value)
    except (TypeError, ValueError):
        return ""
    return names[index] if 0 <= index < len(names) else f"{prefix}_{index}"


def decode_dcs_event(row: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "dcs_trace.event.req": row.get("request", ""),
        "dcs_trace.event.cli": fmt_hex_no0x(parse_hex_int(str(row.get("cli", "")))),
        "dcs_trace.event.action": row.get("action", ""),
        "dcs_trace.event.state": row.get("state", ""),
        "request_name": indexed_name(DCS_REQUEST_NAMES, row.get("request"), "REQ"),
        "state_name": indexed_name(DCS_STATE_NAMES, row.get("state"), "STATE"),
        "action_name": indexed_name(DCS_ACTION_NAMES, row.get("action"), "ACTION"),
    }
