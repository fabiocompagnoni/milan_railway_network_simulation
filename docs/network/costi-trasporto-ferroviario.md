# Analisi dei costi del trasporto ferroviario regionale — quadro per il modello

> Stato: I valori proposti sono stime di ordine di grandezza derivate da fonti pubbliche
> 2026-09-08.

## Ancore macro (fonti primarie)

- **Contratto di Servizio Trenord–Regione Lombardia 2023–2033**: valore
  complessivo ~**5,4 mld €** su 10 anni (≈ 540 M€/anno di corrispettivo).
  Fonti: pagina contratto di servizio Trenord (amministrazione trasparente),
  Regione Lombardia (contratto SFR 2023).
- **Copertura dei costi da ricavi tariffari**: in Lombardia i ricavi da
  biglietti/abbonamenti coprono ~**43%** dei costi del servizio (fonte:
  Regione Lombardia / atti consiliari).
- Produzione annua Trenord: ~**40–44 M treni·km** (ordine di grandezza da
  bilanci/rapporti; da puntualizzare sul bilancio di sostenibilità Trenord).
- ⇒ **Costo totale implicito**: (corrispettivo + ricavi) / treni·km ≈
  **15–19 €/treno·km** — l'ancora di sanità per ogni scomposizione:
  la somma delle categorie sotto deve cadere in questa banda.
- Approfondimento indipendente: **Rapporto TRASPOL (Politecnico di Milano)
  su Trenord 2010–2019** — riferimento per la validazione fine.

## Scomposizione per categoria (proposta per il modello)

| Categoria (`costs.json`) | Unità | Stima proposta | Base della stima | Confidenza |
|---|---|---|---|---|
| `staff` (equipaggio: macchinista + capotreno) | €/treno·ora | **140** | costo aziendale ~60–70 k€/anno a figura / ~1.600 h utili → ~70 €/h a figura × 2 | media |
| `electricity` | €/treno·km | **1,7** | costo medio specifico energia di trazione a prezzi di mercato (analisi settore/ART) | media |
| `diesel` | €/treno·km | **3,0** | consumo specifico automotrici diesel × prezzo gasolio; più alto dell'elettrico | bassa |
| `maintenance` (manutenzione rotabili) | €/treno·km | **2,5** | letteratura di settore: acquisizione+manutenzione rotabile ~4–5 €/km, quota manutentiva | bassa |
| `rolling_stock` (ammortamento/canone) | €/treno·giorno | **650** | es. Caravaggio ~7 M€ / 30 anni ≈ 233 k€/anno / 365 | media |
| `track_access` (pedaggio RFI/Ferrovienord) | €/treno·km | **2,5** | sistema tariffario pedaggio ART (delibere su canoni d'accesso) | media |

**Verifica di coerenza interna** (a velocità commerciale ~50 km/h e ~400 km/
giorno per convoglio): staff ≈ 2,8 €/km + elettrico 1,7 + manutenzione 2,5 +
ammortamento ≈ 1,6 + pedaggio 2,5 ≈ **11 €/treno·km** diretti; col personale
non viaggiante, pulizie, assicurazioni e overhead si arriva alla banda macro
15–19 €/treno·km. La scomposizione è quindi internamente plausibile.

## Cosa NON modelliamo (fuori scope dichiarato)

- Ricavi tariffari e impatto sugli utenti (l'idea «di quanto aumentano i
  biglietti all'aumentare dei costi» è parcheggiata come sviluppo futuro:
  con copertura ~43%, un Δcosti si traduce in Δcorrispettivo pubblico e/o
  Δtariffe — derivata interessante per il report, non per questa fase).
- Costi di infrastruttura non legati alla circolazione (stazioni, personale
  di terra), overhead societari: assorbiti implicitamente nel confronto
  *differenziale* tra scenari (contano i Δ, non i totali assoluti).

## Fonti

- Trenord — Contratto di servizio (amministrazione trasparente):
  `https://www.trenord.it/chi-siamo/amministrazione-trasparente/contratto-di-servizio/`
- Regione Lombardia — Contratto di Servizio SFR 2023:
  `https://www.regione.lombardia.it/.../contratto-di-servizio-sfr-2023`
- Trenord — Bilancio di sostenibilità 2023 (PDF sul sito Trenord).
- TRASPOL/Politecnico di Milano — Rapporto indipendente su Trenord 2010–2019:
  `https://www.traspol.polimi.it/wp-content/uploads/2021/06/TRASPOL-Trenord-report-_-published.pdf`
- ART (Autorità di Regolazione dei Trasporti) — delibere su costi standard e
  canoni di accesso (es. delibera 66/2018) e Data Portal rete ferroviaria.
