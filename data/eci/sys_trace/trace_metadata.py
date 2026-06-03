import copy
from typing import Any, Dict, Iterable, Mapping


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

MREQ_OPCODE_NAMES = {
    0: "ECI_CMD_MREQ_RLDD", 1: "ECI_CMD_MREQ_RLDI",
    2: "ECI_CMD_MREQ_RLDT", 3: "ECI_CMD_MREQ_RLDY",
    4: "ECI_CMD_MREQ_RLDWB", 5: "ECI_CMD_MREQ_RLDX",
    6: "ECI_CMD_MREQ_RC2D_O", 7: "ECI_CMD_MREQ_RC2D_S",
    8: "ECI_CMD_MREQ_RSTT", 9: "ECI_CMD_MREQ_RSTY",
    10: "ECI_CMD_MREQ_RSTP", 11: "ECI_CMD_MREQ_REOR",
    12: "ECI_CMD_UNALLOCATED", 13: "ECI_CMD_MREQ_RADD",
    14: "ECI_CMD_MREQ_RINC", 15: "ECI_CMD_MREQ_RDEC",
    16: "ECI_CMD_MREQ_RSWP", 17: "ECI_CMD_MREQ_RSET",
    18: "ECI_CMD_MREQ_RCLR", 19: "ECI_CMD_MREQ_RCAS",
    20: "ECI_CMD_MREQ_GINV", 21: "ECI_CMD_MREQ_RCASO",
    22: "ECI_CMD_MREQ_RCASS", 23: "ECI_CMD_MREQ_RSTC",
    24: "ECI_CMD_MREQ_GSYNC", 25: "ECI_CMD_MREQ_RSTCO",
    26: "ECI_CMD_MREQ_RSTCS", 27: "ECI_CMD_MREQ_RSMAX",
    28: "ECI_CMD_MREQ_RSMIN", 29: "ECI_CMD_MREQ_RUMAX",
    30: "ECI_CMD_MREQ_RUMIN", 31: "ECI_CMD_MREQ_IDLE_OPCODE",
}

MRSP_OPCODE_NAMES = {
    0: "ECI_CMD_MRSP_VICD", 1: "ECI_CMD_MRSP_VICC",
    2: "ECI_CMD_MRSP_VICS", 3: "ECI_CMD_MRSP_VICDHI",
    4: "ECI_CMD_MRSP_HAKD", 5: "ECI_CMD_MRSP_HAKN_S",
    6: "ECI_CMD_MRSP_HAKI", 7: "ECI_CMD_MRSP_HAKS",
    8: "ECI_CMD_MRSP_HAKV", 9: "ECI_CMD_MRSP_PSHA",
    10: "ECI_CMD_MRSP_PEMD", 24: "ECI_CMD_MRSP_GSDN",
}

MFWD_OPCODE_NAMES = {
    0: "ECI_CMD_MFWD_FLDRO_E", 1: "ECI_CMD_MFWD_FLDRO_O",
    2: "ECI_CMD_MFWD_FLDRS_E", 3: "ECI_CMD_MFWD_FLDRS_O",
    4: "ECI_CMD_MFWD_FLDRS_EH", 5: "ECI_CMD_MFWD_FLDRS_OH",
    6: "ECI_CMD_MFWD_FLDT_E", 7: "ECI_CMD_MFWD_FLDX_E",
    8: "ECI_CMD_MFWD_FLDX_O", 11: "ECI_CMD_MFWD_FEVX_EH",
    12: "ECI_CMD_MFWD_FEVX_OH", 13: "ECI_CMD_MFWD_SINV",
    14: "ECI_CMD_MFWD_SINV_H",
}


def _indexed_enum(names: Iterable[str]) -> Dict[str, str]:
    return {str(index): name for index, name in enumerate(names)}


def _numeric_enum(values: Mapping[int, str]) -> Dict[str, str]:
    return {str(index): name for index, name in sorted(values.items())}


def enum_value(enum: Mapping[str, str], text: str) -> int:
    for value, name in enum.items():
        if text == name:
            return int(value)
    raise ValueError(f"{text!r} is not present in enum metadata")


def enrich_trace_map(trace_map: Dict[str, Any]) -> Dict[str, Any]:
    enriched = copy.deepcopy(trace_map)
    enriched.pop("sources_by_id", None)

    payload_formats = enriched.setdefault("payload_formats", {})
    dcs_fields = payload_formats.setdefault("dcs_event", {}).setdefault("fields", {})
    dcs_fields.setdefault("request", {}).setdefault("enum", _indexed_enum(DCS_REQUEST_NAMES))
    dcs_fields.setdefault("action", {}).setdefault("enum", _indexed_enum(DCS_ACTION_NAMES))
    dcs_fields.setdefault("state", {}).setdefault("enum", _indexed_enum(DCS_STATE_NAMES))

    eci_format = payload_formats.setdefault("eci", {})
    eci_format.setdefault("opcode_enums", {
        "mreq": _numeric_enum(MREQ_OPCODE_NAMES),
        "mrsp": _numeric_enum(MRSP_OPCODE_NAMES),
        "mfwd": _numeric_enum(MFWD_OPCODE_NAMES),
    })

    return enriched
