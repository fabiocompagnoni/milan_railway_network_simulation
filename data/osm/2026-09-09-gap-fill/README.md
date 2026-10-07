# OSM gap fill — 2026-09-09

Completes the 2026-08-05 network sweep for the 11 station pairs (22 directed
links) it had left unmeasured: 4 pairs `no_path`, 7 `unsnapped`. Their rows in
`../2026-08-05-network-sweep/link_measures.csv` were replaced by the ones in
`gap_rows.csv`; every other row is untouched.

## Sources and licensing

- **Raw data**: `gap_rail.json.gz` — fresh Overpass extract (2026-09-09,
  `https://overpass-api.de/api/interpreter`) of `railway=rail|light_rail` ways
  in one bbox per pair (stations ± 0.02°), merged over the archived
  `network_rail.json` (fresh elements win). Data © OpenStreetMap contributors,
  ODbL 1.0.
- **Method**: identical to the sweep ruler (`scripts/fill_gaps.py`, throwaway,
  not part of the Java build) except for the station snap radius, tried
  progressively at 90, 150 and 300 m and kept at the first level that yields a
  path. The sweep's fixed 90 m failed here because the GTFS station points lie
  96–114 m from the tracks (Lancetti and Castellanza underground, S. Donato
  offset) or, for Cesano Maderno and Brescia, the pair's line serves a second
  station body farther away. All pairs snapped at 150 m except Porta
  Garibaldi–Centrale (90 m).

## Results

| Pair | OSM length (m) | ratio to beeline | passenger_lines | eq. speed (km/h) |
|---|---|---|---|---|
| Cesano Maderno – Seveso Baruccana | 1872 | 1.02 | 1 | 100 |
| Cesano Maderno – Ceriano Laghetto-Solaro | 4698 | 0.96 | 2 | untagged |
| Rescaldina – Castellanza | 5458 | 0.98 | 2 | 120 |
| Castellanza – Busto Arsizio Nord | 1880 | 0.91 | 2 | 120 |
| S. Donato Milanese – Milano Rogoredo | 1998 | 0.99 | 4 | 63.8 |
| S. Donato Milanese – Borgolombardo | 1949 | 0.94 | 4 | 200 |
| Milano Villapizzone – Milano Lancetti | 1998 | 0.96 | 2 | 60 |
| Milano Bovisa Politecnico – Milano Lancetti | 1606 | 1.05 | 2 | 60 |
| Milano Lancetti – Porta Garibaldi Passante | 1545 | 1.01 | 2 | 60 |
| Milano Porta Garibaldi – Milano Centrale | 4811 | 3.90 | 2 | 14% untagged |
| Brescia Borgo San Giovanni – Brescia | 1932 | 0.92 | 1 | 56.7 |

- Porta Garibaldi–Centrale is a genuine 4.8 km detour through the Greco
  Pirelli junction (beeline 1.2 km); the RE51 timetable allots 9–11 minutes to
  the hop, consistent with it.
- Ratios below 1 are expected: the path is measured between the projections
  on the track, the beeline between the GTFS station points, which sit up to
  150 m off the axis. The same effect is present in 287 sweep rows
  (minimum 0.895).
- `n_tracks` (path counting) is inflated near yards at 150 m and is not used
  by the applier beyond 2; `passenger_lines` is the declared bundle size.
