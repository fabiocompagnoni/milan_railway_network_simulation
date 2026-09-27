# Scenari di simulazione

Dati che descrivono gli scenari diversi dall'orario reale. Come per i nodi in `data/nodes`, le scelte sono dati con la loro motivazione, non codice.

## `passante-alta-frequenza.json`

Corse aggiunte nell'area urbana di Milano, delimitata da Saronno, Monza,
Rogoredo, Rho Fiera e Albairate. Fuori dall'area l'orario resta quello reale.
Lettore: `schedule/DensificationPlan`.

| Campo | Significato |
|---|---|
| `serviceGapMinutes` | Oltre questo intervallo fra due corse della stessa linea si tratta di una pausa del servizio, che non viene riempita |
| `tunnel.referenceStop` | Fermata in cui si misura l'intervallo nel tunnel del Passante |
| `tunnel.minHeadwaySeconds` | Intervallo minimo fra due treni nella stessa direzione nel tunnel |
| `relations[].lines` | Linee di cui si copiano le corse; se sono più di una, le corse aggiunte si alternano |
| `relations[].from`, `to` | Fermate estreme della corsa aggiunta, valide nelle due direzioni |
| `relations[].intensity` | `full`: la linea raggiunge la cadenza; `reduced`: corse aggiunte in un intervallo sì e uno no |
| `relations[].throughTunnel` | La corsa attraversa il tunnel e ne rispetta l'intervallo minimo |
| `relations[].reason` | Motivazione della scelta |
| `dayProfiles` | Per feriale, sabato e festivo, le fasce orarie in cui si aggiungono corse |

### Cadenza

Il parametro dello scenario è la cadenza di punta: 20, 15 o 10 minuti. Nell'orario
reale ogni linea S passa ogni 30 minuti.

| Livello della fascia | Cadenza applicata |
|---|---|
| `peak` | quella scelta |
| `offPeak` | un gradino più rada: 10 → 15, 15 → 20, 20 → nessuna corsa aggiunta |
| fuori dalle fasce | nessuna corsa aggiunta |

### Valori provvisori

- Intervallo minimo nel tunnel, 180 s.
- Fasce orarie e livelli per tipo di giorno, definiti in assenza di conteggi
  dei passeggeri.

Entrambi vanno confermati con la serie sperimentale registrata in
`docs/esperimenti`.