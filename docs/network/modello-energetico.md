# Modello dei consumi energetici

Come la simulazione calcola l'energia elettrica e il gasolio consumati dai
treni, con i parametri usati, le fonti, le ipotesi e la verifica del
risultato. I numeri fra parentesi quadre rimandano ai
[Riferimenti](#riferimenti). I parametri sono nel file
`data/scenarios/consumi-energetici.json`; ogni run ne conserva la copia usata
(`energy-model.json`).

## 1. Impostazione

railsim non ha un modello di consumo: la specifica dei treni prevede solo
lunghezza, velocità massima, accelerazione e decelerazione [1]. Il consumo è
quindi calcolato dal progetto sul moto che railsim simula. Ogni 5 secondi di
tempo simulato si legge la velocità e l'accelerazione di ogni treno in corsa
e se ne ricava la potenza; l'energia è la potenza per il tempo.

Un treno è contato dalla partenza all'arrivo di ogni corsa, servizi ausiliari
compresi. Fra due corse è considerato spento.

## 2. Potenza di un treno

La potenza alle ruote è la forza per la velocità. La forza ha due termini:
quella che accelera il treno e quella che vince la resistenza al moto.

| Grandezza | Formula |
|---|---|
| Forza alle ruote | F = m · ρ · a + R(v) |
| Resistenza al moto | R(v) = (2,4 + 0,00077 · v²) · P / 1000, con v in km/h e P il peso in newton |
| Potenza alle ruote | W = F · v |

- m è la massa del treno con i viaggiatori, a l'accelerazione, ρ il
  coefficiente delle masse rotanti.
- La resistenza specifica 2,4 + 0,00077 · v² newton per kilonewton di peso è
  la formula binomia usata in [2] per i treni regionali.
- Pendenze e curve della linea non sono considerate.

Da qui la potenza scambiata dal treno:

| Caso | Treno elettrico | Treno diesel |
|---|---|---|
| Trazione (W > 0) | assorbe W / η_t più gli ausiliari | brucia (W / η_tr + ausiliari) / η_m |
| Frenata (W < 0) | restituisce \|W\| · η_r; assorbe gli ausiliari | brucia solo per gli ausiliari |
| Fermo | assorbe gli ausiliari | brucia solo per gli ausiliari |

## 3. Parametri

| Parametro | Valore | Fonte |
|---|---|---|
| Resistenza specifica al moto | 2,4 + 0,00077 · v² N/kN | [2] |
| Coefficiente delle masse rotanti ρ | 1,06 | [2]: 1,04 per le carrozze, 1,06 per le locomotive; adottato 1,06 per gli elettrotreni, che hanno i motori distribuiti |
| Rendimento di trazione elettrica η_t | 0,80 | [3]: perdite e ausiliari di trazione pari al 20–25% dell'energia assorbita nei treni moderni |
| Rendimento del recupero η_r | 0,80 | [3]: un treno moderno recupera il 60–70% dell'energia spesa per accelerare; 0,80 × 0,80 = 0,64 |
| Raggio entro cui l'energia di frenata è riusata | 10 km in linea d'aria | [4]: sottostazioni a 3 kV ogni 20–30 km, 15–20 km sulle linee più cariche; adottata metà della distanza minima |
| Rendimento della trasmissione diesel-elettrica η_tr | 0,76 | [5]: 1160 kW alla ruota su 1528 kW dei motori |
| Peso di un viaggiatore | 75 kg | [6]: 63 t di carico per 840 viaggiatori |
| Servizi ausiliari | 30 kW per cassa | ipotesi |
| Rendimento del motore diesel η_m | 0,40 | ipotesi |
| Energia di un litro di gasolio | 10 kWh | ipotesi (ordine di grandezza del potere calorifico) |
| Coefficiente di taratura del gasolio | 0,41 | taratura, vedi § 6 |

Massa a vuoto e numero di casse di ogni treno sono in
`materiale-rotabile.md`.

## 4. Massa e viaggiatori

La simulazione non ha viaggiatori. La massa di un treno è la massa a vuoto
più una quota dei posti a sedere occupati, a 75 kg per persona. La quota
dipende dal giorno e dall'ora di partenza prevista della corsa.

| Giorno | Fascia | Posti occupati |
|---|---|---|
| Feriale | 6:30–9:30 e 16:30–19:30 | 100% |
| Feriale | altre ore fra le 6:00 e le 21:00 | 50% |
| Sabato | fra le 6:00 e le 21:00 | 50% |
| Domenica | fra le 6:00 e le 21:00 | 40% |
| Tutti | prima delle 6:00 e dopo le 21:00 | 20% |

È un'ipotesi di progetto. Le festività infrasettimanali non sono distinte dai
feriali; i viaggiatori in piedi non sono contati.

## 5. Recupero dell'energia di frenata

In frenata i motori di un treno elettrico funzionano da generatori. Sulle
linee a 3 kV in corrente continua le sottostazioni non riprendono energia: la
corrente restituita resta sulla linea di contatto e serve solo se un altro
treno, nello stesso tratto, sta assorbendo potenza in quel momento.
Altrimenti la tensione sale e il treno la dissipa sui reostati di bordo [3].
RFI ha in corso un progetto sperimentale per il recupero dell'energia di
frenata nelle sottostazioni a 3 kV [7].

Il modello riproduce questo meccanismo. A ogni istante di campionamento
l'energia rigenerata da un treno:

1. alimenta i servizi ausiliari del treno stesso;
2. va ai treni elettrici in trazione entro 10 km, dal più vicino;
3. per la parte che nessuno assorbe, è dissipata.

La quota recuperata dipende quindi dalla densità del traffico: è alta dove i
treni sono vicini, quasi nulla sulle linee isolate. I treni diesel non
partecipano allo scambio.

La distanza è misurata in linea d'aria perché la simulazione conosce la
posizione dei treni, non lo schema elettrico della rete: due treni vicini su
linee elettricamente separate risultano collegati.

## 6. Taratura del consumo di gasolio

Senza correzioni il modello dà un consumo di gasolio più che doppio di quello
reale. Nel giorno feriale del 5 ottobre 2026 gli ATR 125 percorrono 7626
treni-km e il modello calcola 16 657 litri, cioè 2,18 l/km, contro un consumo
medio di esercizio di 0,9 l/km.

La differenza non viene dagli ausiliari: azzerandoli, il consumo dei treni
diesel resta 1,73 l/km. Viene dallo stile di guida di railsim, che porta ogni
treno alla velocità massima della tratta con l'accelerazione massima e lo
frena con la decelerazione massima a ogni fermata, senza marcia per inerzia.
Un treno elettrico recupera in frenata parte dell'energia spesa per
accelerare; un treno diesel la perde tutta, e l'eccesso resta nel conto.

Il consumo di gasolio è quindi moltiplicato per un coefficiente di taratura,
0,9 / 2,18 = 0,41, applicato a tutti i treni diesel. Il modello continua a
distinguere una corsa regolare da una con più fermate e ripartenze; il
coefficiente ne fissa la scala sul dato reale.

Il coefficiente è tarato sul run reale del 5 ottobre 2026: cambiando la rete,
l'orario o i parametri del modello va ricalcolato.

## 7. Verifica

Run reale di lunedì 5 ottobre 2026: 2349 corse, 343 treni.

| Misura | Valore |
|---|---|
| Energia presa dalle sottostazioni | 1800 MWh |
| Energia che servirebbe senza alcun recupero | 2167 MWh |
| Energia rigenerata in frenata | 483 MWh |
| di cui riusata da altri treni | 321 MWh |
| di cui dissipata | 116 MWh |
| Treni-km elettrici | 126 903 |
| Consumo per treno-km | 14,2 kWh |
| Consumo per tonnellata-km | 0,047 kWh |
| Consumo per tonnellata-km senza recupero | 0,057 kWh |
| Picco di potenza, media su un minuto | 167 MW alle 7:01, con 198 treni in servizio |
| Treni-km diesel | 16 303 |

I treni-km totali, 143 206, coincidono a meno dello 0,4% con quelli calcolati
dall'orario per i costi (143 774).

Il valore di confronto è la tabella dei consumi normativi svizzeri per i
treni regionali: 0,0463 kWh per tonnellata-km con frenatura a recupero,
0,0671 senza [8]. Il consumo simulato con recupero è allineato al primo
(0,047); quello senza recupero è inferiore al secondo (0,057 contro 0,067).

## 8. Che cosa viene salvato

Ogni run scrive nella propria cartella:

| File | Contenuto |
|---|---|
| `energy.json` | totali elettrici e diesel, consumi specifici, minuto di picco |
| `energy_by_line.csv` | gli stessi totali per linea e tipo di trazione |
| `power_profile.csv` | per ogni minuto: potenza presa dalla rete, recuperata, dissipata, litri/ora, treni in servizio |
| `trips.csv` | per ogni corsa: km, kWh presi dalla rete, kWh rigenerati, litri |
| `charts/power_profile.png` | curva della potenza nel giorno |
| `energy-model.json` | copia dei parametri usati |

## 9. Limiti

- Lo stile di guida di railsim (accelerazione e frenata massime, nessuna
  marcia per inerzia) sovrastima l'energia spesa a ogni fermata. Per il
  gasolio è corretto dalla taratura; per l'energia elettrica no, e il consumo
  senza recupero va letto come limite superiore.
- Servizi ausiliari e rendimento del motore diesel sono ipotesi senza una
  fonte verificata; gli ausiliari non dipendono dalla stagione.
- La massa a vuoto dei Caravaggio è stimata dal carico assiale.
- Il coefficiente di taratura è ricavato dall'ATR 125 e applicato anche
  all'ATR 803, che ha batterie e un consumo dichiarato inferiore.
- Pendenze, curve e gallerie non entrano nella resistenza al moto.
- Il recupero è valutato in linea d'aria, senza lo schema delle sottostazioni.
- Il riempimento dei treni è un'ipotesi fissa per fascia oraria.

## Riferimenti

1. railsim, *Train specification*, MATSim contrib railsim,
   `contribs/railsim/docs/train-specification.md`.
   <https://github.com/matsim-org/matsim-libs/tree/main/contribs/railsim>
2. B. Dalla Chiara et al., *Consumo energetico dei treni in esercizio*,
   Ingegneria Ferroviaria, n. 4, 2015, pp. 327–358.
   <https://iris.polito.it/handle/11583/2606360>
3. E. Andersson, P. Lukaszewicz, *Energy consumption and related air
   pollution for Scandinavian electric passenger trains*, Report KTH/AVE
   2006:46, Royal Institute of Technology, Stoccolma, 2006.
4. A. Minoia, *Trazione Elettrica*, Lezione 3, "Impianti fissi di trazione
   elettrica", Università degli Studi di Pavia.
   <https://cad.unipv.it/slide_TE/TE_Lezione_3.pdf>
5. Stadler Bussnang AG, *Automotrici articolate Diesel-elettriche GTW 2/6 e
   GTW 4/12 a piano ribassato per Ferrovienord, (Milano) Italia*, scheda
   tecnica GFNM0210i.
6. Rete Ferroviaria Italiana, Direzione Tecnica, *Norme particolari per la
   circolazione dei complessi elettrici (TAF) EB 760 / EB 990 / EA 761*,
   allegato alla disposizione n. 35 del 3 giugno 2005.
7. Rete Ferroviaria Italiana, *Energia di trazione*.
   <https://www.rfi.it/it/Sicurezza-e-tecnologie/tecnologie/energia/energia-di-trazione.html>
   (consultato il 4 ottobre 2026).
8. Ufficio federale dei trasporti (Svizzera), modifica dell'ordinanza
   sull'accesso alla rete ferroviaria, valori di consumo dei treni.
   <https://www.bav.admin.ch/dam/bav/it/dokumente/aktuell-startseite/vernehmlassungen/nzv_bav-nzv/aenderungen_nzv_bav.pdf.download.pdf/Modifica%20OARF-UFT.pdf>
