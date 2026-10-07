#!/usr/bin/env python3
"""Lists the OSM ways of the platform tracks of the micro stations.

Reads the archived Overpass snapshot of 2026-08-05 and keeps the ways tagged
``railway:track_ref`` within a radius of each station whose ``name`` belongs
to the railway the station is on: near Bovisa the RFI tracks of the Passante
(ramo Certosa), the Torino line and the Cintura carry the same numbers as the
FN platforms and must not be mistaken for them. Cadorna has no track refs in
OSM at that date and is left to the geometric fallback.

Writes ``platform_ways.csv`` (station, ref, way_id, name) next to the script's
parent directory. Run from the repository root:

    python3 data/osm/2026-09-13-micro-nodes/scripts/platform_ways.py
"""
import csv
import gzip
import json
import math
from pathlib import Path

SNAPSHOT = Path("data/osm/2026-08-05-network-sweep/network_rail.json.gz")
OUTPUT = Path("data/osm/2026-09-13-micro-nodes/platform_ways.csv")
RADIUS_M = 700

# stop id -> (label, lat, lon, accepted OSM way names)
STATIONS = {
    "S01067": ("Milano Domodossola", 45.48090, 9.16224,
               {"Ferrovia Milano-Saronno", "Ferrovia Milano-Saronno/Milano-Asso"}),
    "S01642": ("Milano Bovisa Politecnico", 45.50257, 9.15925,
               {"Ferrovia Milano-Saronno", "Ferrovia Milano-Saronno/Milano-Asso",
                "Passante Porta Garibaldi-Bovisa", "Passante ferroviario di Milano (ramo Bovisa)"}),
    "S01933": ("Saronno", 45.625316, 9.030748,
               {"Ferrovia Milano-Saronno", "Ferrovia Saronno-Como", "Ferrovia Saronno-Laveno",
                "Ferrovia Saronno-Novara", "Ferrovia Saronno-Seregno"}),
}


def distance_m(a, b):
    return math.hypot((a[0] - b[0]) * 111_000, (a[1] - b[1]) * 78_000)


def main():
    data = json.load(gzip.open(SNAPSHOT))
    nodes = {e["id"]: (e["lat"], e["lon"]) for e in data["elements"] if e["type"] == "node"}
    rows = []
    for element in data["elements"]:
        if element["type"] != "way":
            continue
        tags = element.get("tags", {})
        ref = tags.get("railway:track_ref")
        if not ref:
            continue
        points = [nodes[n] for n in element["nodes"] if n in nodes]
        if not points:
            continue
        midpoint = points[len(points) // 2]
        for station, (label, lat, lon, names) in STATIONS.items():
            if distance_m(midpoint, (lat, lon)) < RADIUS_M and tags.get("name") in names:
                rows.append((station, label, ref, element["id"], tags.get("name")))
    rows.sort(key=lambda r: (r[0], len(r[2]), r[2], r[3]))
    with OUTPUT.open("w", newline="") as out:
        writer = csv.writer(out)
        writer.writerow(["station", "station_name", "ref", "way_id", "osm_name"])
        writer.writerows(rows)
    print(f"wrote {len(rows)} platform ways to {OUTPUT}")


if __name__ == "__main__":
    main()
