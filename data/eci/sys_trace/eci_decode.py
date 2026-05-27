from typing import Any, Dict, Optional

try:
    from .common import bit_range, bits, fmt_hex_no0x
except ImportError:
    from common import bit_range, bits, fmt_hex_no0x


def unalias_cacheline_index(aliased_cli: int) -> int:
    cli = 0
    cli |= bit_range(aliased_cli, 32, 13) << 13
    cli |= (bit_range(aliased_cli, 12, 8) ^ bit_range(aliased_cli, 17, 13)) << 8
    cli |= (bit_range(aliased_cli, 7, 5) ^ bit_range(aliased_cli, 20, 18)) << 5
    cli |= (
        bit_range(aliased_cli, 4, 3)
        ^ bit_range(aliased_cli, 19, 18)
        ^ bit_range(aliased_cli, 17, 16)
        ^ bit_range(aliased_cli, 6, 5)
    ) << 3
    cli |= (
        bit_range(aliased_cli, 2, 0)
        ^ bit_range(aliased_cli, 20, 18)
        ^ bit_range(aliased_cli, 15, 13)
        ^ bit_range(aliased_cli, 7, 5)
    )
    return cli


def unalias_address(aliased_addr: int) -> int:
    aliased_cli = bits(aliased_addr, 7, 33)
    return unalias_cacheline_index(aliased_cli) << 7


def decode_eci_by_opcode(word: int, vc: Optional[int] = None, src: Optional[int] = None) -> Dict[str, Any]:
    res: Dict[str, Any] = {"raw": f"0x{word:016x}"}
    opcode = bit_range(word, 63, 59)
    res["opcode"] = opcode

    mreq_map = {
        0: "ECI_CMD_MREQ_RLDD", 1: "ECI_CMD_MREQ_RLDI",
        2: "ECI_CMD_MREQ_RLDT", 3: "ECI_CMD_MREQ_RLDY",
        4: "ECI_CMD_MREQ_RLDWB", 5: "ECI_CMD_MREQ_RLDX",
        6: "ECI_CMD_MREQ_RC2D_O", 7: "ECI_CMD_MREQ_RC2D_S",
        8: "ECI_CMD_MREQ_RSTT", 9: "ECI_CMD_MREQ_RSTY",
        10: "ECI_CMD_MREQ_RSTP", 24: "ECI_CMD_MREQ_GSYNC",
    }
    mrsp_map = {
        0: "ECI_CMD_MRSP_VICD", 1: "ECI_CMD_MRSP_VICC",
        2: "ECI_CMD_MRSP_VICS", 3: "ECI_CMD_MRSP_VICDHI",
        4: "ECI_CMD_MRSP_HAKD", 5: "ECI_CMD_MRSP_HAKN_S",
        6: "ECI_CMD_MRSP_HAKI", 7: "ECI_CMD_MRSP_HAKS",
        8: "ECI_CMD_MRSP_HAKV", 9: "ECI_CMD_MRSP_PSHA",
        10: "ECI_CMD_MRSP_PEMD", 24: "ECI_CMD_MRSP_GSDN",
    }
    mfwd_map = {
        0: "ECI_CMD_MFWD_FLDRO_E", 1: "ECI_CMD_MFWD_FLDRO_O",
        2: "ECI_CMD_MFWD_FLDRS_E", 3: "ECI_CMD_MFWD_FLDRS_O",
        4: "ECI_CMD_MFWD_FLDRS_EH", 5: "ECI_CMD_MFWD_FLDRS_OH",
        6: "ECI_CMD_MFWD_FLDT_E", 7: "ECI_CMD_MFWD_FLDX_E",
        8: "ECI_CMD_MFWD_FLDX_O", 11: "ECI_CMD_MFWD_FEVX_EH",
        12: "ECI_CMD_MFWD_FEVX_OH", 13: "ECI_CMD_MFWD_SINV",
        14: "ECI_CMD_MFWD_SINV_H",
    }

    if src is None:
        if opcode in mrsp_map:
            cls = "mrsp"
        elif opcode in mreq_map:
            cls = "mreq"
        elif opcode in mfwd_map:
            cls = "mfwd"
        else:
            cls = "unknown"
    elif src == 0:
        cls = "mreq"
    elif 1 <= src <= 4:
        cls = "mrsp"
    elif src == 5:
        cls = "mfwd"
    else:
        cls = "unknown"

    if cls == "mreq":
        name = mreq_map.get(opcode, f"MREQ_OP_{opcode}")
    elif cls == "mrsp":
        name = mrsp_map.get(opcode, f"MRSP_OP_{opcode}")
    elif cls == "mfwd":
        name = mfwd_map.get(opcode, f"MFWD_OP_{opcode}")
    else:
        name = f"OP_{opcode}"
    res["message"] = name

    if cls == "mreq" and opcode == 24:
        res.update({
            "xb3": bit_range(word, 58, 55),
            "rreq_id": bit_range(word, 54, 50),
            "xb40": bit_range(word, 49, 10),
            "rtad": bit_range(word, 9, 7),
            "xb1": bit_range(word, 6, 6),
            "ppvid": bit_range(word, 5, 0),
        })
        return res

    if cls == "mreq":
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb4": bit_range(word, 58, 55),
            "rreq_id": bit_range(word, 54, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb3": bit_range(word, 44, 42),
            "xb2": bit_range(word, 41, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and opcode in (0, 1, 2):
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb10": bit_range(word, 58, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb5": bit_range(word, 44, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and 3 <= opcode <= 8:
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb3": bit_range(word, 58, 56),
            "hreq_id": bit_range(word, 55, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb5": bit_range(word, 44, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    if cls == "mrsp" and opcode == 24:
        res.update({
            "xb12": bit_range(word, 58, 46),
            "ns": bit_range(word, 45, 45),
            "xb35": bit_range(word, 44, 10),
            "rtad": bit_range(word, 9, 7),
            "xb1": bit_range(word, 6, 6),
            "ppvid": bit_range(word, 5, 0),
        })
        return res

    if cls == "mrsp" and opcode in (9, 10):
        aliased_cli = bit_range(word, 39, 7)
        aliased_addr = (aliased_cli << 7) & ((1 << 40) - 1)
        res.update({
            "nxm": bit_range(word, 58, 58),
            "xb3": bit_range(word, 57, 55),
            "rreq_id": bit_range(word, 54, 50),
            "dmask": bit_range(word, 49, 46),
            "xb1": bit_range(word, 45, 45),
            "dirty": bit_range(word, 44, 41),
            "xb1_2": bit_range(word, 40, 40),
            "aliased_addr": fmt_hex_no0x(aliased_addr),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased_addr)),
            "fillo": bit_range(word, 6, 5),
            "xb5": bit_range(word, 4, 0),
        })
        return res

    if cls == "mfwd":
        aliased = bit_range(word, 39, 0)
        res.update({
            "xb3": bit_range(word, 58, 56),
            "hreq_id": bit_range(word, 55, 50),
            "dmask": bit_range(word, 49, 46),
            "ns": bit_range(word, 45, 45),
            "xb1": bit_range(word, 44, 44),
            "rnode": bit_range(word, 43, 42),
            "xb2": bit_range(word, 41, 40),
            "aliased_addr": fmt_hex_no0x(aliased),
            "unaliased_addr": fmt_hex_no0x(unalias_address(aliased)),
        })
        return res

    res.update({
        "sz": bit_range(word, 58, 56),
        "xb1": bit_range(word, 55, 55),
        "rreq_id": bit_range(word, 54, 50),
        "dmask": bit_range(word, 49, 46),
        "rest_cmd": bit_range(word, 35, 0),
    })
    return res
