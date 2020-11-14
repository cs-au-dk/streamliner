#!/usr/bin/env python3

import sys
import json

import numpy as np
import matplotlib
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt

from pathlib import Path
from argparse import ArgumentParser, ArgumentDefaultsHelpFormatter
from collections import defaultdict

parser = ArgumentParser(formatter_class=ArgumentDefaultsHelpFormatter)
parser.add_argument('files', nargs='*', default=[Path('out.json')], type=Path)
parser.add_argument('--exclude', '-x', nargs='+', default=[], help='Blacklist benchmarks')
parser.add_argument('--include', '-i', nargs='+', default=[], help='Whitelist benchmarks')
parser.add_argument('--includeg', '-ig', nargs='+', default=[], help='Whitelist groups')
parser.add_argument('--ylim', '-y', type=int)
parser.add_argument('--figsize', '-fs', default=(16, 10), type=lambda s: tuple(map(int, s.split(','))),
					help='Figure size as a comma separated list of width and height. E.g. 8,5.')
parser.add_argument('--table', action='store_true', help='Show a table instead of creating a plot')
parser.add_argument('--pdf', action='store_true', help='Save pdf')
args = parser.parse_args()

plot_data = defaultdict(dict)

for file in args.files:
	assert file.exists(), f'Benchmark file {file} missing'
	with file.open() as f:
		data = json.load(f)

	for d in data:
		bm = d['benchmark'].split('.')
		group, name = bm[-2:]

		# Fix names from old benchmark files
		group = group.replace('Java8', 'Stream').replace('Inline', 'Opt')

		if args.includeg and group not in args.includeg: continue

		if name in args.exclude: continue
		elif args.include and name not in args.include: continue
		plot_data[group][name] = d['primaryMetric']['score']


assert plot_data

for group, benchmarks in plot_data.items():
	ordered_keys = list(sorted(benchmarks))  # Give a consistent ordering of the benchmarks
	break

# Plotting begins
n = len(ordered_keys)
m = len(plot_data)

ORDER = ['TestBaseline', 'TestStream', 'TestStreamOpt',
		 'TestPush', 'TestPushOpt', 'TestPull', 'TestPullOpt']
ordered_groups = sorted(plot_data.items(), key=lambda v: ORDER.index(v[0]))

if args.table:
	try:
		from tabulate import tabulate
	except ImportError:
		print('Please install tabulate with "apt install python3-tabulate" to generate tables.')
		sys.exit(1)

	try:
		bench_data = data[0]
		print('VM:', bench_data['vmName'], bench_data['vmVersion'])
	except (KeyError, IndexError):
		print('Unknown VM?')

	print(tabulate([[group] + [data[k] if data[k] != 0 else float('inf') for k in ordered_keys] for group, data in ordered_groups],
				   headers=['Group'] + ordered_keys, floatfmt='.3f'))
	sys.exit(0)


# Use TrueType fonts for PDF and PS
matplotlib.rcParams['pdf.fonttype'] = 42
matplotlib.rcParams['ps.fonttype'] = 42

bar_width = .8 / m
opacity = .8

fig, ax = plt.subplots(figsize=args.figsize)
fig.canvas.set_window_title(str(args.files[0]))

if args.ylim:
	ax.set_ylim((0, args.ylim))

index = np.arange(n)
bars = {}
gray = (.5, .5, .5, 1)
any_missing = False

# Create bars
for i, (group, benchmarks) in enumerate(ordered_groups):
	name = group[4:]
	if 'Opt' in group:
		fc = bars[name[:-len('Opt')]][0].get_facecolor()
		color = tuple(max(v - .2, 0) for v in fc)
		name = name.replace('Opt', ' optimized')
	else:
		color = next(ax._get_lines.prop_cycler)['color']

	colors = [color if benchmarks[k] != 0 else gray for k in ordered_keys]
	any_missing |= any(c == gray for c in colors)

	data = [benchmarks[k] if benchmarks[k] != 0 else \
			ordered_groups[i-1][1][k] for k in ordered_keys]

	bars[name] = plt.bar(index + i * bar_width, data,
						bar_width, alpha=opacity, label=name,
						hatch='///' if 'Opt' in group else None,
						edgecolor='black', color=colors)

# Add text for bars that go outside chart
if args.ylim:
	for name, patch in bars.items():
		if 'optimized' in name: continue
		for rect in patch:
			height = rect.get_height()
			if height < args.ylim: continue
			ax.text(rect.get_x() + bar_width / 2, args.ylim + 5,
					str(int(height)), ha='center', va='bottom',
					rotation=30)

plt.ylabel('Average time (ms)')
#plt.title('Time by benchmark')
plt.xticks(index + (m - 1) / 2 * bar_width, ordered_keys, rotation=30)
patch = mpatches.Patch(facecolor=gray, label='Missing optimization', hatch='///', edgecolor='black')
handles = list(bars.values()) + ([patch] if any_missing else [])
plt.legend(handles=handles)
plt.tight_layout()

if args.pdf:
	dir = Path('pdfs')
	dir.mkdir(exist_ok=True)
	pdf_path = dir / args.files[0].with_suffix('.pdf').name
	fig.savefig(pdf_path, bbox_inches='tight')
	print(f'Saved pdf to {pdf_path}')
else:
	plt.show()


