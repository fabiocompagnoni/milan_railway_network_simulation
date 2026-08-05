"""Throwaway ruler: bulk-download the whole network's rail infrastructure from
Overpass, tiled 3x3 over the station bbox (the area spans ~244x196 km), merged
and deduplicated into network_rail.json. One-shot; cached on disk.
"""
import json
import time
import urllib.request

from skeleton import load_skeleton

UA = "milan-railsim-exam-project/0.1 (f.compagnoni03@gmail.com)"
ENDPOINT = "https://overpass-api.de/api/interpreter"
TILES = 3
MARGIN = 0.02

stations, _ = load_skeleton()
lats = [s[1] for s in stations.values()]
lons = [s[2] for s in stations.values()]
s0, w0 = min(lats) - MARGIN, min(lons) - MARGIN
n0, e0 = max(lats) + MARGIN, max(lons) + MARGIN

elements = {}
for i in range(TILES):
	for j in range(TILES):
		ts = s0 + (n0 - s0) * i / TILES
		tn = s0 + (n0 - s0) * (i + 1) / TILES
		tw = w0 + (e0 - w0) * j / TILES
		te = w0 + (e0 - w0) * (j + 1) / TILES
		bbox = f"{ts:.5f},{tw:.5f},{tn:.5f},{te:.5f}"
		query = f"""
[out:json][timeout:300];
(
  way["railway"~"^(rail|light_rail)$"]({bbox});
  node["railway"="switch"]({bbox});
);
(._;>;);
out body;
"""
		for attempt in range(3):
			try:
				req = urllib.request.Request(ENDPOINT, data=query.encode(), headers={"User-Agent": UA})
				with urllib.request.urlopen(req, timeout=360) as r:
					data = json.load(r)
				break
			except Exception as exc:
				print(f"tile {i},{j} attempt {attempt + 1} failed: {exc}", flush=True)
				time.sleep(30)
		else:
			raise SystemExit(f"tile {i},{j}: giving up")
		new = 0
		for el in data["elements"]:
			key = (el["type"], el["id"])
			if key not in elements:
				elements[key] = el
				new += 1
		print(f"tile {i},{j} bbox={bbox}: +{new} elements (total {len(elements)})", flush=True)
		time.sleep(10)  # be polite between tiles

merged = {"elements": list(elements.values())}
with open("network_rail.json", "w") as f:
	json.dump(merged, f)
ways = sum(1 for el in merged["elements"] if el["type"] == "way")
switches = sum(1 for el in merged["elements"] if el["type"] == "node" and el.get("tags", {}).get("railway") == "switch")
print(f"DONE ways={ways} switches={switches} total={len(merged['elements'])}")
