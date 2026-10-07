"""Throwaway ruler: measure the sections of the Seregno - Carnate line, absent
from the 2026-08-05 sweep because no train of the timetable of the time ran on
it. Same method as the sweep: each station projected onto every rail way
within the snap radius, one Dijkstra per starting track over the way graph,
length of the shortest path, its maxspeed runs, its passenger_lines and its
way ids. Input: the 2026-08-05 snapshot and the stop coordinates of the feed.
Output: new_sections.csv, with the columns of link_measures.csv that the
network uses.
"""
import csv
import gzip
import json
import math
import os
from heapq import heappush, heappop

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..", "..", "..")
SWEEP = os.path.join(HERE, "..", "..", "2026-08-05-network-sweep")
PAIRS = [("S01318", "S01500"), ("S01500", "S01501"), ("S01501", "S01511")]
SNAP_LEVELS_M = (90.0, 150.0, 300.0)
GRID_DEG = 0.01
R_EARTH = 6371008.8


def haversine(a, b):
	la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
	h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
	return 2 * R_EARTH * math.asin(math.sqrt(h))


def project_on_segment(p, a, b):
	kx = math.cos(math.radians(a[0])) * 111319.9
	ky = 111132.9
	bx, by = (b[1] - a[1]) * kx, (b[0] - a[0]) * ky
	px, py = (p[1] - a[1]) * kx, (p[0] - a[0]) * ky
	seg2 = bx * bx + by * by
	t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (px * bx + py * by) / seg2))
	return math.hypot(px - bx * t, py - by * t), t


snapshot = json.load(gzip.open(os.path.join(SWEEP, "network_rail.json.gz")))
coords = {e["id"]: (e["lat"], e["lon"]) for e in snapshot["elements"] if e["type"] == "node"}
ways = {e["id"]: e for e in snapshot["elements"] if e["type"] == "way"}
adj = {}
grid = {}
for wid, w in ways.items():
	nodes = w["nodes"]
	for i in range(len(nodes) - 1):
		ca, cb = coords.get(nodes[i]), coords.get(nodes[i + 1])
		if ca is None or cb is None:
			continue
		d = haversine(ca, cb)
		adj.setdefault(nodes[i], []).append((nodes[i + 1], d, wid))
		adj.setdefault(nodes[i + 1], []).append((nodes[i], d, wid))
		grid.setdefault((int(ca[0] / GRID_DEG), int(ca[1] / GRID_DEG)), []).append((wid, i))

stops = {r["stop_id"]: (r["stop_name"], float(r["stop_lat"]), float(r["stop_lon"]))
	for r in csv.DictReader(open(os.path.join(ROOT, "orari_trenord", "stops.txt")))}
next_virtual = -1


def snap(stop_id, radius):
	"""One virtual node per rail way within the radius of the station."""
	global next_virtual
	_, lat, lon = stops[stop_id]
	cell = (int(lat / GRID_DEG), int(lon / GRID_DEG))
	best = {}
	for di in (-1, 0, 1):
		for dj in (-1, 0, 1):
			for wid, i in grid.get((cell[0] + di, cell[1] + dj), []):
				a, b = coords[ways[wid]["nodes"][i]], coords[ways[wid]["nodes"][i + 1]]
				d, t = project_on_segment((lat, lon), a, b)
				if d <= radius and (wid not in best or d < best[wid][0]):
					best[wid] = (d, i, t)
	virtual = []
	for wid, (d, i, t) in best.items():
		a, b = ways[wid]["nodes"][i], ways[wid]["nodes"][i + 1]
		ca, cb = coords[a], coords[b]
		point = (ca[0] + (cb[0] - ca[0]) * t, ca[1] + (cb[1] - ca[1]) * t)
		coords[next_virtual] = point
		for u, v, length in ((next_virtual, a, haversine(ca, point)), (next_virtual, b, haversine(point, cb))):
			adj.setdefault(u, []).append((v, length, wid))
			adj.setdefault(v, []).append((u, length, wid))
		virtual.append(next_virtual)
		next_virtual -= 1
	return virtual


def shortest(start, goals, cutoff):
	dist = {start: 0.0}
	prev = {}
	heap = [(0.0, start)]
	seen = set()
	while heap:
		d, u = heappop(heap)
		if u in seen:
			continue
		seen.add(u)
		if u in goals:
			edges = []
			while u != start:
				p, wid, w = prev[u]
				edges.append((wid, w))
				u = p
			return d, edges[::-1]
		if d > cutoff:
			return None
		for v, w, wid in adj.get(u, []):
			if d + w < dist.get(v, float("inf")):
				dist[v] = d + w
				prev[v] = (u, wid, w)
				heappush(heap, (d + w, v))
	return None


rows = []
for a, b in PAIRS:
	beeline = haversine(stops[a][1:], stops[b][1:])
	best = None
	for radius in SNAP_LEVELS_M:
		starts, goals = snap(a, radius), set(snap(b, radius))
		paths = [p for p in (shortest(s, goals, max(3000.0, beeline * 3)) for s in starts) if p]
		if paths:
			best = min(paths, key=lambda p: p[0])
			break
	if best is None:
		rows.append([f"{a}_{b}", a, b, stops[a][0], stops[b][0], "no_path"] + [""] * 6)
		continue
	length, edges = best
	single = sum(w for wid, w in edges if ways[wid].get("tags", {}).get("passenger_lines") == "1")
	untagged = sum(w for wid, w in edges if "passenger_lines" not in ways[wid].get("tags", {}))
	speeds = [(ways[wid].get("tags", {}).get("maxspeed"), w) for wid, w in edges]
	eq_speed = ""
	if all(s and s.isdigit() for s, _ in speeds):
		eq_speed = round(length / sum(w / (int(s) / 3.6) for s, w in speeds) * 3.6, 1)
	rows.append([f"{a}_{b}", a, b, stops[a][0], stops[b][0], "ok", round(length, 1), round(beeline, 1),
		round(length / beeline, 4), round(single, 1), round(untagged, 1), eq_speed,
		" ".join(str(w) for w in sorted({wid for wid, _ in edges}))])

with open(os.path.join(HERE, "..", "new_sections.csv"), "w", newline="") as f:
	out = csv.writer(f)
	out.writerow(["link_id", "from", "to", "from_name", "to_name", "status", "osm_m", "beeline_m", "ratio",
		"single_track_m", "untagged_m", "eq_speed_kmh", "osm_way_ids"])
	out.writerows(rows)
for r in rows:
	print(r[:12])
