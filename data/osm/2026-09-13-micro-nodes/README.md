# Way OSM dei binari di stazione dei nodi micro

Estratte il 2026-09-13 dallo snapshot Overpass del 2026-08-05
(`data/osm/2026-08-05-network-sweep/network_rail.json.gz`, licenza ODbL) con
`scripts/platform_ways.py`: way con tag `railway:track_ref` entro 700 m dalla
stazione e con `name` di una linea che vi passa (a Bovisa le way RFI del
Passante ramo Certosa, della linea di Torino e della Cintura portano gli
stessi numeri dei binari FN e vengono escluse per nome).

`platform_ways.csv`: `station, station_name, ref, way_id, osm_name`.

Copertura: Domodossola 1-4, Bovisa 1-8, Saronno 1-7 e i tronchi 1Tr e 2Tr.
Cadorna non ha `railway:track_ref` nello snapshot: i suoi binari restano con
la geometria provvisoria calcolata dal modello. I `way_id` sono riportati nei
file `data/nodes/*.json` come `wayIds` di ogni binario.
