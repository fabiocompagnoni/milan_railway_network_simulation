# Infrastruttura del nodo ferroviario di Milano — note per il modello

> Documento di lavoro. Raccoglie la conoscenza del sistema reale (topologia, tipi
> di treno, colli di bottiglia) che alimenta la costruzione della rete railsim.
> **Stato: in costruzione.** Branch: `feature/network`.
>
> Convenzione: i valori marcati `[da OSM]` / `[da GTFS]` vanno riempiti coi dati
> veri estratti, NON inventati (regola di correttezza).

## Filosofia di modellazione

- **Hybrid micro–meso** (come il paper railsim): **micro** (binari/scambi reali)
  sui nodi critici dove il vincolo di capacità morde; **meso** (link con capacità
  = numero di binari) sul resto.
- **Realistico ma non 1:1**: si modellano i nodi che generano il fenomeno; le
  stazioni minori/irrilevanti si semplificano o si lasciano fuori.
- **Correttezza per validazione incrociata**: `distanza OSM ↔ maxspeed OSM ↔
  tempo GTFS` devono concordare. Il GTFS è l'oracolo (tempi reali).
- **Traffico eterogeneo** = il fenomeno centrale: veloci (AV, RE) e lenti
  (suburbani) sugli stessi binari → propagazione dei ritardi e capacità che limita
  l'aumento di cadenza.

## Il fenomeno (perché questi nodi contano)

Traffico eterogeneo su capacità condivisa → un treno veloce resta dietro un lento o
un treno in ritardo → la catena dei ritardi si allunga. Evidenza diretta
(pendolare): un **RE1** (regionale veloce) fermo a stazioni a nord di Milano dietro
suburbani lenti / in ritardo o altri regionali in ritardo.

## Topologia del modello (grafo dei nodi)

Scheletro attorno a cui costruire la rete: la **spina del Passante** (meso) più gli
**8 nodi** di imbocco (micro), per direzione.

- **Spina Passante (meso, doppio binario condiviso da 6 S)**:
  Garibaldi (sott.) → Repubblica → P.ta Venezia → Dateo → P.ta Vittoria → Rogoredo.
- **Nord / NW**: le diramazioni convergono a **Bovisa** → o nel Passante (via
  Garibaldi) o verso **Cadorna** sulla trunk **FNM Cadorna–Bovisa–Saronno**
  (4 binari, 2 fasci) → a **Saronno** si dirama (Varese/Como/Novara/Malpensa).
- **Ovest**: **Rho Fiera** (linee Torino AV/AC + Torino conv. + Domodossola).
- **NE**: **Greco Pirelli** (Lecco/Chiasso/Cintura + merci); la **S7 diesel** corre
  con gli elettrici fino a Monza, poi 2 binari dedicati non elettrificati.
- **Est**: **Segrate / Bivio Lambro** (Venezia + Verona AV/AC + Passante ramo
  Pioltello + Cintura merci) → entrando in Milano si divide verso Passante /
  Centrale / linee merci sud.
- **Sud**: **Rogoredo** (Bologna AV/AC + Genova + ramo Rogoredo del Passante +
  Cintura) → divisione **Genova (SW)** vs **Lodi/Bologna (SE)**. Unico ingresso sud.
- **Garibaldi ↔ Centrale — doppio collegamento** (~2 km): (1) **link diretto** con
  **2 binari** e **limite di velocità basso**, usato da **pochi** treni (es.
  **Malpensa Express**); (2) **via lunga** — i suburbani in genere risalgono verso
  **Bovisa** e raggiungono Centrale via **Cintura**. Il link diretto è
  **infrastruttura sottoutilizzata** (candidato per scenari alternativi).

## Nodi da modellare MICRO — dettaglio stazioni chiave

Schema per nodo: **Ruolo · Binari · Traffico · Connessioni · Assegnazioni ·
Criticità**.

### 1. Bovisa — cardine nord

- **Ruolo**: bivio di smistamento; le diramazioni nord convergono qui.
- **Binari**: fascio a ventaglio `[da OSM]`.
- **Traffico**: suburbani nord (S1/S2/S3/S4/S12/S13…), regionali FNM.
- **Connessioni**: → Passante (via Garibaldi) **oppure** → Cadorna (linee FN).
  Punto dove il fascio FNM "Passante-connesso" tocca i binari del Passante.
- **Criticità**: nodo di decisione dove due mondi (Passante / FNM-Cadorna) si
  intrecciano.

### 2. Milano Porta Garibaldi


- **Ruolo**: **la stazione chiave dell'intera rete suburbana**; vi transita gran
  parte del traffico ferroviario di Milano. Nodo prioritario da studiare a fondo.
- **Binari**: **22** totali (non tutti usati dai suburbani).
  - **2 sotterranei** dedicati al **Passante** → verso **Bovisa** / **Repubblica**.
  - **~20 di superficie**, divisi per funzione:
    - **1–14: terminali** (tronchi) — i treni **o partono o arrivano**, nessun
      transito. → nel modello: risorse di binario con ingresso/uscita dallo
      stesso lato, occupazione per sosta capolinea (inversione banco).
    - **15–20: passanti** — consentono il transito.
    - Tra i passanti, **4 confluiscono in 2** verso **Lecco** (→ Greco Pirelli):
      la confluenza **4→2** è un **collo di bottiglia** esplicito.
- **Traffico**:
  - *Sotterraneo*: le 6 S del Passante.
  - *Superficie*: suburbani verso **Lecco** (via Greco), verso **Certosa /
    Villapizzone**, linea verso **Domodossola**, e **quasi tutti** i suburbani; più
    regionali / lunga percorrenza terminanti.
- **Connessioni**: Bovisa / Repubblica (Passante, sott.), Greco–Lecco, Certosa,
  Domodossola (superficie).
- **Criticità**: **la più critica della rete** — è qui che si concentrano più colli
  di bottiglia. Da modellare con cura: superficie vs sotterraneo, confluenza **4→2**
  verso Lecco, assegnazioni di binario. `[dettaglio binari da OSM]`

### 3. Trunk FNM Cadorna–Bovisa–Saronno — 4 binari, 2 fasci fissi

- **Ruolo**: dorsale FNM da Cadorna verso il nord-ovest.
- **Binari**: **4**, in **2 fasci fissi**:
  - **Fascio 1** (bin. 1–2): treni verso **Varese / Como / Malpensa / Novara**
    (regionali), in partenza da Cadorna.
  - **Fascio 2** (bin. 3–4): a **Bovisa** si mischiano coi binari del Passante; si
    separano poi **verso Asso**. Portano i treni **arrivati da Bovisa / dal
    Passante** (da nord: Garbagnate, Novate…), **non** partiti da Cadorna.
- **Traffico**: regionali (Varese/Como/Malpensa/Novara) + suburbani.
- **Connessioni**: Cadorna ↔ Bovisa ↔ Saronno; fascio 2 ↔ Passante (a Bovisa).
- **Criticità**: **satura** dai regionali → margine minimo per aumentare la cadenza
  dei suburbani. ⚠️ **Caveat orario**: filtrare i treni per **attraversamento del
  segmento** (sequenza fermate), NON per partenza da Cadorna (si perdono i treni
  del fascio 2).

### 4. Saronno — nodo FNM

- **Ruolo**: nodo di diramazione FNM.
- **Binari**: `[da OSM]`.
- **Traffico**: diramazioni Varese / Como / Novara / Malpensa + suburbani.
- **Assegnazioni fisse**: ultimo binario sempre **S9** (mono-binario **dedicato**
  per gran parte del percorso → quasi mai in conflitto, salvo alcune stazioni);
  anche **S1** con banchina dedicata. → questi binari dedicati **riducono** il
  conflitto (risorse separate).
- **Criticità**: nodo di ingresso dei rami nord-ovest.

### 5. Rho Fiera — 6 binari, eterogeneità estrema

- **Ruolo**: convergenza ovest ad altissima eterogeneità.
- **Binari**: **6**.
- **Traffico**: **AV** (~300 km/h: Frecciarossa, Italo, TGV) + regionali Trenord +
  regionali Trenitalia + suburbani (S5, S6, S11).
- **Connessioni**: **Torino–Milano AV/AC** + **Torino–Milano** conv. +
  **Domodossola–Milano**.
- **Criticità**: 6 binari, troppi treni → ritardi cronici. Il caso di traffico
  misto più estremo (Δvmax AV↔suburbano enorme).

### 6. Milano Greco Pirelli — snodo NE

- **Ruolo**: imbocco nord per i treni che **NON** passano da Bovisa (complementare
  a Bovisa).
- **Binari**: assegnati **per direzione**; più a nord si riducono a **4** `[da OSM]`.
- **Traffico**: treni da **Milano Centrale** + da **Garibaldi** + lunga percorrenza
  (**Genova, Svizzera**…) + **molti merci**. Molto **saturo**.
- **Connessioni**: Lecco / Chiasso / Cintura di Milano.
- **Criticità — trazione mista**: la **S7** (Milano–Monza–Molteno–Lecco) è **NON
  elettrificata** (diesel), ma corre con gli elettrici **fino a Monza**; poi 2
  binari dedicati non elettrificati. → asse di eterogeneità *trazione*.

### 7. Segrate / Bivio Lambro — approccio est

- **Ruolo**: imbocco est; a ovest (Bivio Lambro) i binari si moltiplicano e i flussi
  si dividono.
- **Binari**: a **Segrate** solo **2 con fermata** (banchina); gli altri di
  **transito**. A est grande fascio/snodo **merci**. Verso Milano il numero
  **aumenta molto** `[da OSM]`.
- **Traffico**: **RE** (Milano–Venezia), **AV** (Milano–Verona AV/AC), **S**
  (Passante ramo Pioltello), **merci** (Cintura).
- **Connessioni (divergenza a Bivio Lambro)**: → Passante · → Centrale · → linee
  merci sud · → si uniscono al Passante che arriva da **nord** (Greco).
- **Criticità**: convergenza multi-tipologia molto satura.

### 8. Rogoredo — porta sud (unico ingresso da sud)

- **Ruolo**: **unico** punto di ingresso da sud.
- **Binari**: `[da OSM]` + grande **scalo merci**.
- **Traffico**: **AV** (Milano–Bologna), treni dalla **Liguria** (Genova),
  **suburbani verso Lodi**, **regionali verso Pavia** (linea per Genova), **merci**.
- **Connessioni**: divisione a sud **Genova (SW)** vs **Lodi/Bologna (SE)**; qui
  **termina il Passante** (ramo Rogoredo) → chiude la spina Garibaldi→Rogoredo;
  convergono le linee di **Cintura** (Centrale–Rogoredo, Greco–Rogoredo, sud, merci).
- **Criticità**: l'**AV Milano–Bologna** si mischia sui binari condivisi coi treni
  per Centrale che arrivano da est.

### Stazioni minori del Passante — alto conflitto (sulla spina meso)

- Repubblica, Lancetti, P.ta Venezia, Dateo, P.ta Vittoria: quasi **tutte le 6 linee
  S sui 2 binari** → qui si concentra il conflitto sul Passante. Da trattare con
  attenzione anche se appartengono alla parte meso.

## Parti MESO

- Tunnel del Passante tra le stazioni (Garibaldi → Rogoredo): risorsa a doppio
  binario condivisa dalle 6 linee S.
- Diramazioni esterne: link con capacità = numero di binari.

## Traffico da includere (non solo le 13 S)

Per la correttezza del fenomeno servono anche:

- **Regionali** (R / RE) che saturano le trunk (Cadorna/Saronno, Rho).
- **Alta velocità** (Frecciarossa / Italo / TGV) a Rho Fiera e Rogoredo.
- **Merci** (Greco Pirelli, Segrate, Rogoredo, Cintura di Milano).

Cioè il traffico **eterogeneo** completo sui segmenti condivisi.

### Assi di eterogeneità (per i `vehicleType` railsim)

1. **Missione**: suburbano che ferma ovunque vs veloce (RE/AV) che salta → vmax e
   fermate diverse.
2. **Materiale rotabile**: moderno vs TAF (vecchio, accel/decel peggiori).
3. **Trazione**: **elettrico vs diesel** (es. S7) → prestazioni/accelerazione
   diverse sugli stessi binari.

## Percorsi alternativi e leve di scenario

Oltre a riprodurre i **percorsi reali** (baseline), il modello può simulare
**percorsi alternativi** (controfattuali) per vedere se la rete **migliora** — è la
leva "cosa succede SE cambio l'instradamento?", coerente col framing
simulazione/confronto-scenari (NON ottimizzazione).

- **Esempio — link diretto Garibaldi–Centrale**: instradare più treni sul link
  diretto (oggi quasi solo Malpensa Express) invece della via lunga via Bovisa →
  verificare se allevia i colli di bottiglia. **Vincolo reale**: solo 2 binari e
  vmax bassa → potrebbe non reggere più traffico (da testare in simulazione).
- railsim supporta il **rerouting** (link `railsimEntry`/`railsimExit`,
  `TrainRouter`, `RailsimDetourEvent`) → i percorsi alternativi sono modellabili.

## Linee S in scope (13, milanesi)

S1, S2, S3, S4, S5, S6, S7, S8, S9, S11, S12, S13, S19.

## Dati da estrarre per costruire (schema)

Per ogni **nodo/segmento**, popolare con i dati veri:

| Campo | Fonte | Uso railsim |
|---|---|---|
| Stazioni ordinate per linea | GTFS (`stop_times`) | nodi + sequenza link |
| Linee/treni che attraversano il segmento | GTFS (per **attraversamento**, non origine) | traffico misto sul link |
| Distanza reale tra fermate | OSM (geometria, linear referencing) | `length` |
| Velocità di linea | OSM (`maxspeed`) | `freespeed` |
| Tempo di percorrenza + dwell | GTFS | **validazione** (oracolo) |
| Numero di binari / fasci | OSM (way paralleli) + conoscenza diretta | `railsimTrainCapacity` / `railsimResourceId` |
| Assegnazioni linea→binario | conoscenza diretta + OSM | risorse dedicate vs condivise |

### Punti ancora aperti

- Conteggio esatto binari e assegnazioni linea→binario per Bovisa, Garibaldi,
  Saronno, Greco, Rogoredo (`[da OSM]`).
- Elenco preciso dei regionali / AV / merci per segmento (via attraversamento GTFS).
- Confini esatti del modello (quali stazioni dentro / fuori).
