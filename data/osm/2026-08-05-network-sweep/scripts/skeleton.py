"""Throwaway ruler: stations and link pairs from the committed network skeleton.
Source: scenarios/milan/network-skeleton.xml (EPSG:32632).
"""
import math
import xml.etree.ElementTree as ET

SKELETON = "/home/fabio/Scrivania/milan_railway_network_simulation/scenarios/milan/network-skeleton.xml"


def utm32n_to_wgs84(x, y):
	"""Inverse transverse Mercator (UTM 32N, WGS84). ~1e-6 deg accuracy."""
	a = 6378137.0
	f = 1 / 298.257223563
	k0 = 0.9996
	e2 = f * (2 - f)
	ep2 = e2 / (1 - e2)
	lon0 = math.radians(9.0)
	x0 = x - 500000.0
	m = y / k0
	mu = m / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
	e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
	phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
		+ (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
		+ (151 * e1 ** 3 / 96) * math.sin(6 * mu))
	sin1, cos1 = math.sin(phi1), math.cos(phi1)
	c1 = ep2 * cos1 ** 2
	t1 = math.tan(phi1) ** 2
	n1 = a / math.sqrt(1 - e2 * sin1 ** 2)
	r1 = a * (1 - e2) / (1 - e2 * sin1 ** 2) ** 1.5
	d = x0 / (n1 * k0)
	lat = phi1 - (n1 * math.tan(phi1) / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * ep2) * d ** 4 / 24
		+ (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * ep2 - 3 * c1 ** 2) * d ** 6 / 720)
	lon = lon0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
		+ (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * ep2 + 24 * t1 ** 2) * d ** 5 / 120) / cos1
	return math.degrees(lat), math.degrees(lon)


def load_skeleton():
	"""Returns (stations, links): stations {stop_id: (name, lat, lon)};
	links [(link_id, from_id, to_id, gtfs_min_s, gtfs_routes, gtfs_trips)]."""
	root = ET.parse(SKELETON).getroot()
	stations = {}
	for n in root.iter("node"):
		a = n.find("attributes")
		name = ""
		for at in (a if a is not None else []):
			if at.get("name") == "gtfsStopName":
				name = at.text
		lat, lon = utm32n_to_wgs84(float(n.get("x")), float(n.get("y")))
		stations[n.get("id")] = (name, lat, lon)
	links = []
	for l in root.iter("link"):
		a = l.find("attributes")
		attrs = {at.get("name"): at.text for at in a} if a is not None else {}
		links.append((l.get("id"), l.get("from"), l.get("to"),
			int(attrs.get("gtfsMinTravelTimeSeconds", -1)),
			attrs.get("gtfsRoutes", ""), int(attrs.get("gtfsDailyTrips", 0))))
	return stations, links


if __name__ == "__main__":
	stations, links = load_skeleton()
	lats = [s[1] for s in stations.values()]
	lons = [s[2] for s in stations.values()]
	print(f"stations={len(stations)} links={len(links)}")
	print(f"bbox: {min(lats):.4f},{min(lons):.4f},{max(lats):.4f},{max(lons):.4f}")
