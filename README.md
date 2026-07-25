# Milan Suburban Railway Simulation

Simulation of Milan's suburban railway (S lines) with **railsim** (a MATSim
contrib). Exam project — *Sistemi Complessi: Modelli e Simulazione*,
Università di Milano-Bicocca.

Research question: how far can the S-line network be pushed towards *metro-like*
frequencies before delays cascade, and which bottlenecks set that threshold.

## Prerequisites

- **JDK 25** (the MATSim `2027.0-SNAPSHOT` sources require `--release 25`)
- **Maven** (3.8+)

## Dependency setup (required on every machine)

This project depends on `matsim` and the `railsim` / `application` contribs at
version `2027.0-SNAPSHOT`. These are **not** on Maven Central: they must be built
locally into `~/.m2` from the MATSim monorepo.

```bash
# 1. Clone the MATSim monorepo (outside this project)
git clone --depth 1 https://github.com/matsim-org/matsim-libs.git

# 2. Build and install railsim + application (pulls in matsim core)
cd matsim-libs
mvn -pl contribs/railsim,contribs/application -am install -DskipTests
```

After that, this project resolves its dependencies from `~/.m2`.

## Build and run

```bash
# Compile
mvn compile

# Run the smoke scenario (default config)
mvn exec:java

# Run a specific scenario
mvn exec:java -Dexec.args="scenarios/<name>/config.xml"
```

Output is written under `output/<runId>/`, including railsim analysis CSVs
(`railsimTrainStates`, `railsimLinkStates`, `railsimTimeDistance`) in
`output/<runId>/ITERS/it.0/`.

## Layout

```
src/main/java/it/unimib/milanrailsim/   Java sources (run class, extensions)
scenarios/                              railsim scenarios (config + network + schedule + vehicles)
orari_trenord/                          Trenord GTFS feed (calibration data)
docs/docs_matsim/                       railsim reference docs
docs/paper/                             railsim paper (Kaddoura et al. 2024)
```

## Data sources

- **GTFS Trenord** (`orari_trenord/`): schedule, dwell times, frequencies.
- **OpenStreetMap** (via Overpass): real track geometry, distances, line speeds.

## Git workflow

`master` holds integration only; each piece of work happens on a dedicated
`feature/*` branch and is merged back.
