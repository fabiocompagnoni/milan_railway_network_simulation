"""Throwaway ruler: where the sections of the simulated network run on single
track, restricted to the sections that need checking.

The simulated network (scenarios/milan/network.xml) takes the track count of
a section from the length-weighted mode of the OSM tag passenger_lines along
it, so a section partly single and partly double counts as double. Checked:
- mixed: sections counted double whose OSM path has stretches tagged
  passenger_lines=1 (more than MIXED_SHARE of the length);
- single: sections counted single, for operating points inside them, where
  trains can meet;
- unmeasured: sections without a track count.

For each single-tagged stretch of a mixed section two criteria are compared:
A, the tag itself; B, the drawn geometry: how many other running tracks
(railway=rail without service=*) run parallel within PARALLEL_M. Positions
along a section are the projection on the line between its two stations.

Inputs: the 2026-08-05 snapshot (ways, tags) and its link measures (the ways
of each section's path), operating_points.json (downloaded 2026-09-30).
Outputs: sections.csv (one row per checked section) and review.csv (the rows
to confirm, with the reason).
"""
import csv
import gzip
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..", "..", "..")
SWEEP = os.path.join(HERE, "..", "..", "2026-08-05-network-sweep")
sys.path.insert(0, os.path.join(SWEEP, "scripts"))
from skeleton import load_skeleton  # noqa: E402

MIXED_SHARE = 0.02
PARALLEL_M = 12.0
PARALLEL_DEG = 25.0
SAMPLE_M = 25.0
POINT_M = 60.0
STATION_ZONE_M = 400.0
R_EARTH = 6371008.8


def haversine(a, b):
	la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
	h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
	return 2 * R_EARTH * math.asin(math.sqrt(h))


def local_xy(p, origin):
	"""Metres east and north of origin, flat approximation (sections are a few km)."""
	return ((p[1] - origin[1]) * math.cos(math.radians(origin[0])) * 111319.9, (p[0] - origin[0]) * 111132.9)


def position_along(p, a, b, length):
	"""Metres from station a, projecting p on the line a-b scaled to the measured length."""
	bx, by = local_xy(b, a)
	px, py = local_xy(p, a)
	seg2 = bx * bx + by * by
	t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (px * bx + py * by) / seg2))
	return t * length


def distance_to_segment(p, a, b):
	bx, by = local_xy(b, a)
	px, py = local_xy(p, a)
	seg2 = bx * bx + by * by
	t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (px * bx + py * by) / seg2))
	return math.hypot(px - bx * t, py - by * t)


def bearing(a, b):
	x, y = local_xy(b, a)
	return math.degrees(math.atan2(y, x)) % 180


print("loading the 2026-08-05 snapshot ...", flush=True)
snapshot = json.load(gzip.open(os.path.join(SWEEP, "network_rail.json.gz")))
coords = {e["id"]: (e["lat"], e["lon"]) for e in snapshot["elements"] if e["type"] == "node"}
ways = {e["id"]: e for e in snapshot["elements"] if e["type"] == "way"}
running = {wid: w for wid, w in ways.items()
	if w.get("tags", {}).get("railway") == "rail" and "service" not in w.get("tags", {})}

GRID = 0.002  # about 200 m
grid = {}
for wid, w in running.items():
	for i in range(len(w["nodes"]) - 1):
		c = coords.get(w["nodes"][i])
		if c:
			grid.setdefault((int(c[0] / GRID), int(c[1] / GRID)), set()).add((wid, i))


def parallel_tracks(p, direction, own):
	"""Other running tracks within PARALLEL_M of p, roughly parallel to direction."""
	found = set()
	c0 = (int(p[0] / GRID), int(p[1] / GRID))
	for di in (-1, 0, 1):
		for dj in (-1, 0, 1):
			for wid, i in grid.get((c0[0] + di, c0[1] + dj), ()):
				if wid in own or wid in found:
					continue
				nodes = running[wid]["nodes"]
				a, b = coords.get(nodes[i]), coords.get(nodes[i + 1])
				if a is None or b is None or distance_to_segment(p, a, b) > PARALLEL_M:
					continue
				angle = abs(bearing(a, b) - direction)
				if min(angle, 180 - angle) <= PARALLEL_DEG:
					found.add(wid)
	return found


def samples(way):
	"""Points every SAMPLE_M along a way, with the bearing of their segment."""
	points = []
	pts = [coords[n] for n in way["nodes"] if n in coords]
	for a, b in zip(pts, pts[1:]):
		d = haversine(a, b)
		steps = max(1, int(d / SAMPLE_M))
		for k in range(steps):
			t = k / steps
			points.append(((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t), bearing(a, b)))
	return points


def way_length(way):
	pts = [coords[n] for n in way["nodes"] if n in coords]
	return sum(haversine(a, b) for a, b in zip(pts, pts[1:]))


def merge(intervals):
	merged = []
	for start, end in sorted(intervals):
		if merged and start <= merged[-1][1] + 50:
			merged[-1][1] = max(merged[-1][1], end)
		else:
			merged.append([start, end])
	return merged


network = open(os.path.join(ROOT, "scenarios", "milan", "network.xml")).read()
tracks_total = {lid: int(t) for lid, t in re.findall(
	r'<link id="([^"]+)"[^>]*>(?:(?!</link>).)*?tracksTotal" class="java.lang.Integer">(\d+)', network, re.S)}
section_ids = set(re.findall(r'<link id="([^"_.]+_[^"_.]+)"', network))

points = json.load(open(os.path.join(HERE, "..", "operating_points.json")))["elements"]
stations, _ = load_skeleton()
names = {sid: s[0] for sid, s in stations.items()}

measures = {}
for r in csv.DictReader(open(os.path.join(SWEEP, "link_measures.csv"))):
	measures.setdefault(tuple(sorted((r["from"], r["to"]))), r)

rows = []
review = []
done = set()
for link_id in sorted(section_ids):
	a_id, b_id = link_id.split("_")
	key = tuple(sorted((a_id, b_id)))
	if key in done:
		continue
	done.add(key)
	a_id, b_id = key
	name = f"{names.get(a_id, a_id)} - {names.get(b_id, b_id)}"
	counted = tracks_total.get(f"{a_id}_{b_id}", tracks_total.get(f"{b_id}_{a_id}"))
	m = measures.get(key)
	if counted is None or m is None or m["status"] != "ok":
		rows.append([a_id, b_id, name, "unmeasured", "", counted or "", "", "", "", ""])
		review.append([a_id, b_id, name, "tratta senza numero di binari misurato ("
			+ (m["status"] if m else "assente dal rilievo del 2026-08-05") + ")"])
		continue
	length = float(m["osm_min_m"])
	path = [ways[int(w)] for w in m["osm_way_ids"].split() if int(w) in ways]
	a, b = stations[a_id][1:], stations[b_id][1:]
	tagged_single = [w for w in path if w.get("tags", {}).get("passenger_lines") == "1"]
	single_len = sum(way_length(w) for w in tagged_single)
	path_len = sum(way_length(w) for w in path) or 1
	share = single_len / path_len
	inside = []
	for p in points:
		pc = (p["lat"], p["lon"])
		for w in path:
			pts = [coords[n] for n in w["nodes"] if n in coords]
			if any(distance_to_segment(pc, x, y) <= POINT_M for x, y in zip(pts, pts[1:])):
				inside.append(f"{p['tags'].get('name', '?')} ({p['tags']['railway']}, km {position_along(pc, a, b, length) / 1000:.1f})")
				break
	if counted == 1:
		if inside:
			rows.append([a_id, b_id, name, "single", round(length / 1000, 2), counted, "", "", "", "; ".join(inside)])
			review.append([a_id, b_id, name, "binario unico con punti di incrocio interni: " + "; ".join(inside)])
		continue
	if share <= MIXED_SHARE:
		continue
	own = {w["id"] for w in path}
	stretches_a, stretches_b = [], []
	for w in tagged_single:
		pts = [coords[n] for n in w["nodes"] if n in coords]
		interval = sorted((position_along(pts[0], a, b, length), position_along(pts[-1], a, b, length)))
		stretches_a.append(interval)
		sampled = samples(w)
		doubled = sum(1 for p, d in sampled if parallel_tracks(p, d, own))
		if sampled and doubled / len(sampled) < 0.5:
			stretches_b.append(interval)
	single_a = merge(stretches_a)
	single_b = merge(stretches_b)
	fmt = lambda runs: " ".join(f"{s / 1000:.1f}-{e / 1000:.1f}" for s, e in runs)
	km_a = sum(e - s for s, e in single_a) / 1000
	km_b = sum(e - s for s, e in single_b) / 1000
	rows.append([a_id, b_id, name, "mixed", round(length / 1000, 2), counted, fmt(single_a), fmt(single_b),
		round(km_a, 2), "; ".join(inside)])
	reasons = []
	if abs(km_a - km_b) > 0.1:
		reasons.append(f"i criteri discordano: tag {km_a:.1f} km, geometria {km_b:.1f} km")
	if single_b and all(s < STATION_ZONE_M or e > length - STATION_ZONE_M for s, e in single_b) and km_b < 0.8:
		reasons.append("binario unico solo vicino a una stazione: forse deviatoi o raccordi")
	if single_b and not inside:
		reasons.append("cambio del numero di binari senza un punto di snodo OSM vicino")
	review.append([a_id, b_id, name, f"contata doppia, binario unico da tag {fmt(single_a)} km, da geometria "
		f"{fmt(single_b) or 'nessuno'} km" + (" - " + "; ".join(reasons) if reasons else "")
		+ (" - punti: " + "; ".join(inside) if inside else "")])

with open(os.path.join(HERE, "..", "sections.csv"), "w", newline="") as f:
	out = csv.writer(f)
	out.writerow(["from", "to", "name", "check", "length_km", "tracks_in_network", "single_by_tags_km",
		"single_by_geometry_km", "single_km", "operating_points"])
	out.writerows(rows)
with open(os.path.join(HERE, "..", "review.csv"), "w", newline="") as f:
	out = csv.writer(f)
	out.writerow(["from", "to", "name", "to_confirm"])
	out.writerows(review)
kinds = {}
for r in rows:
	kinds[r[3]] = kinds.get(r[3], 0) + 1
print(f"DONE sections checked {kinds}, rows to confirm {len(review)}")
