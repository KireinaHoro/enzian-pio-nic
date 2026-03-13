#!/usr/bin/env python3

"""parse_eci_trace.py

Reads a CSV trace (e.g. odd-app.csv), finds the second row where the dump signal is 1,
and parses the following N (default 512) samples' `traceOut_event_data` field as
ECI 64-bit words according to the bundles in EciCmdDefs.scala.

Output: CSV with decoded fields (opcode, numeric message id, address when present,
and all extracted fields) and timestamp.
"""

import argparse
import csv
import sys
from pathlib import Path
from typing import Dict, Any, List, Optional
import re


def bits(value: int, hi: int, lo: int) -> int:
	return (value >> lo) & ((1 << (hi - lo + 1)) - 1)

def unalias_cacheline_index(aliased_cli: int) -> int:
	# Implements eci_unalias_cache_line_index from eci_cmd_defs.sv
	def g(x, hi, lo):
		return (x >> lo) & ((1 << (hi - lo + 1)) - 1)

	cli = 0
	# cli[32:13] = aliased_cli[32:13]
	cli |= (g(aliased_cli, 32, 13) & ((1 << (32 - 13 + 1)) - 1)) << 13

	part = g(aliased_cli, 12, 8) ^ g(aliased_cli, 17, 13)
	cli |= (part & 0x1F) << 8

	part = g(aliased_cli, 7, 5) ^ g(aliased_cli, 20, 18)
	cli |= (part & 0x7) << 5

	part = g(aliased_cli, 4, 3) ^ g(aliased_cli, 19, 18) ^ g(aliased_cli, 17, 16) ^ g(aliased_cli, 6, 5)
	cli |= (part & 0x3) << 3

	part = g(aliased_cli, 2, 0) ^ g(aliased_cli, 20, 18) ^ g(aliased_cli, 15, 13) ^ g(aliased_cli, 7, 5)
	cli |= (part & 0x7)

	return cli


def unalias_address(aliased_addr: int) -> int:
	# aliased_addr is 40-bit value; take bits 39:7 as aliased_cli
	aliased_cli = (aliased_addr >> 7) & ((1 << 33) - 1)
	cli = unalias_cacheline_index(aliased_cli)
	# append seven zero bits as in Scala: ## U(0,7 bits)
	return (cli << 7)


def fmt_hex0x(v: int, bits: int = 40) -> str:
	hex_digits = (bits + 3) // 4
	return f"0x{v:0{hex_digits}x}"


def fmt_hex_no0x(v: Optional[int]) -> str:
	"""Return plain hex digits (no 0x), no leading zeros. Return empty string for None."""
	if v is None:
		return ""
	try:
		iv = int(v)
	except Exception:
		return ""
	return format(iv, 'x')


def decode_by_opcode(word: int, vc: Optional[int] = None, src: Optional[int] = None) -> Dict[str, Any]:
	"""Decode a 64-bit ECI word into fields based on opcode and (optionally) vc.

	Opcode position per `eci_cmd_defs.sv` is bits 63:59 (MSB).
	"""
	res: Dict[str, Any] = {"raw": f"0x{word:016x}"}
	opcode = bits(word, 63, 59)
	res["opcode"] = opcode

	# Use `src` (traceOut_src) to decide which class of message this is.
	# According to user: src==0 => MREQ, src in 1..4 => MRSP, src==5 => MFWD
	mreq_map = {
		0: "ECI_CMD_MREQ_RLDD",
		1: "ECI_CMD_MREQ_RLDI",
		2: "ECI_CMD_MREQ_RLDT",
		3: "ECI_CMD_MREQ_RLDY",
		4: "ECI_CMD_MREQ_RLDWB",
		5: "ECI_CMD_MREQ_RLDX",
		6: "ECI_CMD_MREQ_RC2D_O",
		7: "ECI_CMD_MREQ_RC2D_S",
		8: "ECI_CMD_MREQ_RSTT",
		9: "ECI_CMD_MREQ_RSTY",
		10: "ECI_CMD_MREQ_RSTP",
		24: "ECI_CMD_MREQ_GSYNC",
	}

	mrsp_map = {
		0: "ECI_CMD_MRSP_VICD",
		1: "ECI_CMD_MRSP_VICC",
		2: "ECI_CMD_MRSP_VICS",
		3: "ECI_CMD_MRSP_VICDHI",
		4: "ECI_CMD_MRSP_HAKD",
		5: "ECI_CMD_MRSP_HAKN_S",
		6: "ECI_CMD_MRSP_HAKI",
		7: "ECI_CMD_MRSP_HAKS",
		8: "ECI_CMD_MRSP_HAKV",
		9: "ECI_CMD_MRSP_PSHA",
		10: "ECI_CMD_MRSP_PEMD",
		24: "ECI_CMD_MRSP_GSDN",
	}

	mfwd_map = {
		0: "ECI_CMD_MFWD_FLDRO_E",
		1: "ECI_CMD_MFWD_FLDRO_O",
		2: "ECI_CMD_MFWD_FLDRS_E",
		3: "ECI_CMD_MFWD_FLDRS_O",
		4: "ECI_CMD_MFWD_FLDRS_EH",
		5: "ECI_CMD_MFWD_FLDRS_OH",
		6: "ECI_CMD_MFWD_FLDT_E",
		7: "ECI_CMD_MFWD_FLDX_E",
		8: "ECI_CMD_MFWD_FLDX_O",
		11: "ECI_CMD_MFWD_FEVX_EH",
		12: "ECI_CMD_MFWD_FEVX_OH",
		13: "ECI_CMD_MFWD_SINV",
		14: "ECI_CMD_MFWD_SINV_H",
	}

	# Determine class
	cls = None
	if src is None:
		# unknown source; fall back to MRSP if opcode matches MRSP's known range, else heuristic
		if opcode in mrsp_map:
			cls = 'mrsp'
		elif opcode in mreq_map:
			cls = 'mreq'
		elif opcode in mfwd_map:
			cls = 'mfwd'
		else:
			cls = 'unknown'
	else:
		if src == 0:
			cls = 'mreq'
		elif 1 <= src <= 4:
			cls = 'mrsp'
		elif src == 5:
			cls = 'mfwd'
		else:
			cls = 'unknown'

	if cls == 'mreq':
		name = mreq_map.get(opcode, f"MREQ_OP_{opcode}")
	elif cls == 'mrsp':
		name = mrsp_map.get(opcode, f"MRSP_OP_{opcode}")
	elif cls == 'mfwd':
		name = mfwd_map.get(opcode, f"MFWD_OP_{opcode}")
	else:
		name = f"OP_{opcode}"
	res["message"] = name

	# Helper to decode bit ranges per SystemVerilog typedefs
	# mreq 0..10 layout (eci_vc_cat_mreq_0to10_t)
	if cls == 'mreq':
		aliased = bits(word, 39, 0)
		unaliased = unalias_address(aliased)
		res.update({
			"xb4": bits(word, 58, 55),
			"rreq_id": bits(word, 54, 50),
			"dmask": bits(word, 49, 46),
			"ns": bits(word, 45, 45),
			"xb3": bits(word, 44, 42),
			"xb2": bits(word, 41, 40),
			"aliased_addr": fmt_hex_no0x(aliased),
			"unaliased_addr": fmt_hex_no0x(unaliased),
		})
		return res

	# mreq 24 (gsync)
	if name == "ECI_CMD_MREQ_GSYNC":
		res.update({
			"xb3": bits(word, 58, 55),
			"rreq_id": bits(word, 54, 50),
			"xb40": bits(word, 49, 10),
			"rtad": bits(word, 9, 7),
			"xb1": bits(word, 6, 6),
			"ppvid": bits(word, 5, 0),
		})
		return res

	# mrsp 0..2
	if cls == 'mrsp' and opcode in (0, 1, 2):
		aliased = bits(word, 39, 0)
		unaliased = unalias_address(aliased)
		res.update({
			"xb10": bits(word, 58, 50),
			"dmask": bits(word, 49, 46),
			"ns": bits(word, 45, 45),
			"xb5": bits(word, 44, 40),
			"aliased_addr": fmt_hex_no0x(aliased),
			"unaliased_addr": fmt_hex_no0x(unaliased),
		})
		return res

	# mrsp 3..8
	if cls == 'mrsp' and 3 <= opcode <= 8:
		aliased = bits(word, 39, 0)
		unaliased = unalias_address(aliased)
		res.update({
			"xb3": bits(word, 58, 56),
			"hreq_id": bits(word, 55, 50),
			"dmask": bits(word, 49, 46),
			"ns": bits(word, 45, 45),
			"xb5": bits(word, 44, 40),
			"aliased_addr": fmt_hex_no0x(aliased),
			"unaliased_addr": fmt_hex_no0x(unaliased),
		})
		return res

	# mrsp 24 (gsdn)
	if cls == 'mrsp' and opcode == 24:
		res.update({
			"xb12": bits(word, 58, 46),
			"ns": bits(word, 45, 45),
			"xb35": bits(word, 44, 10),
			"rtad": bits(word, 9, 7),
			"xb1": bits(word, 6, 6),
			"ppvid": bits(word, 5, 0),
		})
		return res

	# mrsp 9..10 (pemd/psha_new)
	# For these messages the cache_line_index occupies bits 39:7 (not a full EciAddress)
	# Reconstruct the aliased address from the cache-line index and produce aliased/unaliased addresses.
	if cls == 'mrsp' and opcode in (9, 10):
		aliased_cli = bits(word, 39, 7)
		# Reconstruct 40-bit aliased address: cli << 7 (lower 7 bits are the byte offset)
		aliased_addr = (aliased_cli << 7) & ((1 << 40) - 1)
		unaliased_addr = unalias_address(aliased_addr)
		res.update({
			"nxm": bits(word, 58, 58),
			"xb3": bits(word, 57, 55),
			"rreq_id": bits(word, 54, 50),
			"dmask": bits(word, 49, 46),
			"xb1": bits(word, 45, 45),
			"dirty": bits(word, 44, 41),
			"xb1_2": bits(word, 40, 40),
			"aliased_addr": fmt_hex_no0x(aliased_addr),
			"unaliased_addr": fmt_hex_no0x(unaliased_addr),
			"fillo": bits(word, 6, 5),
			"xb5": bits(word, 4, 0),
		})
		return res

	# mfwd (0..15)
	if cls == 'mfwd':
		aliased = bits(word, 39, 0)
		unaliased = unalias_address(aliased)
		res.update({
			"xb3": bits(word, 58, 56),
			"hreq_id": bits(word, 55, 50),
			"dmask": bits(word, 49, 46),
			"ns": bits(word, 45, 45),
			"xb1": bits(word, 44, 44),
			"rnode": bits(word, 43, 42),
			"xb2": bits(word, 41, 40),
			"aliased_addr": fmt_hex_no0x(aliased),
			"unaliased_addr": fmt_hex_no0x(unaliased),
		})
		return res

	# local mrsp 0..1 and lcl_mrsp_2
	if name.startswith("LCL_"):
		aliased = bits(word, 39, 0)
		unaliased = unalias_address(aliased)
		res.update({
			"xb3": bits(word, 58, 56),
			"hreq_id": bits(word, 55, 50),
			"dmask": bits(word, 49, 46),
			"ns": bits(word, 45, 45),
			"xb5": bits(word, 44, 40),
			"aliased_addr": fmt_hex_no0x(aliased),
			"unaliased_addr": fmt_hex_no0x(unaliased),
		})
		return res

	# fallback: expose generic fields similar to generic_cmd_t
	res.update({
		"sz": bits(word, 58, 56),
		"xb1": bits(word, 55, 55),
		"rreq_id": bits(word, 54, 50),
		"dmask": bits(word, 49, 46),
		"rest_cmd": bits(word, 35, 0),
	})
	return res


def parse_hex_field(s: str) -> Optional[int]:
	if s is None:
		return None
	s = s.strip()
	# Accept only plain hex digits (no 0x/0X prefix). Return None otherwise.
	if s == "":
		return None
	if s == "0":
		return 0
	# Only accept ASCII hex digits without any prefix
	if re.fullmatch(r"[0-9A-Fa-f]+", s):
		try:
			return int(s, 16)
		except Exception:
			return None
	return None


def find_column(headers: List[str], substr: str) -> Optional[str]:
	for h in headers:
		if substr.lower() in h.lower():
			return h
	return None


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("input", nargs="?", default="odd-app.csv", help="Input CSV file")
	ap.add_argument("-o", "--output", default="parsed.csv", help="Output CSV file")
	ap.add_argument("--sys-clock", action="store_true", help="Use system clock 322.265625 MHz instead of default 200 MHz")
	ap.add_argument("--dump-col", default=None, help="Dump column name (substring match) if not auto-detected")
	ap.add_argument("--data-col", default=None, help="Event data column name (substring match) if not auto-detected")
	ap.add_argument("--ts-col", default=None, help="Timestamp column name (substring match) if not auto-detected")
	args = ap.parse_args()

	input_path = Path(args.input)
	if not input_path.exists():
		print(f"Input file not found: {input_path}")
		sys.exit(2)

	# Choose clock frequency (MHz): default 200, use 322.265625 if --sys-clock present
	freq_mhz = 322.265625 if args.sys_clock else 200.0
	# microseconds per cycle = 1 / freq_in_MHz
	period_us = 1.0 / freq_mhz

	with input_path.open() as f:
		reader = csv.DictReader(f)
		headers = reader.fieldnames or []

		dump_col = args.dump_col or find_column(headers, "dump")
		data_col = args.data_col or find_column(headers, "event_data") or find_column(headers, "traceOut_event_data")
		ts_col = args.ts_col or find_column(headers, "traceOut_ts") or find_column(headers, "ts")
		vc_col = find_column(headers, "vc")
		src_col = find_column(headers, "traceOut_src") or find_column(headers, "src")

		if not dump_col or not data_col:
			print(f"Could not auto-detect columns. Headers: {headers}")
			sys.exit(3)

		rows = list(reader)

	# find indices where dump == 1
	dump_indices = [i for i, r in enumerate(rows) if r.get(dump_col, "0").strip() in ("1", "0x1", "0X1")]
	if len(dump_indices) < 2:
		print(f"Found only {len(dump_indices)} dump==1 occurrences; need at least 2")
		sys.exit(4)

	start_idx = dump_indices[1]
	selected = rows[start_idx:]

	out_path = Path(args.output)
	# collect decoded rows
	decoded: List[Dict[str, Any]] = []
	for idx, r in enumerate(selected, start=start_idx-1): # skip radix row
		raw = r.get(data_col, "0").strip()
		word = parse_hex_field(raw)
		ts_raw = r.get(ts_col, "0").strip() if ts_col else None
		ts_val = parse_hex_field(ts_raw) if ts_raw is not None else None
		vc_val = None
		if vc_col:
			vc_raw = r.get(vc_col, "").strip()
			vc_val = parse_hex_field(vc_raw)

		# If timestamp is all zeros (parsed to 0) treat as end of valid samples
		if ts_val == 0:
			break

		if word is None:
			# skip invalid words but continue
			continue

		# get src (traceOut_src) if present to classify message class
		src_val = None
		if src_col:
			src_raw = r.get(src_col, "").strip()
			src_val = parse_hex_field(src_raw)

		d = decode_by_opcode(word, vc=vc_val, src=src_val)
		d["index"] = idx
		# Convert cycles to microseconds using selected clock period
		if ts_val is None:
			ts_us_str = ""
		else:
			ts_us = float(ts_val) * period_us
			ts_us_str = format(ts_us, '.6f')
		d["timestamp"] = ts_us_str
		# keep original cycles for sorting/traceability
		d["cycles"] = ts_val
		if vc_val is not None:
			d["vc"] = vc_val
		if src_val is not None:
			d["src"] = src_val
		decoded.append(d)

	# Sort decoded rows by original cycle count ("cycles") ascending
	decoded.sort(key=lambda x: x.get("cycles", 0))

	# build header from union of keys
	keys = ["index", "raw", "opcode", "message", "timestamp"]
	extra_keys = set()
	for d in decoded:
		extra_keys.update(k for k in d.keys() if k not in keys)
	keys.extend(sorted(extra_keys))

	with out_path.open("w", newline="") as f:
		writer = csv.DictWriter(f, fieldnames=keys)
		writer.writeheader()
		for d in decoded:
			# ensure all fields present
			out = {k: d.get(k, "") for k in keys}
			writer.writerow(out)

	print(f"Wrote {len(decoded)} decoded samples to {out_path}")


if __name__ == '__main__':
	main()
