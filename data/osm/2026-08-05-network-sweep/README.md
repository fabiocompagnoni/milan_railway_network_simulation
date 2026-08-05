# OSM network sweep — 2026-08-05

Measured infrastructure for every skeleton link (station pair) of the Milan
suburban network model. Source for the mesoscopic network
(`scenarios/milan/network.xml`, applied by
`it.unimib.milanrailsim.network.ApplyMesoMeasures`).

## Sources and licensing

- **Raw data**: `network_rail.json.gz` — snapshot of OpenStreetMap rail
  infrastructure, downloaded 2026-08-05 via Overpass API
  (`https://overpass-api.de/api/interpreter`), 3×3 tiles over bbox
  `44.7625,8.2066 – 46.5276,11.3580` (all 453 stations ± 0.02°), filter
  `railway=rail|light_rail` ways + `railway=switch` nodes; 23590 ways,
  10247 switches. Data © OpenStreetMap contributors, ODbL 1.0.
- **Station coordinates and oracle times**: Trenord GTFS feed
  (`orari_trenord/`), service date 2026-09-16, via the committed skeleton
  `scenarios/milan/network-skeleton.xml`.
- **Method** (validated on the Cadorna–Saronno trunk against domain
  knowledge, 2026-07-30): station point projected onto every rail way within
  90 m (one virtual node per physical track — OSM maps each track as its own
  way); Dijkstra per starting track over the way graph (haversine edge
  weights); paths > 1.2× the pair minimum discarded as detours; maxspeed and
  `passenger_lines` aggregated along the shortest path (length-weighted mode
  for the track count); untagged stretches reported, never guessed.
- **Scripts**: `scripts/` — the exact throwaway Python rulers that produced
  these files (documentation of method; not project code and not part of the
  Java build).

## Files

| File | Content |
|---|---|
| `link_measures.csv` | one row per directed skeleton link: track counts (`n_tracks` measured, `passenger_lines_mode` declared), lengths (m), beeline ratio, equivalent constant speed (km/h, only where the whole path is maxspeed-tagged), untagged share, GTFS oracle (min travel time, trips/day, routes), OSM way ids |
| `measures.json.gz` | same measures keyed by undirected pair, with full maxspeed runs |
| `network_rail.json.gz` | raw Overpass snapshot (reproducibility) |

## Quality summary

- 1157/1179 directed links measured (`status=ok`); 4 pairs `no_path`,
  7 pairs `unsnapped` (3 stations beyond 90 m from any rail way: Castellanza,
  S.Donato Milanese, Milano Lancetti — the latter underground).
- Ratio path/beeline: median 1.021; values up to ~2.3 are real curvature
  (mountain branches); the applier trusts lengths with ratio ∈ [1.0, 2.5].
- Declared track counts (`passenger_lines`): 559 single-track, 520
  double-track, 50 quadruple-track links (of tagged ones).
- Equivalent speed available for 526/1157 links (elsewhere maxspeed tagging
  is incomplete; those links keep a GTFS-derived provisional freespeed).
- 13 links where the GTFS-implied speed slightly exceeds the OSM equivalent
  speed (≤ 8 km/h margin): consistent with GTFS minute rounding, flagged in
  the CSV via `implied_kmh` > `eq_speed_kmh`.
