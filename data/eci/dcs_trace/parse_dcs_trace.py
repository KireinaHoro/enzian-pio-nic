#!/usr/bin/env python3

"""parse_dcs_trace.py

Reads a CSV trace (e.g. dcs.csv), finds the second row where dump==1,
and extracts the following 4 event columns (plus timestamp) for the next N samples
while timestamp != 0:
  - dcs_trace.event.req
  - dcs_trace.event.cli
  - dcs_trace.event.action
  - dcs_trace.event.state
  - dcs_trace.ts (timestamp)

Output: CSV with these 5 columns, sorted by timestamp ascending.
"""

import argparse
import csv
import sys
from pathlib import Path
from typing import Dict, Any, List, Optional


def parse_hex_field(s: str) -> Optional[int]:
	if s is None:
		return None
	s = s.strip()
	if s == "":
		return None
	if s == "0":
		return 0
	# Only accept ASCII hex digits without any prefix
	import re
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
	ap.add_argument("input", nargs="?", default="dcs.csv", help="Input CSV file")
	ap.add_argument("-o", "--output", default="parsed_dcs_events.csv", help="Output CSV file")
	ap.add_argument("--dump-col", default=None, help="Dump column name (substring match)")
	ap.add_argument("--ts-col", default=None, help="Timestamp column name (substring match)")
	args = ap.parse_args()

	input_path = Path(args.input)
	if not input_path.exists():
		print(f"Input file not found: {input_path}")
		sys.exit(2)

	with input_path.open() as f:
		reader = csv.DictReader(f)
		headers = reader.fieldnames or []

		dump_col = args.dump_col or find_column(headers, "dump")
		ts_col = args.ts_col or find_column(headers, "dcs_trace.ts") or find_column(headers, "ts")
		
		# Find event columns
		req_col = find_column(headers, "dcs_trace.event.req") or find_column(headers, "event.req")
		cli_col = find_column(headers, "dcs_trace.event.cli") or find_column(headers, "event.cli")
		action_col = find_column(headers, "dcs_trace.event.action") or find_column(headers, "event.action")
		state_col = find_column(headers, "dcs_trace.event.state") or find_column(headers, "event.state")

		if not dump_col or not ts_col:
			print(f"Could not auto-detect dump or timestamp columns. Headers: {headers}")
			sys.exit(3)

		if not all([req_col, cli_col, action_col, state_col]):
			print(f"Could not auto-detect event columns. Available headers: {headers}")
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
	# collect extracted rows
	extracted: List[Dict[str, Any]] = []
	for idx, r in enumerate(selected, start=start_idx-1): # skip radix row
		ts_raw = r.get(ts_col, "0").strip() if ts_col else None
		ts_val = parse_hex_field(ts_raw) if ts_raw is not None else None

		# If timestamp is all zeros treat as end of valid samples
		if ts_val == 0:
			break

		req_val = r.get(req_col, "").strip()
		cli_val = r.get(cli_col, "").strip()
		action_val = r.get(action_col, "").strip()
		state_val = r.get(state_col, "").strip()

		# Convert cycles to microseconds assuming 200 MHz clock (1 cycle = 5 ns = 0.005 us)
		if ts_val is None:
			ts_us_str = ""
		else:
			ts_us = float(ts_val) * 0.005
			# emit with microsecond precision (6 decimal places)
			ts_us_str = format(ts_us, '.6f')

		d = {
			"index": idx,
			"dcs_trace.event.req": req_val,
			"dcs_trace.event.cli": cli_val,
			"dcs_trace.event.action": action_val,
			"dcs_trace.event.state": state_val,
			"timestamp": ts_us_str,
			"cycles": ts_val,  # original cycles (for sorting)
		}
		extracted.append(d)

	# Sort by original cycle count ("cycles") ascending
	extracted.sort(key=lambda x: x.get("cycles", 0))

	# Write output CSV (without timestamp_int, that was just for sorting)
	with out_path.open("w", newline="") as f:
		fieldnames = ["index", "dcs_trace.event.req", "dcs_trace.event.cli", "dcs_trace.event.action", "dcs_trace.event.state", "timestamp"]
		writer = csv.DictWriter(f, fieldnames=fieldnames)
		writer.writeheader()
		for d in extracted:
			row = {k: d.get(k, "") for k in fieldnames}
			writer.writerow(row)

	print(f"Wrote {len(extracted)} event samples to {out_path}")


if __name__ == '__main__':
	main()
