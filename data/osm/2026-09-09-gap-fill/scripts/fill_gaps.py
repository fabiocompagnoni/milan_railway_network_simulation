"""Throwaway ruler: re-measure the skeleton pairs the 2026-08-05 sweep left
without a path (status no_path / unsnapped). For each pair a fresh Overpass
extract around the two stations is merged over the archived snapshot, then the
sweep method is re-run with a progressive station snap radius (90/150/300 m:
underground and offset stations) and a wider Dijkstra cutoff. Output: gap_rail.json (fresh
elements), gap_measures.json, gap_rows.csv (link_measures.csv rows).
"""
import csv
import json
import math
import os
import sys
import time
import urllib.request
from heapq import heappush, heappop

sys.path.insert(0, "../../2026-08-05-network-sweep/scripts")
from skeleton import load_skeleton  # noqa: E402

SWEEP = "../../2026-08-05-network-sweep"
UA = "milan-railsim-exam-project/0.1 (f.compagnoni03@gmail.com)"
ENDPOINT = "https://overpass-api.de/api/interpreter"
# Progressive snap radius: the sweep radius first (yards are not over-counted),
# widened only when a station lies farther from the tracks of the pair's line
# (underground platforms, second station body on another line).
SNAP_LEVELS_M = (90.0, 150.0, 300.0)
OUTLIER_FACTOR = 1.2
GRID_DEG = 0.01
R_EARTH = 6371008.8
MARGIN_DEG = 0.02

stations, links = load_skeleton()
old = csv.DictReader(open(f"{SWEEP}/link_measures.csv"))
pairs = sorted({tuple(sorted((r["from"], r["to"]))) for r in old if r["status"] != "ok"})
print(f"{len(pairs)} pairs to fill", flush=True)


def haversine(a, b):
	la1, lo1 = map(math.radians, a)
	la2, lo2 = map(math.radians, b)
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


def fetch(bbox):
	query = f"""
[out:json][timeout:180];
(
  way["railway"~"^(rail|light_rail)$"]({bbox});
);
(._;>;);
out body;
"""
	for attempt in range(3):
		try:
			req = urllib.request.Request(ENDPOINT, data=query.encode(), headers={"User-Agent": UA})
			with urllib.request.urlopen(req, timeout=240) as r:
				return json.load(r)["elements"]
		except Exception as exc:
			print(f"  attempt {attempt + 1} failed: {exc}", flush=True)
			time.sleep(20)
	raise SystemExit("Overpass: giving up")


fresh = {}
if os.path.exists("gap_rail.json"):
	fresh = {(el["type"], el["id"]): el for el in json.load(open("gap_rail.json"))["elements"]}
	pairs_to_fetch = []
else:
	pairs_to_fetch = pairs
for a, b in pairs_to_fetch:
	(_, la, lo), (_, lb, lob) = stations[a], stations[b]
	bbox = f"{min(la, lb) - MARGIN_DEG:.5f},{min(lo, lob) - MARGIN_DEG:.5f},{max(la, lb) + MARGIN_DEG:.5f},{max(lo, lob) + MARGIN_DEG:.5f}"
	print(f"fetch {a}-{b} bbox={bbox}", flush=True)
	new = 0
	for el in fetch(bbox):
		key = (el["type"], el["id"])
		if key not in fresh:
			fresh[key] = el
			new += 1
	print(f"  +{new} elements", flush=True)
	time.sleep(5)
json.dump({"elements": list(fresh.values())}, open("gap_rail.json", "w"))

print("merging with archived snapshot ...", flush=True)
merged = {}
for el in json.load(open(f"{SWEEP}/network_rail.json"))["elements"]:
	merged[(el["type"], el["id"])] = el
merged.update(fresh)

coords = {k[1]: (el["lat"], el["lon"]) for k, el in merged.items() if el["type"] == "node"}
ways = {k[1]: el for k, el in merged.items() if el["type"] == "way"}
adj = {}
grid = {}
for wid, w in ways.items():
	nds = w["nodes"]
	for i in range(len(nds) - 1):
		na, nb = nds[i], nds[i + 1]
		ca, cb = coords.get(na), coords.get(nb)
		if ca is None or cb is None:
			continue
		d = haversine(ca, cb)
		adj.setdefault(na, []).append((nb, d, wid))
		adj.setdefault(nb, []).append((na, d, wid))
		grid.setdefault((int(ca[0] / GRID_DEG), int(ca[1] / GRID_DEG)), []).append((wid, i))


def nearby_projections(lat, lon, radius):
	c0 = (int(lat / GRID_DEG), int(lon / GRID_DEG))
	best = {}
	for di in (-1, 0, 1):
		for dj in (-1, 0, 1):
			for wid, i in grid.get((c0[0] + di, c0[1] + dj), []):
				w = ways[wid]
				a, b = coords[w["nodes"][i]], coords[w["nodes"][i + 1]]
				d, t = project_on_segment((lat, lon), a, b)
				if d <= radius and (wid not in best or d < best[wid][0]):
					best[wid] = (d, i, t)
	return best


next_vid = [-1]


def add_virtual(wid, edge_i, t):
	vid = next_vid[0]
	next_vid[0] -= 1
	w = ways[wid]
	a, b = w["nodes"][edge_i], w["nodes"][edge_i + 1]
	ca, cb = coords[a], coords[b]
	lat = ca[0] + (cb[0] - ca[0]) * t
	lon = ca[1] + (cb[1] - ca[1]) * t
	coords[vid] = (lat, lon)
	for u, v, d in ((vid, a, haversine(ca, (lat, lon))), (vid, b, haversine((lat, lon), cb))):
		adj.setdefault(u, []).append((v, d, wid))
		adj.setdefault(v, []).append((u, d, wid))
	return vid


def dijkstra_edges(start, goals, cutoff_m):
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
			cur = u
			while cur != start:
				p, wid, w = prev[cur]
				edges.append((wid, w))
				cur = p
			edges.reverse()
			return d, edges
		if d > cutoff_m:
			return None
		for v, w, wid in adj.get(u, []):
			nd = d + w
			if nd < dist.get(v, float("inf")):
				dist[v] = nd
				prev[v] = (u, wid, w)
				heappush(heap, (nd, v))
	return None


proj = {}


def projections(sid, radius):
	key = (sid, radius)
	if key not in proj:
		name, lat, lon = stations[sid]
		proj[key] = [(add_virtual(wid, i, t), wid, round(d, 1))
			for wid, (d, i, t) in nearby_projections(lat, lon, radius).items()]
	return proj[key]


def measure(a, b, beeline, radius):
	goal_map = {vid: (wid, off) for vid, wid, off in projections(b, radius)}
	paths = []
	for vid, wid, off in projections(a, radius):
		r = dijkstra_edges(vid, set(goal_map), max(5000.0, beeline * 3))
		if r is None:
			continue
		paths.append(r)
	return paths


results = {}
for a, b in pairs:
	(name_a, la, lo), (name_b, lb, lob) = stations[a], stations[b]
	beeline = haversine((la, lo), (lb, lob))
	paths = []
	for radius in SNAP_LEVELS_M:
		raw = measure(a, b, beeline, radius)
		if raw:
			print(f"{name_a} - {name_b}: snapped at {radius:.0f} m", flush=True)
			break
	for length, edges in raw:
		runs, pl_weight = [], {}
		for w_id, w in edges:
			tags = ways[w_id]["tags"] if w_id in ways else {}
			ms = tags.get("maxspeed")
			ms = int(ms) if ms and ms.isdigit() else None
			if runs and runs[-1][0] == ms:
				runs[-1][1] += w
			else:
				runs.append([ms, w])
			pl = tags.get("passenger_lines")
			pl = int(pl) if pl and pl.isdigit() else None
			pl_weight[pl] = pl_weight.get(pl, 0.0) + w
		paths.append({"length_m": round(length, 1), "runs": runs, "pl_weight": pl_weight,
			"way_ids": sorted({w_id for w_id, _ in edges})})
	key = f"{a}|{b}"
	if not paths:
		results[key] = {"status": "no_path", "beeline_m": round(beeline, 1)}
		print(f"{name_a} - {name_b}: NO PATH (beeline {beeline:.0f} m)", flush=True)
		continue
	paths.sort(key=lambda p: p["length_m"])
	best = paths[0]
	tracks = [p for p in paths if p["length_m"] <= best["length_m"] * OUTLIER_FACTOR]
	pl_tagged = {k: v for k, v in best["pl_weight"].items() if k is not None}
	pl_mode = max(pl_tagged.items(), key=lambda kv: kv[1])[0] \
		if pl_tagged and sum(pl_tagged.values()) >= 0.5 * best["length_m"] else None
	untagged = sum(l for m, l in best["runs"] if m is None)
	eq_speed = None
	if untagged == 0 and all(m for m, _ in best["runs"]):
		eq_speed = round(best["length_m"] / sum(l / (m / 3.6) for m, l in best["runs"]) * 3.6, 1)
	results[key] = {"status": "ok", "n_tracks": len(tracks), "passenger_lines_mode": pl_mode,
		"min_m": best["length_m"], "max_m": round(tracks[-1]["length_m"], 1),
		"beeline_m": round(beeline, 1), "ratio": round(best["length_m"] / beeline, 4),
		"untagged_pct": round(100 * untagged / best["length_m"], 1), "eq_speed_kmh": eq_speed,
		"maxspeed_runs": [[m, round(l, 1)] for m, l in best["runs"]], "way_ids": best["way_ids"]}
	print(f"{name_a} - {name_b}: {best['length_m']:.0f} m (beeline {beeline:.0f}, ratio {best['length_m'] / beeline:.3f}), "
		f"{len(tracks)} tracks, passenger_lines={pl_mode}, eq_speed={eq_speed}, untagged {100 * untagged / best['length_m']:.0f}%", flush=True)

json.dump(results, open("gap_measures.json", "w"), indent=1)

with open("gap_rows.csv", "w", newline="") as f:
	wr = csv.writer(f)
	for link_id, fr, to, gtfs_s, routes, trips in links:
		v = results.get(f"{min(fr, to)}|{max(fr, to)}")
		if v is None:
			continue
		if v["status"] != "ok":
			wr.writerow([link_id, fr, to, v["status"]] + [""] * 13)
			continue
		implied = round(v["min_m"] / gtfs_s * 3.6, 1) if gtfs_s > 0 else ""
		wr.writerow([link_id, fr, to, "ok", v["n_tracks"], v["passenger_lines_mode"] or "",
			v["min_m"], v["max_m"], v["beeline_m"], v["ratio"], v["eq_speed_kmh"] or "",
			v["untagged_pct"], gtfs_s, trips, implied, routes.replace(",", "|"), " ".join(map(str, v["way_ids"]))])
print("wrote gap_rail.json, gap_measures.json, gap_rows.csv", flush=True)
