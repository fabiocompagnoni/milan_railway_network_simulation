# Rilievo del binario unico — 2026-09-30

Verifica del numero di binari delle tratte della rete simulata
(`scenarios/milan/network.xml`), ristretta alle tratte che ne hanno bisogno.

## Perché

La rete prende il numero di binari di una tratta fra due stazioni dalla moda,
pesata sulla lunghezza, del tag OSM `passenger_lines` lungo la tratta
(rilievo `2026-08-05-network-sweep`). Una tratta in parte a binario unico e in
parte doppia risulta quindi tutta doppia, anche se due treni opposti non
possono incrociarsi sul tratto singolo. Caso noto: Cesano Maderno – Ceriano
Laghetto-Solaro, a binario unico fra Cesano e PM Groane.

## Tratte verificate

| Gruppo | Criterio | Tratte |
|---|---|---|
| Miste | contate doppie, ma con più del 2% del percorso OSM etichettato `passenger_lines=1` | 25 |
| A binario unico con punti di incrocio | contate a binario unico, con un posto di movimento o un bivio OSM entro 60 m dal percorso | 8 |
| Non misurate | senza numero di binari nella rete | 7 |

Le altre tratte a binario unico (292) e doppie non sono state riesaminate.

## Fonti

- **Binari**: istantanea OSM del 2026-08-05 (`../2026-08-05-network-sweep/network_rail.json.gz`)
  e percorso di ogni tratta dallo stesso rilievo (`link_measures.csv`,
  colonna `osm_way_ids`). Dati © OpenStreetMap contributors, ODbL 1.0.
- **Punti di snodo**: `operating_points.json`, scaricato il 2026-09-30 via
  Overpass API, nodi `railway=service_station | junction | crossover |
  spur_junction` nell'area delle stazioni della rete: 76 punti (posti di
  movimento, bivi, posti di comunicazione).
- **Rete**: `scenarios/milan/network.xml`, attributo `tracksTotal`.

## Metodo

Per ogni tratto etichettato a binario unico di una tratta mista:

- **criterio A, tag**: il tratto è a binario unico perché OSM lo dichiara
  (`passenger_lines=1`);
- **criterio B, geometria**: il tratto è a binario unico se in meno di metà
  dei punti campionati ogni 25 m c'è un altro binario di corsa
  (`railway=rail` senza `service=*`) entro 12 m e con direzione entro 25°.

Le posizioni sono in km dalla prima stazione della coppia, in ordine di
codice, proiettando sulla retta fra le due stazioni.

Script usa-e-getta in `scripts/`, documentazione del metodo e non parte del
progetto Java:

- `download_operating_points.py`: scarico dei punti di snodo;
- `measure_tracks.py`: misura e produzione dei due file sotto.

## File

| File | Contenuto |
|---|---|
| `operating_points.json` | punti di snodo OSM, con posizione e tag |
| `sections.csv` | una riga per tratta verificata: lunghezza, binari nella rete, tratti a binario unico per i due criteri, punti di snodo |
| `review.csv` | le tratte da confermare, con il motivo |

| `confirmed.csv` | esito di ogni tratta verificata, con la fonte |
| `new_sections.csv` | misura delle tre tratte della Seregno - Carnate, assenti dal rilievo del 2026-08-05 (`scripts/measure_new_sections.py`) |

## Esito

Tutte le tratte sono state verificate per rilievo diretto il 2026-09-30 e il
2026-10-02; l'esito di ognuna e' in `confirmed.csv`. Le correzioni sono
applicate a `scenarios/milan/network.xml`, da cui si rigenerano la rete di
dettaglio e i file della mappa:

- **a binario unico** (erano contate doppie o senza dato): Camnago-Lentate -
  Seveso, Busto Arsizio - Busto Arsizio Nord, Laveno Mombello - Sangiano,
  Bergamo - Seriate, Iseo - Brescia, Treviglio Ovest - Treviglio;
- **a doppio binario** (erano contate a binario unico o senza dato):
  Bisuschio Viggiu' - Arcisate, Cadenazzo - Quartino, Lungavilla - Voghera,
  Chiasso - Balerna, Balerna - Mendrisio, Chiasso - Mendrisio, Mendrisio
  S. Martino - Capolago-Riva S. Vitale, Castione - Bellinzona;
- **spezzate dove cambia il numero di binari**, con un nodo nuovo:

| Tratta | Nodo | Binario unico | Doppio binario |
|---|---|---|---|
| Cesano Maderno - Ceriano Laghetto-Solaro | `S01927` Ceriano Laghetto Groane, a 59 m da PM Groane | Cesano Maderno - nodo, 1,9 km | nodo - Ceriano Laghetto-Solaro, 2,8 km |
| Induno Olona - Varese | `PPINDUNO` P.P. Induno | nodo - Varese, 2,7 km | Induno Olona - nodo, 0,9 km |
| Cadenazzo - Riazzino | `CADENAZZOOVEST` bivio Cadenazzo Ovest | nodo - Riazzino, 3,8 km | Cadenazzo - nodo, 1,7 km |
| Tortona - Pozzolo Formigaro | `BIVIOPOZZOLO` Bivio Pozzolo F. | nodo - Pozzolo Formigaro, 4,7 km | Tortona - nodo, 9,3 km |

La lunghezza della tratta e' divisa in proporzione alla distanza in linea
d'aria del nodo dalle due stazioni; il tempo minimo di percorrenza nella
stessa proporzione. Un nodo senza fermata riceve due binari, come ogni
stazione al confine fra binario unico e doppio, ed e' quindi un punto di
incrocio.

Le altre tratte verificate restano come nella rete: il binario unico indicato
da OSM appartiene a un'altra linea che corre accanto (Monza - Molteno, Seregno
- Carnate, Lecco - Brescia a Calolziocorte-Olginate) o non e' di corsa.
