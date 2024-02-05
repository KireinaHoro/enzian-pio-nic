#!/usr/bin/env python3

import csv
import argparse
import scipy.stats as st
import numpy as np

import matplotlib.pyplot as plt

parser = argparse.ArgumentParser(
            prog='plot.py',
            description='Plot data from the PCIe PIO experiment',
            epilog='Report bugs to Pengcheng Xu <pengcheng.xu@inf.ethz.ch>.'
        )

parser.add_argument('--loopback', help='CSV file for loopback time measurements', default='loopback.csv')
parser.add_argument('--pcie', help='CSV file for PCIe latency measurements', default='pcie_lat.csv')

args = parser.parse_args()

FREQ = 250e6 # 250 MHz

# ================ DATA PROC ================

def cyc_to_us(cycles):
    if type(cycles) == str:
        cycles = int(cycles)
    return 1e6 / FREQ * cycles

# convert to median + ci
def vals_to_med_ci(vals, dtype=float):
    med = np.median(vals)
    bt_ci = st.bootstrap((vals,), np.median, confidence_level=.95, method='percentile')
    plot_lo = med - bt_ci.confidence_interval.low
    plot_hi = bt_ci.confidence_interval.high - med
    return med, (plot_lo, plot_hi)

# read PCIe RTT
pcie_lat_us = []
with open(args.pcie, 'r') as f:
    reader = csv.DictReader(f)

    for row in reader:
        pcie_lat_us.append(cyc_to_us(row['pcie_lat_cyc']))

pcie_lat_median, (pcie_lat_ci_low, pcie_lat_ci_high) = vals_to_med_ci(pcie_lat_us)
bootstrap_ci = st.bootstrap((pcie_lat_us,), np.median, confidence_level=.95, method='percentile')
print(f'mean PCIe latency: {pcie_lat_median}; 95% CI: {bootstrap_ci.confidence_interval}')

loopback_stats = {}

# read loopback times
with open(args.loopback, 'r') as f:
    reader = csv.DictReader(f)
    for row in reader:
        size = int(row['size'])
        size_stats = loopback_stats.setdefault(size, {})

        def append_type(label, value):
            type_stats = size_stats.setdefault(label, [])
            type_stats.append(value)

        def cycdiff_to_us(start_col, end_col):
            start = int(row[start_col])
            end = int(row[end_col])
            return cyc_to_us(end - start)

        append_type('tx', float(row['tx_us']))

        append_type('rx queue', cycdiff_to_us('entry_cyc', 'after_rx_queue_cyc'))
        append_type('rx stream buf', cycdiff_to_us('after_rx_queue_cyc', 'after_dma_write_cyc'))
        
        # only add wait to host if read happens later than entry
        wait = cycdiff_to_us('after_dma_write_cyc', 'read_start_cyc')
        if wait > 0:
            append_type('rx wait host', wait)
            append_type('rx issue cmd', cycdiff_to_us('read_start_cyc', 'after_read_cyc'))
        else:
            append_type('rx issue cmd', cycdiff_to_us('after_dma_write_cyc', 'after_read_cyc'))

        append_type('rx read packet', cycdiff_to_us('after_read_cyc', 'host_read_complete_cyc') - pcie_lat_median / 2)
        append_type('rx process commit', cycdiff_to_us('host_read_complete_cyc', 'after_commit_cyc') - pcie_lat_median / 2)

# convert to stackplot layout
sizes = loopback_stats.keys()
label_data = {}
for stats in loopback_stats.values():
    for stat_name, vals in stats.items():
        label_data.setdefault(stat_name, []).append(vals_to_med_ci(vals))

# ================ PLOTTING ================

def fixed_ratio_fig(aspect_ratio):
    figwidth = 5.125 # page width
    return figwidth, figwidth/aspect_ratio

# plot 1: stack plot rx latency component | x=size y=latency contribution
fig = plt.figure(figsize=fixed_ratio_fig(6/5))
ax = fig.add_subplot()
ax.grid(which='major', alpha=0.5)
ax.grid(which='minor', alpha=0.2)
ax.set_title('RX Latency Breakdown (PIO)')
ax.set_xlabel('Payload Length (B)')
ax.set_ylabel('Latency (us)')
ax.set_ylim(0, 140)

comp_labels = []
comp_data = []
comp_ci = []
for lbl, data in label_data.items():
    if lbl != 'tx':
        comp_labels.append(lbl[3:]) # strip 'rx '
        dat, ci = zip(*data)
        comp_data.append(dat)
        comp_ci.append(ci)

stack = ax.stackplot(sizes, *comp_data, labels=comp_labels)

ys = np.zeros((len(sizes),), dtype=float)

for meds, cis in zip(comp_data, comp_ci):
    ys += meds
    ci_los, ci_his = zip(*cis)
    ax.errorbar(sizes, ys, yerr=(ci_los, ci_his), fmt='none', color='black')

ax.legend(loc='upper left')
fig.savefig('lat-breakdown.pdf')

# plot 2: stack plot tx latency component | x=size y=latency contribution
# TODO