# Nodi micro

Descrizione dichiarativa dei nodi della rete modellati a livello microscopico
(binari, gruppi di binari, gole, tratte per binario). Un file per nodo, più
`sidings.json` per i ricoveri. `MicroNodeBuilder` innesta ogni nodo nella
rete mesoscopica al posto del link-anello di stazione e dei link adiacenti
coperti da una tratta del nodo.

Le fonti sono elencate in ogni file; nessun valore è inventato: i campi non
misurati sono `null` o marcati `provisional`, con il default usato dal modello
indicato qui sotto.

## Schema

```
node, title, status, detailLevel, sources[]
stations[]
  id            stop GTFS (nodo della rete meso)
  name, kind    terminal | through | mixed
  platformLengthM   null -> 200 m provvisori
  groups[]
    id          identificatore unico nel nodo (es. cadorna_saronno)
    kind        terminal | through
    tracks[]    binari nominati: {ref, direction}  direction = north | south | null
    capacity    in alternativa a tracks: numero di binari anonimi (stazioni FS)
    otherOperatorsShare   quota di capacity occupata da treni senza orario (default 0)
    connections {north: [...], south: [...]}
                dove il gruppo entra ed esce: "segment:<from>_<to>:<bundle>"
                (tratta del nodo) oppure "meso:<stop>" (link meso verso quel nodo)
    notes       testo libero
  throats[]
    id, side (north | south), resource, lengthM, speedKmh, switches, groups[]
                risorsa di conflitto condivisa dai link fra la gola e i gruppi elencati
  sidings       {tracks, note}  presenza di ricovero (regola in sidings.json)
  notes
segments[]
  from, to      stop GTFS; il link meso from_to (e il contrario) viene sostituito
  bundles{id}
    north/south {wayIds[], lengthM}   una lista di way OSM per binario e verso
    speedProfile[] {kmh, lengthM}     dalla partenza di from verso to
    use
lines{lineId}
  bundle        fascio usato sulle tratte del nodo (null se la linea non le percorre)
  stations{stopId: [groupId, ...]}   gruppi ammessi in ordine di preferenza;
                il binario pianificato è nel primo, gli altri sono la riserva
  notes
openQuestions[]
```

Chiavi di `lines` che iniziano con `*` sono regole per le linee non elencate:
`*terminal` vale per le corse che iniziano o finiscono nella stazione,
`*through` (o `*transit`) per quelle che la attraversano. Una linea elencata
per nome ha sempre la precedenza sulla regola.

Convenzioni: circolazione a sinistra; `north` è il verso che si allontana da
Milano lungo la tratta, `south` quello verso Milano; nei capolinea i binari
sono bidirezionali (`direction: null`) e restano occupati per tutta la sosta.
