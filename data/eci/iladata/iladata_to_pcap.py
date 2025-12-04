#!/usr/bin/env python3
"""
Convert Vivado ILA AXIS interface dump to PCAP format.

Parses an ILA CSV dump of an AXIS interface (512-bit) carrying Ethernet frames
and assembles them into individual packets, saving to a PCAP file.
"""

import csv
import struct
import sys
from datetime import datetime
from pathlib import Path


class PCAPWriter:
    """Write packets to PCAP format."""
    
    # PCAP global header format
    PCAP_GLOBAL_HEADER = struct.pack(
        '<IHHIIII',
        0xa1b2c3d4,  # Magic number
        2, 4,        # Version major, minor
        0,           # Timezone offset
        0,           # Timestamp accuracy
        65535,       # Max packet length
        1            # Data link type (Ethernet)
    )
    
    def __init__(self, filename):
        """Initialize PCAP writer."""
        self.filename = filename
        self.file = open(filename, 'wb')
        self.file.write(self.PCAP_GLOBAL_HEADER)
        self.packet_count = 0
    
    def write_packet(self, data, timestamp_us=0):
        """Write a packet to the PCAP file."""
        ts_sec = timestamp_us // 1_000_000
        ts_usec = timestamp_us % 1_000_000
        
        # PCAP packet header
        packet_header = struct.pack(
            '<IIII',
            ts_sec,           # Timestamp seconds
            ts_usec,          # Timestamp microseconds
            len(data),        # Captured length
            len(data)         # Original length
        )
        
        self.file.write(packet_header)
        self.file.write(data)
        self.packet_count += 1
    
    def close(self):
        """Close the PCAP file."""
        self.file.close()


def parse_hex_data(hex_string):
    """Convert hex string to bytes."""
    return bytes.fromhex(hex_string)


def parse_tkeep_mask(tkeep_hex):
    """
    Parse tkeep mask and return number of valid bytes.
    tkeep is 64 bits, each bit represents one byte in the 512-bit data.
    """
    tkeep = int(tkeep_hex, 16)
    
    # Count valid bytes from LSB
    valid_bytes = 0
    for i in range(64):
        if (tkeep >> i) & 1:
            valid_bytes += 1
        else:
            # Assume tkeep is contiguous from LSB
            break
    
    return valid_bytes


def extract_valid_bytes(tdata_hex, tkeep_hex):
    """
    Extract valid bytes from tdata based on tkeep mask.
    
    tdata is 512 bits (64 bytes), represented as a hex string.
    The hex string represents bytes in big-endian order, so we need to reverse.
    tkeep is 64 bits, one bit per byte.
    Returns only the valid bytes in correct byte order.
    """
    # Convert hex string to bytes and reverse (big-endian to little-endian)
    tdata = parse_hex_data(tdata_hex)
    tdata = tdata[::-1]  # Reverse byte order
    tkeep = int(tkeep_hex, 16)
    
    valid_bytes = bytearray()
    for i in range(64):
        if (tkeep >> i) & 1:
            valid_bytes.append(tdata[i])
    
    return bytes(valid_bytes)


def parse_ila_dump(csv_file):
    """
    Parse ILA dump CSV and extract Ethernet packets.
    
    Returns a list of tuples (packet_data, timestamp_counter).
    probe0 is a 250 MHz counter, convert to microseconds.
    """
    packets = []
    current_packet = bytearray()
    first_timestamp = None
    current_timestamp = None
    
    with open(csv_file, 'r') as f:
        reader = csv.DictReader(f)
        
        # Skip the radix line
        next(reader)
        
        for row in reader:
            # Parse relevant columns
            tvalid = int(row['i_app/design_1_i/hier_ilas/ila_cmac_rx/U0/net_slot_0_axis_tvalid'])
            tlast = int(row['i_app/design_1_i/hier_ilas/ila_cmac_rx/U0/net_slot_0_axis_tlast'])
            tdata_hex = row['i_app/design_1_i/hier_ilas/ila_cmac_rx/U0/net_slot_0_axis_tdata[511:0]']
            tkeep_hex = row['i_app/design_1_i/hier_ilas/ila_cmac_rx/U0/net_slot_0_axis_tkeep[63:0]']
            probe0_hex = row['i_app/design_1_i/hier_ilas/ila_cmac_rx/U0/probe0[47:0]']
            
            # Parse probe0 as 250 MHz counter
            probe0_cycles = int(probe0_hex, 16)
            timestamp_us = int(probe0_cycles * 1_000_000 / 250_000_000)  # Convert 250 MHz cycles to microseconds
            
            # Only process when tvalid is high
            if tvalid == 1:
                # Capture timestamp at start of packet
                if len(current_packet) == 0:
                    first_timestamp = timestamp_us
                    current_timestamp = timestamp_us
                
                # Extract valid bytes from this beat
                valid_bytes = extract_valid_bytes(tdata_hex, tkeep_hex)
                current_packet.extend(valid_bytes)
                
                # Check if this is the last beat of a packet
                if tlast == 1:
                    if len(current_packet) > 0:
                        packets.append((bytes(current_packet), first_timestamp))
                        current_packet = bytearray()
                        first_timestamp = None
    
    # Handle any incomplete packet at end
    if len(current_packet) > 0:
        packets.append((bytes(current_packet), first_timestamp))
    
    return packets


def main():
    """Main entry point."""
    if len(sys.argv) < 2:
        print("Usage: python iladata_to_pcap.py <input_csv> [output_pcap]")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else Path(input_file).stem + '.pcap'
    
    print(f"Parsing ILA dump: {input_file}")
    packets = parse_ila_dump(input_file)
    
    print(f"Found {len(packets)} packets")
    
    # Write to PCAP
    pcap = PCAPWriter(output_file)
    for i, (packet, timestamp_us) in enumerate(packets):
        pcap.write_packet(packet, timestamp_us)
        print(f"  Packet {i+1}: {len(packet)} bytes, timestamp: {timestamp_us} µs")
    
    pcap.close()
    print(f"\nWrote {pcap.packet_count} packets to {output_file}")


if __name__ == '__main__':
    main()
