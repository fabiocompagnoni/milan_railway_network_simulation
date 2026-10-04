# Materiale rotabile — dati tecnici

Dati tecnici dei treni rappresentati nel modello, con la fonte di ogni valore.
Integra la sezione "Parco rotabile" di `infrastruttura-nodo-milano.md`, che
documenta i parametri usati dal motore di simulazione (lunghezza, posti,
velocità massima, accelerazione); qui sono raccolti massa, potenza e
composizione, necessari al calcolo dei consumi energetici.

I numeri fra parentesi quadre rimandano ai [Riferimenti](#riferimenti).

## Quadro riassuntivo

| Treno | Codice | Costruttore | Trazione | Composizione | Lunghezza | Posti a sedere | Massa a vuoto | Potenza |
|---|---|---|---|---|---|---|---|---|
| Caravaggio | ETR 521 | Hitachi Rail | elettrica, 3 kV c.c. | 5 casse a due piani | 136,8 m | 598 | 325 t ¹ | 3400 kW |
| Caravaggio | ETR 421 | Hitachi Rail | elettrica, 3 kV c.c. | 4 casse a due piani | 109,6 m | 466 | 261 t ¹ | 3400 kW |
| TSR | EB 711 + EB 710 | AnsaldoBreda | elettrica, 3 kV c.c. | 4 casse a due piani | 104,97 m | 436 | 222 t | 2720 kW |
| TAF | EB 760 + EB 990 + EA 761 | AnsaldoBreda | elettrica, 3 kV c.c. | 4 casse a due piani | 103,95 m | 468 | 213 t | 3640 kW |
| Donizetti | ETR 204 | Alstom | elettrica, 3 kV c.c. | 4 casse a un piano | 84,2 m | 259 | 143,5 t | 2000 kW |
| FLIRT TILO | ETR 524 (RABe 524.3) | Stadler | elettrica, 3 kV c.c. e 15 kV 16,7 Hz | 6 casse a un piano | 104,9 m | 244 | 177,5 t | 2600 kW |
| GTW 4/12 | ATR 125 | Stadler | diesel-elettrica | 4 casse e 2 moduli motore | 77,33 m | 231 | 136 t | 1160 kW alle ruote |
| Colleoni | ATR 803 | Stadler | diesel-elettrica con batterie | 3 casse e 1 modulo motore | 66,8 m | 151 | 135 t | 1200 kW |

¹ Valore stimato: vedi [Valori stimati](#valori-stimati).

## Schede

### Caravaggio — ETR 421 e ETR 521

Elettrotreni a due piani per il servizio regionale ad alta capacità. Le due
casse di estremità sono motrici, con entrambi i carrelli motori; le casse
intermedie sono rimorchiate.

| Dato | ETR 421 | ETR 521 | Fonte |
|---|---|---|---|
| Costruttore | Hitachi Rail | Hitachi Rail | [1] |
| Casse | 4 | 5 | [1] |
| Rodiggio | Bo′Bo′+2′2′+2′2′+Bo′Bo′ | Bo′Bo′+2′2′+2′2′+2′2′+Bo′Bo′ | [1] [2] |
| Lunghezza | 109,6 m | 136,8 m | [1] [2] |
| Lunghezza delle casse | motrici 27,6 m, rimorchiate 27,2 m | motrici 27,6 m, rimorchiate 27,2 m | [3] |
| Alimentazione | 3 kV c.c. | 3 kV c.c. | [1] |
| Potenza oraria | 3400 kW | 3400 kW | [1] |
| Accelerazione massima | 1,10 m/s² | 1,10 m/s² | [1] |
| Velocità massima | 160 km/h | 160 km/h | [1] |
| Massa di una motrice | 74 t | 74 t | [3] |
| Carico assiale delle motrici | 18,5 t | 18,5 t | [3] |
| Posti a sedere | 466 | 598 | [1] |
| Posti a sedere, allestimento Trenord | 443 | 573 | [2] |
| Unità consegnate a Ferrovienord | 40 | 60 | [2] |

### TSR — Treno Servizio Regionale

Elettrotreno a due piani a composizione variabile, da due a sei casse, tutte
motrici. Il modello rappresenta la composizione a quattro casse: due motrici
con cabina alle estremità e due motrici intermedie.

| Dato | Motrice con cabina | Motrice intermedia | Fonte |
|---|---|---|---|
| Sigla Ferrovienord | EB 711 | EB 710 | [5] |
| Sigla Trenitalia | ALe 711 | ALe 710 | [5] |
| Massa a vuoto | 58 t | 53 t | [4] |
| Massa in servizio | 71 t | 68 t | [4] [5] |
| Lunghezza | 26,460 m | 26,025 m | [5] |
| Potenza continuativa | 680 kW | 680 kW | [4] [5] |
| Potenza oraria | 750 kW | 750 kW | [4] |

| Dato | Convoglio a 4 casse | Origine |
|---|---|---|
| Massa a vuoto | 222 t | 2 × 58 t + 2 × 53 t |
| Massa in servizio | 278 t | 2 × 71 t + 2 × 68 t |
| Lunghezza | 104,97 m | 2 × 26,460 m + 2 × 26,025 m |
| Potenza continuativa | 2720 kW | 4 × 680 kW |
| Potenza oraria | 3000 kW | 4 × 750 kW |

La lunghezza calcolata coincide con quella del modello (104,98 m). In
esercizio la composizione più frequente è di otto casse, ottenuta accoppiando
un convoglio da cinque e uno da tre [5].

### TAF — Treno ad Alta Frequentazione

Elettrotreno a due piani a composizione bloccata: una motrice, due
rimorchiate e una motrice attrezzata per i viaggiatori con disabilità. I dati
di massa provengono dalle norme di circolazione emanate da RFI per i convogli
di Ferrovie Nord Milano [6].

| Dato | EB 760 (motrice) | EB 990 (rimorchiata) | EA 761 (motrice) | Fonte |
|---|---|---|---|---|
| Unità nel convoglio | 1 | 2 | 1 | [6] |
| Massa a vuoto | 62 t | 44 t | 63 t | [6] |
| Carico normale e massimo | 14 t | 18 t | 13 t | [6] |
| Viaggiatori a pieno carico | 178 | 251 | 160 | [6] |
| Posti a sedere | 98 | 144 | 82 | [6] |
| Lunghezza | 25,885 m | 26,090 m | 25,885 m | [7] |

| Dato | Convoglio | Fonte |
|---|---|---|
| Massa a vuoto | 213 t | [6] |
| Carico massimo | 63 t | [6] |
| Massa a pieno carico | 276 t | [6] |
| Viaggiatori a pieno carico | 840 | [6] |
| Posti a sedere | 468 | [6] |
| Lunghezza | 103,95 m | [7] |
| Potenza | 3640 kW | [8] |
| Velocità massima | 140 km/h | [6] |
| Frenatura | elettrodinamica e pneumatica a dischi | [6] |
| Convogli | 27, numerati 001 ÷ 027 | [6] |

I convogli da 6 a 10 hanno un allestimento con 415 posti a sedere [6]. Il
carico massimo di 63 t per 840 viaggiatori corrisponde a 75 kg per persona.

### Donizetti — ETR 204

Elettrotreno a un piano per il servizio regionale a media capacità, della
famiglia Alstom Coradia Stream; è la versione per Ferrovienord del treno
"Pop" ETR 104 di Trenitalia.

| Dato | Valore | Fonte |
|---|---|---|
| Costruttore | Alstom | [2] |
| Casse | 4 | [2] |
| Rodiggio | Bo′+2′2′+2′2′+2′2′+Bo′ | [2] |
| Lunghezza | 84,2 m | [2] |
| Alimentazione | 3 kV c.c. | [2] |
| Massa | 143,5 t | [9] |
| Potenza | 2000 kW | [9] |
| Motori di trazione | 4 | [10] |
| Velocità massima | 160 km/h | [2] [10] |
| Posti a sedere | 259, di cui 16 di prima classe | [2] |
| Unità consegnate a Ferrovienord | 31 | [2] |

### FLIRT TILO — ETR 524 (RABe 524.3)

Elettrotreno bitensione Stadler FLIRT 3 conforme alle specifiche tecniche di
interoperabilità, in servizio sulle relazioni transfrontaliere TILO.

| Dato | Valore | Fonte |
|---|---|---|
| Costruttore | Stadler Rail | [11] |
| Numerazione | 524 301–305 (FFS), 524 306–314 (FNM) | [11] |
| Anni di costruzione | 2019–2021 | [11] |
| Casse | 6 | [11] |
| Lunghezza | 104,9 m | [11] |
| Alimentazione | 3 kV c.c. e 15 kV 16,7 Hz | [12] |
| Massa a vuoto | 177,5 t | [11] |
| Massa complessiva | 210 t | [11] |
| Potenza massima | 2600 kW | [11] |
| Sforzo di trazione | 200 kN | [11] |
| Motori di trazione | 4 | [11] |
| Velocità massima | 160 km/h | [11] |
| Posti a sedere | 244 | [11] |

### GTW 4/12 — ATR 125

Automotrice articolata diesel-elettrica a piano ribassato, formata da due
unità accoppiate, ciascuna con due casse e un modulo motore centrale. In
servizio sulla Milano – Molteno – Lecco. I dati provengono dalla scheda
tecnica del costruttore per Ferrovienord [13].

| Dato | Valore | Fonte |
|---|---|---|
| Costruttore | Stadler Bussnang | [13] |
| Rodiggio | 2′Bo′2′ + 2′Bo′2′ | [13] |
| Lunghezza ai respingenti | 77,33 m | [13] |
| Massa totale in ordine di marcia | 136 t (2 × 68 t) | [13] |
| Motori diesel | 4 × 382 kW, common-rail, Euro IIIA | [13] |
| Potenza massima alla ruota | 1160 kW (4 × 290 kW) | [13] |
| Sforzo di trazione fino a 47 km/h | 160 kN (4 × 40 kN) | [13] |
| Velocità massima | 140 km/h | [13] |
| Posti a sedere | 231, più 12 ribaltabili | [13] |
| Unità | 11, in servizio dal 2011 | [13] |

Il rapporto fra potenza alla ruota e potenza dei motori diesel è 1160 / 1528
= 0,76. La trasmissione diesel-elettrica consente il recupero di energia in
frenatura [13].

### Colleoni — ATR 803

Treno diesel-elettrico Stadler FLIRT con batterie, con i gruppi di
generazione in un modulo motore al centro del convoglio.

| Dato | Valore | Fonte |
|---|---|---|
| Costruttore | Stadler | [14] |
| Casse | 3, più il modulo motore | [14] |
| Lunghezza | 66,8 m | [14] [15] |
| Massa | 135 t | [14] |
| Potenza | 1200 kW, due motori diesel | [14] |
| Carico massimo per asse | 16,3 t | [15] |
| Sforzo di trazione all'avviamento | 160 kN | [15] |
| Velocità massima | 140 km/h | [15] |
| Posti a sedere | 151, più 17 strapuntini | [15] |
| Autonomia a sole batterie | 9 km | [15] |

## Valori stimati

La massa a vuoto dei Caravaggio non è pubblicata. È stimata dal carico
assiale di 18,5 t dichiarato per le motrici [3], esteso a tutti gli assi del
convoglio per ottenere la massa a pieno carico, da cui si sottrae il peso dei
viaggiatori seduti a 75 kg per persona [6].

| Treno | Assi | Massa a pieno carico | Posti a sedere | Viaggiatori | Massa a vuoto |
|---|---|---|---|---|---|
| ETR 421 | 16 | 296 t | 466 | 35 t | 261 t |
| ETR 521 | 20 | 370 t | 598 | 45 t | 325 t |

La stima è un limite superiore: le casse rimorchiate hanno verosimilmente un
carico assiale inferiore a quello delle motrici, e il pieno carico comprende
anche i viaggiatori in piedi. Un consumo calcolato con queste masse risulta
quindi sovrastimato, non sottostimato.

Per confronto, la massa a vuoto per metro di lunghezza dei treni a due piani
con dato pubblicato è 2,1 t/m per il TSR e 2,0 t/m per il TAF; la stima dà
2,4 t/m per entrambi i Caravaggio.

## Differenze rispetto ai parametri del modello

| Treno | Parametro | Nel modello | Nelle fonti |
|---|---|---|---|
| ETR 421 | posti a sedere | 466 [1] | 443 nell'allestimento Trenord [2] |
| ETR 521 | posti a sedere | 598 [1] | 573 nell'allestimento Trenord [2] |
| Donizetti | posti a sedere | 262 | 259 [2] |
| TAF | posti a sedere | 469 | 468 [6] |
| ATR 803 | posti a sedere | 168, strapuntini compresi | 151 fissi [15] |
| TSR | composizione | 4 casse | variabile, in prevalenza 5 + 3 [5] |

## Riferimenti

1. Wikipedia, *Elettrotreno FS ETR 421, 521, 521 S1 e 621*.
   <https://it.wikipedia.org/wiki/Elettrotreno_FS_ETR_421,_521,_521_S1_e_621>
   (consultato il 4 ottobre 2026).
2. M. Fantini (Ferrovienord), *Elettrotreni Alta Capacità «Caravaggio» e
   Media Capacità «Donizetti»*, Collegio Ingegneri Ferroviari Italiani,
   Sezione di Milano, 18 febbraio 2021.
   <https://www.cifi.it/UplDocumenti/Milano18022021/03%20Fantini.pdf>
3. Scalaenne, *Doppio piano, parte 6: Rock / Caravaggio (ETR 421, 521, 621)*,
   31 ottobre 2020.
   <https://scalaenne.wordpress.com/2020/10/31/doppio-piano-parte-6-rock-caravaggio-etr-421-521-621/>
4. Wikipedia, *Treno Servizio Regionale*.
   <https://it.wikipedia.org/wiki/Treno_Servizio_Regionale>
   (consultato il 3 ottobre 2026).
5. Scalaenne, *Doppio piano, parte 5: TSR (Treno Servizio Regionale)*,
   24 ottobre 2020.
   <https://scalaenne.wordpress.com/2020/10/24/doppio-piano-parte-5-tsr-treno-servizio-regionale/>
6. Rete Ferroviaria Italiana, Direzione Tecnica, *Norme particolari per la
   circolazione dei complessi elettrici (TAF) EB 760 / EB 990 / EA 761
   (001 ÷ 027) sulla infrastruttura ferroviaria nazionale*, allegato alla
   disposizione n. 35 del 3 giugno 2005.
7. Scalaenne, *Treni ad Alta Frequentazione (TAF) a doppio piano: ALe 426 /
   Le 736 / ALe 506 FS, EA 761 / EB 990 / EB 760 FNM*, 19 marzo 2016.
   <https://scalaenne.wordpress.com/2016/03/19/treni-ad-alta-frequentazione-taf-a-doppio-piano-ale-426le-736ale-506-fs-ea-761eb-990eb-760-fnm/>
8. Wikipedia, *Treno ad alta frequentazione* (edizione inglese).
   <https://en.wikipedia.org/wiki/Treno_ad_alta_frequentazione>
   (consultato il 4 ottobre 2026).
9. Wikipedia, *Pop (train)* (edizione inglese).
   <https://en.wikipedia.org/wiki/Pop_(train)>
   (consultato il 3 ottobre 2026).
10. Trenitalia, *Pop: la nuova generazione dei treni regionali*, scheda
    tecnica, 7 novembre 2017.
    <https://www.cifi.it/UplDocumenti/Bologna15062108/SCHEDA_TECNICA_POP.pdf>
11. trainswiss (Macchinista.ch), *RABe 524 TSI «Flirt 3»*.
    <https://trainswiss.jimdoweb.com/ffs/ffs-viaggiatori/rabe-524-tsi-flirt-3/>
    (consultato il 4 ottobre 2026).
12. Railway Gazette International, *First TILO Flirt 3 enters service*.
    <https://www.railwaygazette.com/traction-and-rolling-stock/first-tilo-flirt-3-enters-service/57518.article>
13. Stadler Bussnang AG, *Automotrici articolate Diesel-elettriche GTW 2/6 e
    GTW 4/12 a piano ribassato per Ferrovienord, (Milano) Italia*, scheda
    tecnica GFNM0210i.
14. Railvolution, *First New FNM FLIRT DMU On Test*.
    <https://www.railvolution.net/news/first-new-fnm-flirt-dmu-on-test>
15. Wikipedia, *Autotreno ATR 803*.
    <https://it.wikipedia.org/wiki/Autotreno_ATR_803>
    (consultato il 3 ottobre 2026).
