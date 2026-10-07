#!/usr/bin/env python3
"""Writes one micro-node file per line for the crossing stations on single track.

Input: docs/network/misure/stazioni-binario-unico.csv (survey of 2026-09-17: tracks from
Wikipedia, OSM estimate, Fabio's own count when filled) and the mesoscopic network
scenarios/milan/network.xml for each station's neighbours.

A station becomes one group of bidirectional tracks, numbered 1..n, connected to every
neighbour: the sides are split by geography, "south" being the side of the neighbour
closest to Milano Centrale, so a train passing through changes side. Termini of a
single-track line get terminal tracks. Stations already declared in a hand-written node
are skipped. Every hypothesis is written into the file's notes.
"""
import csv, json, math, re, glob, collections, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SURVEY = ROOT / 'docs/network/misure/stazioni-binario-unico.csv'
NETWORK = ROOT / 'scenarios/milan/network.xml'
STOPS = ROOT / 'orari_trenord/stops.txt'
NODES = ROOT / 'data/nodes'
MILAN = 'S01700'

FILES = {
	'R13,RE8': 'valtellina', 'R12,RE8': 'valtellina', 'R12,R13,RE8': 'valtellina', 'R11': 'valchiavenna',
	'R3,RE3': 'valcamonica', 'R3,RE3,S31': 'brescia-iseo', 'R5': 'brescia-cremona', 'R5,R6': 'brescia-cremona',
	'R1,R5': 'bergamo-brescia', 'R1,R4,R5,RE6': 'bergamo-brescia', 'R6': 'treviglio-cremona', 'R8': 'brescia-parma-incroci',
	'R22,RE1': 'saronno-laveno', 'R21': 'gallarate-luino', 'R21,R23,RE4,RE5,RE51,S5': 'gallarate-luino',
	'R16': 'milano-asso', 'R16,R18': 'milano-asso', 'R16,S2': 'milano-asso', 'R16,S2,S4': 'milano-asso', 'R16,S2,S4,S9': 'milano-asso',
	'R18': 'como-lecco', 'R18,S7': 'como-lecco', 'R18,RE80,S11': 'como-lecco', 'S7': 'monza-molteno',
	'R14': 'carnate-bergamo', 'R14,R7': 'carnate-bergamo', 'R14,RE8,S8': 'carnate-bergamo', 'R7': 'lecco-bergamo',
	'R17,RE7': 'saronno-como', 'R27': 'saronno-novara', 'R36': 'pavia-vercelli', 'R35': 'pavia-alessandria',
	'R25,R31,R35': 'pavia-alessandria', 'R31': 'milano-mortara-incroci', 'R37': 'pavia-cremona',
	'R37,R39,RE11': 'pavia-cremona', 'R40,RE11': 'cremona-mantova', 'RE13': 'tortona-novi', 'R34': 'pavia-stradella',
	'RE80': 'ticino', '': 'ticino', 'R34,R41': 'pavia-stradella', 'R34,RE13': 'pavia-stradella', 'R35,R36': 'pavia-alessandria',
	'R37,R38,RE11': 'pavia-cremona', 'R5,R8': 'brescia-cremona', 'R7,RE8,S8': 'lecco-bergamo', 'RE5': 'varese-porto-ceresio',
	'RE5,S5': 'varese-porto-ceresio', 'RE80,S11,S9': 'seregno',
}


def slug(name):
	s = unicodedata.normalize('NFKD', name).encode('ascii', 'ignore').decode().lower()
	return re.sub(r'[^a-z0-9]+', '_', s).strip('_')


def main():
	stops = {r['stop_id']: (r['stop_name'], float(r['stop_lon']), float(r['stop_lat'])) for r in csv.DictReader(open(STOPS, encoding='utf-8'))}
	declared = set()
	for f in NODES.glob('*.json'):
		node = json.load(open(f))
		if str(node.get('status', '')).startswith('generato il'):
			continue  # our own output: a hand-written node always takes a station over
		for s in node.get('stations', []):
			declared.add(s['id'])
	net = open(NETWORK).read()
	neighbours = collections.defaultdict(set)
	for a, b in re.findall(r'<link id="(S\d+)_(S\d+)"', net):
		neighbours[a].add(b)
	lon0, lat0 = stops[MILAN][1], stops[MILAN][2]
	k = 111320.0

	def xy(sid):
		_, lon, lat = stops[sid]
		return (lon - lon0) * k * math.cos(math.radians(lat0)), (lat - lat0) * k

	rows = [r for r in csv.DictReader(open(SURVEY)) if r['incrocio_wikipedia'] == 'si' and r['stop_id'] not in declared]
	files = collections.defaultdict(list)
	for r in rows:
		files[FILES.get(r['linee'], 'linea-' + slug(r['linee']))].append(r)
	for name, group in files.items():
		if (NODES / f'{name}.json').exists() and name not in {'valtellina'}:
			pass
		stations, lines_terminal, lines_through, sources = [], {}, {}, []
		for r in sorted(group, key=lambda r: r['nome']):
			sid = r['stop_id']
			tracks = int(r['binari_reali_Fabio'] or r['binari_wikipedia'])
			terminus = r['ruolo'] == 'capolinea binario unico'
			sx, sy = xy(sid)
			nbs = sorted(neighbours[sid], key=lambda n: math.dist(xy(n), (0, 0)))
			south, north = [], []
			if nbs:
				anchor = nbs[0]
				ax, ay = xy(anchor)[0] - sx, xy(anchor)[1] - sy
				for n in nbs:
					vx, vy = xy(n)[0] - sx, xy(n)[1] - sy
					(south if n == anchor or vx * ax + vy * ay > 0 else north).append(n)
				if not north and len(nbs) > 1:
					north.append(south.pop())
			gid = slug(r['nome'])
			connections = {}
			if south:
				connections['south'] = [f'meso:{n}' for n in south]
			if north:
				connections['north'] = [f'meso:{n}' for n in north]
			stations.append({
				'id': sid, 'name': r['nome'], 'kind': 'terminal' if terminus else 'through', 'platformLengthM': None,
				'groups': [{'id': gid, 'kind': 'terminal' if terminus else 'through',
					'tracks': [{'ref': str(i + 1), 'direction': None} for i in range(tracks)],
					'connections': connections,
					'notes': f"{tracks} binari ({r['note']}); binari bidirezionali di incrocio, tutti collegati a ogni vicino: la numerazione e l'uso per linea non sono rilevati (ipotesi). south = lato di {stops[south[0]][0] if south else '-'}, verso Milano"}],
				'throats': [],
				'sidings': {'tracks': None, 'note': 'da rilevare'},
			})
			lines_terminal[sid] = [gid]
			lines_through[sid] = [gid]
			sources.append(f"{r['nome']}: {r['binari_wikipedia']} binari secondo {r['fonte_wikipedia']}; stima OSM {r['binari_OSM']}"
				+ (f"; rilievo Fabio {r['binari_reali_Fabio']}" if r['binari_reali_Fabio'] else ''))
		node = {
			'node': name,
			'title': f"Stazioni di incrocio sul binario unico: {', '.join(s['name'] for s in stations)}",
			'status': 'generato il 2026-09-17 da data/osm/2026-09-17-single-track-stations/generate_nodes.py, da rivedere a mano per i bivi',
			'detailLevel': 'un gruppo di binari bidirezionali per stazione, con il numero di binari da Wikipedia (o dal rilievo di Fabio) e i lati dalla geografia; senza gole ne\' assegnazione per linea',
			'sources': ['docs/network/misure/stazioni-binario-unico.csv (rilievo 2026-09-17)'] + sources,
			'stations': stations,
			'segments': [],
			'lines': {'*terminal': {'bundle': None, 'stations': lines_terminal}, '*through': {'bundle': None, 'stations': lines_through}},
			'openQuestions': ['Numerazione e uso per linea dei binari', 'Nei bivi: quali binari raggiungono quale linea', 'Ricoveri'],
		}
		with open(NODES / f'{name}.json', 'w') as out:
			json.dump(node, out, ensure_ascii=False, indent='\t')
			out.write('\n')
		print(name, len(stations))


if __name__ == '__main__':
	main()
