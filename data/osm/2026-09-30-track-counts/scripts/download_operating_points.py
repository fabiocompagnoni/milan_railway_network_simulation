"""Throwaway ruler: download the operating points outside stations (posti di
movimento, bivi, posti di comunicazione) over the station bbox of the
2026-08-05 sweep, in one Overpass request: a few dozen nodes, no geometry.
"""
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "2026-08-05-network-sweep", "scripts"))
from skeleton import load_skeleton  # noqa: E402

UA = "milan-railsim-exam-project/0.2 (university project, track count survey)"
ENDPOINT = "https://overpass-api.de/api/interpreter"
MARGIN = 0.02
OUT = os.path.join(os.path.dirname(__file__), "..", "operating_points.json")

stations, _ = load_skeleton()
lats = [s[1] for s in stations.values()]
lons = [s[2] for s in stations.values()]
bbox = f"{min(lats) - MARGIN:.5f},{min(lons) - MARGIN:.5f},{max(lats) + MARGIN:.5f},{max(lons) + MARGIN:.5f}"
query = f"""
[out:json][timeout:170];
node["railway"~"^(service_station|junction|crossover|spur_junction)$"]({bbox});
out body;
"""
req = urllib.request.Request(ENDPOINT, data=query.encode(), headers={"User-Agent": UA})
with urllib.request.urlopen(req, timeout=180) as r:
	elements = json.load(r)["elements"]
with open(OUT, "w") as f:
	json.dump({"downloaded": time.strftime("%Y-%m-%d"), "bbox": bbox, "elements": elements}, f, indent=1)
print(f"DONE operating_points={len(elements)}")
