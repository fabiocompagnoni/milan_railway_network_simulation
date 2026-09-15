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
  stations{stopId: [preferenza, ...]}   binari ammessi in ordine di preferenza
                (vedi sotto); il binario pianificato è nella prima, le altre
                sono la riserva
  notes
openQuestions[]
```

Ogni preferenza è un gruppo intero (`"cadorna_s3"`: i suoi binari sono usati a
rotazione, come una stazione che distribuisce i treni sui binari liberi del
fascio) oppure un binario preciso (`"cadorna_s3/8"`: gruppo e `ref` del
binario, o il numero `1..n` per i gruppi dichiarati solo con `capacity`), come
nel piano di utilizzo dei binari reale dove una linea ha un binario fisso. Un
binario fisso occupato al momento del passaggio fa scalare alla preferenza
successiva e conta come conflitto nel log del pianificatore.

Quando il piano distingue i treni per il lato da cui arrivano, al posto della
lista si indica un oggetto con una lista per lato:

```
"S01066": {"from_north": ["bovisa_asso/7", "bovisa_asso"], "from_south": ["bovisa_asso/8"]}
```

Il lato di arrivo è quello della fermata precedente della corsa. Per la prima
fermata è il lato opposto a quello della fermata successiva: il piano assegna i
binari per verso di marcia, e un treno che parte verso nord viaggia come uno
arrivato da sud. Se la fermata precedente non è tra i
vicini dichiarati dal nodo, viene ricavata dal percorso sulla rete (si passa
comunque da un vicino dichiarato). Se il lato non è determinabile, si usano le
preferenze di tutti i lati nell'ordine scritto. Un `ref` che il gruppo non ha
fa fallire la lettura del nodo.

Chiavi di `lines` che iniziano con `*` sono regole per le linee non elencate:
`*terminal` vale per le corse che iniziano o finiscono nella stazione,
`*through` (o `*transit`) per quelle che la attraversano. Una linea elencata
per nome ha sempre la precedenza sulla regola.

Convenzioni: circolazione a sinistra; `north` è il verso che si allontana da
Milano lungo la tratta, `south` quello verso Milano; nei capolinea i binari
sono bidirezionali (`direction: null`) e restano occupati per tutta la sosta.
