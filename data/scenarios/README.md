# Scenari di simulazione

Dati che descrivono gli scenari diversi dall'orario reale. Come per i nodi in `data/nodes`, le scelte sono dati con la loro motivazione, non codice.

## `passante-alta-frequenza.json`

Corse aggiunte nell'area urbana di Milano. Fuori dalle relazioni dichiarate l'orario resta quello reale, e le corse reali non vengono mai spostate.
Lettore: `schedule/DensificationPlan`; generatore: `schedule/TimetableDensifier`.

| Campo | Significato |
|---|---|
| `criteria` | I criteri con cui le relazioni sono state scelte |
| `serviceGapMinutes` | Oltre questo intervallo fra due treni si tratta di una pausa del servizio, che non viene riempita |
| `minSpacingSeconds` | Nessuna corsa viene aggiunta se l'intervallo risultante scende sotto questo valore |
| `tunnel.referenceStop` | Fermata in cui si misura l'intervallo nel tunnel del Passante |
| `tunnel.minHeadwaySeconds` | Intervallo minimo fra due treni nella stessa direzione nel tunnel |
| `relations[].flow` | Due fermate: l'intervallo si misura su tutti i treni, di qualunque linea, che fermano nella prima e poi nella seconda; per la direzione opposta, nell'ordine inverso |
| `relations[].services` | Le corse da copiare, a rotazione: linea e fermate estreme della copia. Una linea che non circola nel giorno cede il turno alla successiva |
| `relations[].intensity` | `full`: ogni vuoto sopra l'obiettivo riceve le sue corse; `reduced`: un vuoto sì e uno no |
| `relations[].throughTunnel` | La corsa attraversa il tunnel e ne rispetta l'intervallo minimo |
| `relations[].promptTermini` | Capolinea, uno per servizio al più, in cui la corsa aggiunta parte appena il treno arrivato ha invertito la marcia. Facoltativo |
| `relations[].promptTerminiReason` | Perché quei capolinea e non quelli opposti |
| `relations[].levelCrossings` | Passaggi a livello sul percorso delle corse aggiunte |
| `relations[].reason` | Motivazione della scelta |
| `discarded` | Relazioni e linee valutate e scartate, con il motivo |
| `dayProfiles` | Per feriale, sabato e festivo, le fasce orarie in cui si aggiungono corse |

Le relazioni sono servite nell'ordine del file: ciascuna misura il proprio
flusso contando anche le corse aggiunte da quelle che la precedono.

### Obiettivo

Il parametro dello scenario è l'attesa massima nelle ore di punta: 15 o 10
minuti. Fra due treni consecutivi di un flusso si aggiungono le corse
necessarie a non superarla, equidistanti.

| Vuoto fra due treni | Obiettivo 15 | Obiettivo 10 |
|---|---|---|
| 30 min, linea S isolata | 1 corsa, un treno ogni 15 min | 2 corse, un treno ogni 10 min |
| 15 min | nessuna | 1 corsa, un treno ogni 7,5 min |
| 11 min, fra due coppie di treni a Bovisa | nessuna | 1 corsa, un treno ogni 5,5 min |
| 10 min o meno | nessuna | nessuna |

| Livello della fascia | Obiettivo applicato |
|---|---|
| `peak` | quello scelto |
| `offPeak` | un gradino più lungo: 10 → 15, 15 → nessuna corsa aggiunta |
| fuori dalle fasce | nessuna corsa aggiunta |

### Partenza dai capolinea

Una corsa aggiunta collocata a metà del vuoto ignora quando arriva il treno
che dovrebbe effettuarla: il treno resta al capolinea fino alla prima partenza
utile e occupa il binario anche per più di mezz'ora. Nei capolinea elencati in
`promptTermini` la corsa parte invece quando il treno è pronto.

- Il treno è quello arrivato con una corsa aggiunta della stessa linea; fra più
  linee parte per primo quello fermo da più tempo.
- È pronto dopo il tempo tecnico di inversione, 5 minuti
  (`SchedulePipeline.TURNAROUND_SECONDS`).
- La partenza resta dentro il vuoto e rispetta l'attesa massima, la distanza
  minima fra due treni e l'intervallo del tunnel. Se nessun treno può partire
  entro questi limiti, la corsa resta a metà del vuoto.
- La direzione che parte dai capolinea dichiarati viene riempita per seconda,
  sugli arrivi dell'altra.

Le corse reali e le linee fuori dalle relazioni non sono interessate. Il
prezzo è la regolarità: le corse aggiunte in quella direzione non sono più
equidistanti.

### Limiti

- La partenza anticipata vale a un solo capo di ogni servizio: all'altro la
  sosta dipende dall'orario e può allungarsi.

- Il flusso è misurato in un solo punto della relazione. Lungo il percorso una
  corsa aggiunta può trovarsi a ridosso di un treno di un'altra linea.
- I passaggi a livello non sono modellati nella simulazione.
- L'orario comprende il solo servizio Trenord.

### Valori provvisori

- Intervallo minimo nel tunnel, 180 s, e distanza minima fra due treni, 300 s.
- Fasce orarie e livelli per tipo di giorno, definiti in assenza di conteggi
  dei passeggeri.

Vanno confermati con la serie sperimentale registrata in `docs/esperimenti`.