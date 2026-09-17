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
- **Binari (8, mappatura con la trunk da Domodossola)**:
  - **1–2**: innesto del **Passante** che arriva da Garibaldi (ramo Bovisa).
  - **3–4**: continuazione del **fascio 1** della trunk (= binari 1–2 a
    Domodossola).
  - **5–6**: continuazione del **fascio 2** della trunk (= binari 3–4 a
    Domodossola).
  - **7–8**: **Passante, fissi verso Asso** (es. S2 → Seveso/Mariano).
  - **Flessibilità**: i binari **5–6 possono proseguire sia verso Asso sia
    verso Saronno** (scambi a nord della stazione: segnali/deviatoi 39, 48,
    49a/b su OpenRailwayMap); 1–2, 3–4 lato Saronno; 7–8 fissi lato Asso.
  - Innesto Passante visibile su OpenRailwayMap presso **P.M. Ghisolfa**
    (ramo Bovisa / ramo Certosa / Cintura).
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

### Regole di circolazione (valide su tutta la rete)

- **Binario unico — blocchi e precedenze**: le tratte monobinario si riservano
  **in blocco** tra due punti d'incrocio (stazioni con più binari); le
  precedenze avvengono in quelle stazioni. Caso reale di riferimento (fonte:
  Fabio): **Malnate ↔ Varese Nord** — tratto unico su linea altrimenti doppia,
  incroci gestiti nelle due stazioni ai capi. Nel modello: catene di link a
  capacità 1 tra punti d'incrocio condividono un unico `railsimResourceId`;
  arbitraggio FCFS del `TrainDisposition` (politiche di precedenza = leva
  sperimentale futura). Limite dichiarato: punti singoli più corti della
  spaziatura tra stazioni (es. ponti) sono invisibili alla granularità meso,
  trattabili spezzando il link se rilevanti.

- **Binario unico — consenso all'ingresso nel blocco**: oltre alla
  serializzazione del blocco, un treno entra in una tratta a binario unico solo
  se la stazione di incrocio all'altro capo ha un binario per lui e, quando lì
  c'è già (o sta arrivando) un treno del suo stesso verso, un ulteriore binario
  libero per il treno opposto: così l'incrocio resta possibile. È il consenso
  che il dirigente movimento dà prima di licenziare un treno sul binario unico.
  Nel modello: `SingleTrackDeadlockAvoidance.crossingTrackFree`, che tiene il
  conto dei treni presenti o diretti in ogni stazione meso con il lato da cui
  arrivano. Motivo: nella simulazione del 2026-09-18 due S7 nello stesso verso
  riempivano Villasanta (2 binari) e l'S7 opposto, già nel blocco successivo,
  non poteva più entrare: stallo per tutta la giornata (79 treni su 274
  bloccati su tratte a binario unico). Limite: la regola vale per le stazioni
  meso (un anello con più binari); nelle stazioni dettagliate decidono la
  gola e i binari.

- **Doppio binario — distanziamento a blocco automatico**: una tratta a doppio
  binario non tiene un solo treno per verso ma uno per sezione di blocco. Sulle
  linee RFI e Ferrovienord il blocco automatico (BAcc) ha sezioni da 900 a
  1350 m e ammette il treno seguente appena la sezione dietro al primo è libera
  (fonti: Wikipedia, "Blocco elettrico automatico a correnti codificate";
  Ferrovienord, "Istruzione per l'esercizio con sistema di blocco elettrico
  automatico", ed. 2019). Nel modello (`MesoNetworkEnricher`): capacità per
  verso = binari per verso × ⌊lunghezza / 2,7 km⌋, minimo 1, cioè un treno
  ogni due sezioni da 1350 m (quella occupata e quella di distanziamento).
  Motivo: con un treno per verso, tratte da 30–50 km (Treviglio–Brescia,
  Monza–Lecco) fermavano in stazione il secondo treno, che occupava il binario
  e accodava tutta la linea (simulazione del 2026-09-18: 112 treni su 274
  bloccati per questo). Valore provvisorio: le lunghezze reali delle sezioni
  per linea non sono state rilevate. Le tratte sotto i 2,7 km (Passante,
  Garibaldi–Centrale) restano a un treno per verso.

- **Binario di stazione e sorpassi**: ogni corsa ha il binario abituale del
  piano di utilizzo (`data/nodes/*.json`, assegnato dal `PlatformPlanner`). Se
  all'arrivo quel binario è occupato, railsim può deviare il treno su un altro
  binario della stazione, ma solo fra quelli che il treno può fisicamente usare:
  i gruppi collegati sia alla stazione da cui arriva sia a quella verso cui
  riparte, nel suo verso di marcia (`TransitScheduleBuilder.platformFacility`,
  `PlatformPlanner.groupsConnecting`). È il sorpasso reale: un RE1 supera un
  R22 in ritardo a Saronno dove il piazzale lo consente (osservazione di Fabio
  come passeggero), mentre a Bovisa i binari del Passante e quelli per Cadorna
  portano in direzioni diverse e non si scambiano. Motivo: nella simulazione
  del 2026-09-18 RE51 e RE54 a Saronno avevano un solo binario ammesso per
  verso; un treno in sosta oltre l'orario fermava tutti quelli dietro.

- **Circolazione a SINISTRA**: i treni tengono la sinistra (contrario delle
  auto). Determina l'assegnazione binario→direzione in ogni fascio a doppio
  binario.
- **Stazioni terminali** (Cadorna, Centrale, Garibaldi binari 1–14…): i treni
  sono **bidirezionali (2 teste)**, non si girano — **ripartono dallo stesso
  binario da cui sono arrivati** (inversione banco). Nel modello: il binario
  terminale è una risorsa con ingresso e uscita dallo stesso lato, occupata per
  l'intera sosta di capolinea.

### 3. Trunk FNM Cadorna–Bovisa–Saronno — 4 binari, 2 fasci fissi

- **Ruolo**: dorsale FNM da Cadorna verso il nord-ovest.
- **Milano Cadorna — testata (10 binari terminali)**:
  - **Binario 1**: **fisso Malpensa Express** (arriva e riparte da lì).
  - **Binari 2–5** (2 coppie): **fissi verso Saronno** (poi diramazioni
    Como / Varese / Novara).
  - **Binari 6–10**: **verso Asso**.
  - **Confluenza 10→4** in uscita: binari **1–5 → primi 2** binari della trunk
    (fascio 1), binari **6–10 → ultimi 2** (fascio 2). I 4 binari risultanti
    sono gli stessi che servono la fermata successiva **Milano Domodossola**.
- **Binari**: **4**, in **2 fasci fissi**:
  - **Fascio 1** (bin. 1–2): treni verso **Varese / Como / Malpensa / Novara**
    (regionali), in partenza da Cadorna.
  - **Fascio 2** (bin. 3–4): a **Bovisa** si mischiano coi binari del Passante; si
    separano poi **verso Asso**. Portano i treni **arrivati da Bovisa / dal
    Passante** (da nord: Garbagnate, Novate…), **non** partiti da Cadorna.
- **Scambi**: **molti crossover tra i fasci**, concentrati **in prossimità
  delle stazioni** e **in uscita da Cadorna**; in piena linea i fasci viaggiano
  segregati. → nel modello: risorse di conflitto (scambi) alle gole di
  stazione, tratte di linea come risorse separate per fascio.
- **Direzionalità**: dentro ogni fascio i binari sono **rigidamente
  monodirezionali** (circolazione a sinistra, niente banalizzazione): il veloce
  resta dietro il lento fino a un punto di precedenza — è il meccanismo del
  fenomeno osservato.
- **Bovisa → Saronno**: restano **4 binari fino a Saronno** (quadruplicazione
  continua), con segregazione **lenti/veloci**: binari **1–2 = suburbani**
  (S1/S3, fermano in tutte le stazioni), binari **3–4 = regionali in transito**
  (da Bovisa a Saronno non fermano). A **Saronno** convergono inoltre **altri
  binari che non arrivano da Cadorna** (rami Como/Varese/Novara/Seregno).
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

### Parco rotabile reale (fonte: Fabio, 2026-08-05; sigle verificate su fonti pubbliche)

- **Suburbani**: **TAF** e soprattutto **TSR**; su alcune linee stanno entrando i **Caravaggio** (Hitachi); su **S9 e S19** anche **ETR 245** (Alstom Coradia Meridian, 5 casse, 82,2 m, 230 posti, vmax 160 km/h).
- **Regionali**: principalmente **Caravaggio**; in alcuni orari anche ETR 245, TSR e TAF. **Donizetti** (ETR 204, Alstom Coradia Stream) solo su alcune linee, es. quella per **Pavia**.
- **Diesel** (S7 e regionale Como–Molteno–Lecco): **ATR 125**.
- **Malpensa Express**: solo i **nuovi Caravaggio**.
- Nota modellazione: l'assegnazione reale varia per orario/turno; la v1 usa il tipo **dominante per rotta** (semplificazione dichiarata), i mix diventano parametro di scenario negli esperimenti.

#### Dati tecnici dalla pagina ufficiale flotta Trenord (consultata 2026-08-05)

Fonte: `https://www.trenord.it/chi-siamo/la-flotta/`.

| Rotabile | Dati dichiarati |
|---|---|
| **TSR** | elettrotreno 2 piani, modulare 3–6 casse, **298–640 posti**, vmax **140 km/h**, dal 2007 su **S1, S2, S5, S6, S13 del Passante** + regionali; «**accelerazioni simili a quelle di una metropolitana**» |
| **TAF** | elettrotreno 2 piani, composizione fissa **4 casse, 467 posti**, vmax **140 km/h**, fine anni '90; doppia composizione possibile (8 casse); «buone prestazioni in accelerazione» |
| **Caravaggio ETR 421** | 2 piani, **4 casse, 109,6 m, 466 posti** (infobox Wikipedia; altre fonti 443–479), vmax **160 km/h**, accel. max **1,10 m/s²**, 3400 kW — **Malpensa Express** in config. aeroportuale da marzo 2025 + regionali (fonte: Fabio + Wikipedia it `Elettrotreno_FS_ETR_421/521/521_S1/621`, 2026-08-05) |
| **Caravaggio ETR 521** | 2 piani, **5 casse, 136,8 m, 598 posti** (pagina flotta Trenord: 563), vmax **160 km/h**, accel. max **1,10 m/s²** — **S11** (limite sagoma: attestato a Como S.G.) e **Milano–Saronno–Varese–Laveno (RE1)** da set. 2022 + regionali; 70 unità 521 S1 |
| **Donizetti** | vmax **160 km/h**, >300 posti (4 casse), >200 (3 casse) |
| **ETR 425 Coradia Meridian** | regionale, vmax **160 km/h** |
| **ETR 245 Coradia Meridian** | **5 casse, 82,2 m, 230 posti**, vmax **160 km/h**, dal 2011, doppia composizione possibile (la pagina lo associa al Malpensa Express; oggi su MXP girano i nuovi Caravaggio e gli ETR 245 anche su S9/S19 — fonte: Fabio) |
| **ATR 125** (GTW 4/12 «Besanino») | diesel-elettrico Stadler, **4 casse, 231 posti**, su **Milano–Molteno–Lecco (S7)**; vmax **140 km/h** |
| **ATR 115** (GTW 2/6) | diesel-elettrico Stadler, **2 casse, 104 posti**, su Brescia–Iseo–Edolo e Como–Lecco; vmax **140 km/h** |
| **ALn 668** | automotrice diesel, 68 posti, vmax 95–130 km/h |
| **Colleoni** | diesel nuova generazione, 3 casse, **168 posti**; «+20% in accelerazione» rispetto alla flotta attuale |

- **FLIRT TSI** (TILO RABe 524, per RE80): 6 casse, **105 m, 244 posti**, vmax
  **160 km/h**, 2.600 kW (trainswiss/Wikipedia/sguggiari.ch, 2026-08-05). Il
  RE80 usa Flirt TSI + Flirt 4/6; la v1 modella **solo il Flirt TSI**
  (semplificazione dichiarata, fonte: Fabio).
- **Mancano dalla fonte ufficiale**: accelerazione/decelerazione in m/s²
  (`railsimAcceleration`/`railsimDeceleration`) e lunghezze di
  TSR/TAF/Caravaggio/Donizetti → da schede tecniche costruttori, con fonte
  citata e approvazione prima dell'uso.

#### Assegnazione rotta→tipo v1 (fonte: Fabio, 2026-08-05)

- **Suburbani** (S1–S13 tranne S7/S11, incluse S9/S19): sempre **TSR o TAF**,
  con **alternanza per corsa** al **70% TSR / 30% TAF** (stima di dominio,
  fonte: Fabio 2026-08-05 — i TAF sono in dismissione; parametro di scenario
  regolabile); l'ETR 245 appare solo saltuariamente su S9/S19 (non
  modellato in v1). Su S3 anche Caravaggio (non dominante).
- **S11**: `caravaggio_521` (attestato Como S.G.). **S7**: `atr125`.
- **RE1** (Laveno): `caravaggio_521`. **RE54/MXP**: `caravaggio_421`.
- **Linee per Pavia** (R34…): `donizetti`. **Altri R/RE**: `caravaggio_521`
  (dominante dichiarato: "regionali principalmente Caravaggio").
- **TILO S10/S30/S40/S50: esclusi dalla v1** (non confluiscono sulla rete
  suburbana); **RE80 incluso** con tipo `tilo_flirt_tsi`.

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
