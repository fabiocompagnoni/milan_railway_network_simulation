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

## `potenziamento-per-linea.json`

Corse aggiunte su percorsi scelti uno per uno, ciascuno con la propria attesa
massima. Un percorso è una linea con la sua coppia di capolinea. Le corse
reali non vengono mai spostate.
Lettore: `schedule/LineUpgradePlan`; generatore: `schedule/LineUpgrade`;
scelta dei percorsi: `schedule/RegularRoutes`.

| Campo | Significato |
|---|---|
| `serviceGapMinutes` | Oltre questo intervallo fra due corse si tratta di una pausa del servizio, che non viene riempita |
| `regularRoute.minTrips` | Corse nel giorno perché un percorso compaia fra quelli potenziabili |
| `regularRoute.minTripsPerDirection` | Corse minime per ciascun verso |
| `targetMinutes.min`, `.max` | Limiti dell'attesa massima che si può chiedere per un percorso |
| `targetMinutes.proposed` | Valore proposto dall'applicazione |
| `tunnel.referenceStop` | Fermata in cui si misura l'intervallo nel tunnel del Passante |
| `tunnel.minHeadwaySeconds` | Intervallo di riferimento fra due treni nello stesso verso nel tunnel |

### Regola

Per ogni percorso scelto e per ogni verso si considerano le sole corse della
linea che percorrono l'intero percorso in quel verso, misurate alla partenza
dal capolinea. Fra due corse consecutive il vuoto riceve
`ceil(vuoto / obiettivo) − 1` copie della corsa che lo apre, equidistanti e
arrotondate al minuto.

| Vuoto fra due corse | Obiettivo 30 | Obiettivo 15 |
|---|---|---|
| 60 min | 1 corsa, una ogni 30 min | 3 corse, una ogni 15 min |
| 30 min | nessuna | 1 corsa, una ogni 15 min |
| oltre `serviceGapMinutes` | nessuna | nessuna |

L'obiettivo vale per tutto il giorno di servizio, senza fasce orarie.

### Differenze dal Passante ad alta frequenza

| | Passante ad alta frequenza | Potenziamento per linea |
|---|---|---|
| Che cosa si misura | tutti i treni di un flusso, di qualunque linea | le corse di una linea su un percorso |
| Dove si aggiunge | relazioni fissate nei dati | percorsi scelti nell'applicazione |
| Fasce orarie | punta e morbida per tipo di giorno | nessuna |
| Corse in conflitto | rinunciate (`skipped_trips.csv`) | inserite comunque e segnalate |
| Tunnel del Passante | vincolo | riferimento |

Nessuna corsa viene rifiutata: una copia che passa nel tunnel a meno
dell'intervallo di riferimento da un altro treno è inserita e segnalata,
perché se la rete la regge è ciò che la simulazione misura.

### Che cosa viene salvato

Nella cartella `scenario` del run: `added_trips.csv` (linea, corsa, corsa
copiata, capolinea, partenza) e `tunnel_warnings.csv` (linea, corsa, istante
del passaggio, intervallo dal treno più vicino). La scheda Scenario dei
risultati li riassume per linea.

### Limiti

- Con tutti i percorsi a 15 minuti la simulazione si blocca: nel run del 5
  ottobre 2026 la regolarità è del 34,8% e 687 treni non arrivano. Le cause
  individuate sono due: i treni che saltano fermate restano dietro a quelli
  che le servono, e ai capolinea i binari sono assegnati in modo fisso. Il
  blocco è un limite del modello, non una misura della saturazione della
  rete. Lo scenario va usato su una o poche linee.
- L'intervallo si misura alla partenza dal capolinea: lungo il percorso una
  corsa aggiunta può trovarsi a ridosso di un treno di un'altra linea.
- Le corse aggiunte usano il materiale della linea; il numero di treni
  necessari non è limitato.

### Valori provvisori

- Soglie del percorso regolare (8 corse, 4 per verso).
- Intervallo di riferimento nel tunnel, 180 s, lo stesso dello scenario ad
  alta frequenza.

## `consumi-energetici.json`

Parametri del modello dei consumi: resistenza al moto, rendimenti, raggio del
recupero in frenata, ausiliari, taratura del gasolio, riempimento dei treni
per fascia oraria, massa a vuoto e casse di ogni tipo di treno. Ogni valore
porta la sua fonte o è dichiarato come ipotesi. Il modello, la verifica e i
limiti sono in `docs/network/modello-energetico.md`.
Lettore: `results/EnergyModel`. L'applicazione ne copia il contenuto nella
cartella di ogni run (`energy-model.json`), così il run conserva i parametri
con cui è stato misurato.