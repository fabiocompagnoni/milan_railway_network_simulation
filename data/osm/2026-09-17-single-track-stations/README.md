# Stazioni di incrocio sul binario unico (2026-09-17)

`generate_nodes.py` scrive i file `data/nodes/<linea>.json` per le 140
stazioni di incrocio che toccano una tratta a binario unico, a partire dal
censimento `docs/network/misure/stazioni-binario-unico.csv` (binari da
Wikipedia, stima OpenStreetMap, rilievo di Fabio quando compilato) e dai vicini
di ogni stazione nella rete meso `scenarios/milan/network.xml`.

Regole di generazione, tutte dichiarate nelle note dei file:

- una stazione = un gruppo di binari bidirezionali numerati da 1 a n, collegati
  a ogni vicino; la numerazione reale e l'uso per linea non sono rilevati;
- i lati: `south` è il lato del vicino più vicino a Milano Centrale in linea
  d'aria, e i vicini nella stessa direzione; `north` gli altri; nei bivi con
  tre o più vicini la divisione è geografica e va rivista a mano;
- i capolinea di linee a binario unico (Asso, Como Lago, Laveno Lago, Novara
  Nord, Vercelli, Bozzolo, Porto Ceresio, Locarno) hanno binari di testa;
- le stazioni già descritte in un nodo scritto a mano sono saltate;
- le 96 fermate a un binario non diventano nodi: stanno in
  `docs/network/misure/binari-stazioni.csv` con valore 1 e fonte, perché un
  nodo a un solo binario sarebbe un confine di blocco e i treni opposti si
  bloccherebbero lì invece di incrociarsi nella stazione successiva.

Per rigenerare: `python3 data/osm/2026-09-17-single-track-stations/generate_nodes.py`,
poi `CreateMicroNetwork`. Un file modificato a mano va tolto dalla mappa
`FILES` dello script, o riscritto.
