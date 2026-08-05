"""Throwaway ruler: measure every skeleton link against OSM. v1 (network sweep).
Method identical to the trunk ruler (validated 2026-07-30): station point
projected onto every rail way within SNAP_M (grid-indexed prefilter), one
Dijkstra per starting track, outliers > 1.2x pair minimum dropped, maxspeed
runs along the shortest path. Output: measures.json + link_measures.csv
(per DIRECTED skeleton link, joined with the GTFS oracle attributes).
"""
import csv
import json
import math
from heapq import heappush, heappop

from skeleton import load_skeleton

SNAP_M = 90.0
OUTLIER_FACTOR = 1.2
GRID_DEG = 0.01  # ~1 km cells
R_EARTH = 6371008.8


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


print("loading network_rail.json ...", flush=True)
data = json.load(open("network_rail.json"))
coords = {}
for el in data["elements"]:
	if el["type"] == "node":
		coords[el["id"]] = (el["lat"], el["lon"])
ways = {el["id"]: el for el in data["elements"] if el["type"] == "way"}

adj = {}
grid = {}  # cell -> [(way_id, edge_index)]
for wid, w in ways.items():
	nds = w["nodes"]
	for i in range(len(nds) - 1):
		a, b = nds[i], nds[i + 1]
		ca, cb = coords.get(a), coords.get(b)
		if ca is None or cb is None:
			continue
		d = haversine(ca, cb)
		adj.setdefault(a, []).append((b, d, wid))
		adj.setdefault(b, []).append((a, d, wid))
		cell = (int(ca[0] / GRID_DEG), int(ca[1] / GRID_DEG))
		grid.setdefault(cell, []).append((wid, i))
print(f"graph: {len(adj)} vertices, {len(ways)} ways", flush=True)


def nearby_projections(lat, lon):
	"""Best projection per way within SNAP_M, using the grid prefilter."""
	c0 = (int(lat / GRID_DEG), int(lon / GRID_DEG))
	best = {}
	for di in (-1, 0, 1):
		for dj in (-1, 0, 1):
			for wid, i in grid.get((c0[0] + di, c0[1] + dj), []):
				w = ways[wid]
				a, b = coords[w["nodes"][i]], coords[w["nodes"][i + 1]]
				d, t = project_on_segment((lat, lon), a, b)
				if d <= SNAP_M and (wid not in best or d < best[wid][0]):
					best[wid] = (d, i, t)
	return best


def add_virtual(vid, wid, edge_i, t):
	w = ways[wid]
	a, b = w["nodes"][edge_i], w["nodes"][edge_i + 1]
	ca, cb = coords[a], coords[b]
	lat = ca[0] + (cb[0] - ca[0]) * t
	lon = ca[1] + (cb[1] - ca[1]) * t
	coords[vid] = (lat, lon)
	da, db = haversine(ca, (lat, lon)), haversine((lat, lon), cb)
	for u, v, d in ((vid, a, da), (vid, b, db)):
		adj.setdefault(u, []).append((v, d, wid))
		adj.setdefault(v, []).append((u, d, wid))


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


stations, links = load_skeleton()

# virtual projection nodes per station
next_vid = -1
proj = {}
unsnapped = []
for sid, (name, lat, lon) in stations.items():
	entries = []
	for wid, (d, i, t) in nearby_projections(lat, lon).items():
		add_virtual(next_vid, wid, i, t)
		entries.append({"vid": next_vid, "way_id": wid, "offset_m": round(d, 1)})
		next_vid -= 1
	proj[sid] = entries
	if not entries:
		unsnapped.append(f"{sid} {name}")
print(f"stations snapped: {len(stations) - len(unsnapped)}/{len(stations)}", flush=True)
for u in unsnapped:
	print(f"  UNSNAPPED: {u}", flush=True)

results = {}
for li, (link_id, f, t, gtfs_s, routes, trips) in enumerate(links):
	key = tuple(sorted((f, t)))
	if key in results:
		continue
	pa, pb = proj.get(f, []), proj.get(t, [])
	if not pa or not pb:
		results[key] = {"status": "unsnapped"}
		continue
	name_a, lat_a, lon_a = stations[f]
	name_b, lat_b, lon_b = stations[t]
	beeline = haversine((lat_a, lon_a), (lat_b, lon_b))
	cutoff = max(3000.0, beeline * 3)
	goal_map = {e["vid"]: e for e in pb}
	paths = []
	for e in pa:
		r = dijkstra_edges(e["vid"], set(goal_map), cutoff)
		if r is None:
			continue
		length, edges = r
		runs = []
		pl_weight = {}
		for wid, w in edges:
			tags = ways[wid]["tags"] if wid in ways else {}
			ms = tags.get("maxspeed")
			ms = int(ms) if ms and ms.isdigit() else None
			if runs and runs[-1][0] == ms:
				runs[-1][1] += w
			else:
				runs.append([ms, w])
			pl = tags.get("passenger_lines")
			pl = int(pl) if pl and pl.isdigit() else None
			pl_weight[pl] = pl_weight.get(pl, 0.0) + w
		paths.append({"length_m": round(length, 1), "start_way_id": e["way_id"],
			"runs": [[m, round(l, 1)] for m, l in runs],
			"pl_weight": pl_weight,
			"way_ids": sorted({wid for wid, _ in edges})})
	if not paths:
		results[key] = {"status": "no_path", "beeline_m": round(beeline, 1)}
		continue
	paths.sort(key=lambda p: p["length_m"])
	pmin = paths[0]["length_m"]
	tracks = [p for p in paths if p["length_m"] <= pmin * OUTLIER_FACTOR]
	best = paths[0]
	# length-weighted mode of passenger_lines along the shortest path (declared
	# bundle size; more robust than path counting near stations)
	pl_tagged = {k: v for k, v in best["pl_weight"].items() if k is not None}
	pl_untagged_m = best["pl_weight"].get(None, 0.0)
	pl_mode = None
	if pl_tagged and sum(pl_tagged.values()) >= 0.5 * best["length_m"]:
		pl_mode = max(pl_tagged.items(), key=lambda kv: kv[1])[0]
	untagged = sum(l for m, l in best["runs"] if m is None)
	# equivalent constant speed over the shortest path: total length / sum(len/v),
	# defined only when every run is tagged
	eq_speed = None
	if untagged == 0 and all(m for m, _ in best["runs"]):
		tt = sum(l / (m / 3.6) for m, l in best["runs"])
		eq_speed = round(best["length_m"] / tt * 3.6, 1)
	results[key] = {"status": "ok", "n_tracks": len(tracks),
		"passenger_lines_mode": pl_mode,
		"pl_untagged_m": round(pl_untagged_m, 1),
		"min_m": pmin, "max_m": round(tracks[-1]["length_m"], 1),
		"beeline_m": round(beeline, 1), "ratio": round(pmin / beeline, 4) if beeline else None,
		"untagged_m": round(untagged, 1),
		"untagged_pct": round(100 * untagged / pmin, 1),
		"eq_speed_kmh": eq_speed,
		"maxspeed_runs": best["runs"],
		"way_ids": best["way_ids"]}
	if (li + 1) % 100 == 0:
		print(f"  measured {li + 1}/{len(links)} links", flush=True)

with open("measures.json", "w") as fjson:
	json.dump({"|".join(k): v for k, v in results.items()}, fjson)

ok = sum(1 for v in results.values() if v["status"] == "ok")
print(f"pairs: {len(results)}  ok={ok}  no_path={sum(1 for v in results.values() if v['status']=='no_path')}  unsnapped={sum(1 for v in results.values() if v['status']=='unsnapped')}", flush=True)

with open("link_measures.csv", "w", newline="") as fcsv:
	wr = csv.writer(fcsv)
	wr.writerow(["link_id", "from", "to", "status", "n_tracks", "passenger_lines_mode",
		"osm_min_m", "osm_max_m", "beeline_m", "ratio", "eq_speed_kmh", "untagged_pct",
		"gtfs_min_s", "gtfs_trips", "implied_kmh", "gtfs_routes", "osm_way_ids"])
	for link_id, f, t, gtfs_s, routes, trips in links:
		v = results[tuple(sorted((f, t)))]
		if v["status"] != "ok":
			wr.writerow([link_id, f, t, v["status"]] + [""] * 12 + [""])
			continue
		implied = round(v["min_m"] / gtfs_s * 3.6, 1) if gtfs_s > 0 else ""
		wr.writerow([link_id, f, t, "ok", v["n_tracks"], v["passenger_lines_mode"] or "",
			v["min_m"], v["max_m"], v["beeline_m"], v["ratio"], v["eq_speed_kmh"] or "",
			v["untagged_pct"], gtfs_s, trips, implied, routes.replace(",", "|"), " ".join(map(str, v["way_ids"]))])
print("wrote measures.json + link_measures.csv", flush=True)
