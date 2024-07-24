import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
import scipy.stats as st

def warn(msg):
    print('Warning: ', msg)

FREQ = 250e6 # 250 MHz

def cyc_to_us(cycles):
    if type(cycles) == str:
        cycles = int(cycles)
    return 1e6 / FREQ * cycles

# 5.125 is page width
def fixed_ratio_fig(aspect_ratio, figwidth=3):
    return figwidth, figwidth/aspect_ratio

from matplotlib import rcParams
rcParams.update({'figure.autolayout': True})

def create_plot(name, figsize=fixed_ratio_fig(6/5)):
    fig = plt.figure(figsize=figsize)
    ax = fig.add_subplot()
    ax.grid(which='major', alpha=0.5)
    ax.grid(which='minor', alpha=0.2)
    ax.xaxis.set_major_formatter(ticker.EngFormatter())
    # ax.set_title(name)
    direction = name[:2]
    ax.set_xlabel(direction + ' Payload Length (B)')
    ax.set_ylabel(direction + ' Latency (us)')
    return fig, ax

# convert to median + ci
def vals_to_med_ci(vals, dtype=float, name='unknown'):
    med = np.median(vals)
    if len(vals) == 1:
        warn(f'series "{name}" has only {len(vals)} elements, generating dummy CI.')
        return med, (med, med)
    bt_ci = st.bootstrap((vals,), np.median, confidence_level=.95, method='percentile')
    plot_lo = med - bt_ci.confidence_interval.low
    plot_hi = bt_ci.confidence_interval.high - med
    return med, (plot_lo, plot_hi)
