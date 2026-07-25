# Milan Suburban Railway Simulation — project instructions

Simulazione della rete suburbana (linee S) di Milano con **railsim/MATSim**.
Progetto d'esame *Sistemi Complessi: Modelli e Simulazione* (Milano-Bicocca).
Design completo: `docs/superpowers/specs/2026-07-14-simulazione-rete-s-milano-railsim-design.md`.

## Coding rules (ferree)

Stile senior, clean code. Valgono per TUTTO il codice del progetto.

**Language & formatting**
- Identifiers: English. Comments and Javadoc: **English**.
- Java indentation: **tabs** (as railsim/MATSim sources).
- Match the surrounding railsim/MATSim idiom when working alongside it.

**Naming & structure**
- Intention-revealing names, no abbreviations. Classes = nouns, methods = verbs.
- One class = one responsibility. Small methods doing one thing, at a single level of abstraction.
- Few parameters (≤3); no boolean flag arguments.
- `final` where it expresses immutability; prefer immutability; no mutable static state.
- No dead code, no commented-out code.

**Comments — only where they add value**
- Explain the **why**, never the **what**. No comments restating the code.
- Allowed for: non-obvious decisions, constraints, workarounds (with reason),
  references to papers/formulas (e.g. "braking distance, cf. Li-Gao-Ning §3"), warnings.
- An obsolete or misleading comment is a bug: update it or delete it.

**Javadoc — where needed**
- On public API (public classes/methods) when the contract is not self-evident:
  purpose, `@param`, `@return`, `@throws`, invariants/assumptions. Written for the caller.
- No Javadoc noise on trivial or self-evident members.

**Java idioms**
- Explicit error handling: no swallowed exceptions; no `null` where `Optional` or fail-fast is clearer.
- Logging via the framework logger (MATSim log4j), never `System.out`.
- Interfaces at boundaries; dependency injection (Guice/MATSim).

**Verification**
- Logic-bearing code (metrics analysis, custom disposition, parsing) is developed
  test-first where it makes sense. Verify by running, not by asserting.

## railsim working rules

- **Non fidarsi della memoria per l'API di railsim.** Ancorarsi al sorgente reale in
  `/home/fabio/Scrivania/matsim-libs/contribs/railsim`; verificare compilando/eseguendo.
- railsim extension points (Guice, `RailsimQSimModule` binds defaults that
  "might be replaced"): `TrainDisposition` (conflict priority — the scientific lever),
  `DeadlockAvoidance`, `SpeedProfile`, event handlers for custom metrics.
- Gotcha: `RunRailsimExample` default points to `microOlten/` which is ABSENT — always pass an explicit config.
- Toolchain: JDK 25 + Maven; MATSim/railsim artifacts are `2027.0-SNAPSHOT` in `~/.m2`.

## Repo rules

- Do NOT commit the `.md` files (context doc + spec) for now, unless told otherwise.
- Commit messages: **concise** (ideally one line), no verbose body. **NEVER** add a `Co-Authored-By` / co-author trailer.
- Git workflow: `master` is for merges only; one `feature/*` branch per piece of work.
