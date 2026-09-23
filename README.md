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

### Desktop application

```bash
# Run from the project folder (the data is read from here)
mvn javafx:run

# Native package with the logo: target/dist/ (a .deb on Linux, .exe on Windows, .dmg on macOS)
mvn -DskipTests verify -Pdist
sudo apt install ./target/dist/milanrailsim_*.deb
```

The package is built with `jpackage` from the shaded jar and bundles a Java
runtime, so the installed application needs neither Maven nor a JDK. It is
installed under `/opt/milanrailsim` with a menu entry, and the window is tied
to its icon through `StartupWMClass` in `assets/jpackage/linux/MilanRailSim.desktop`
(the stock `jpackage` template lacks it, and GNOME would show a generic icon).
Windows and macOS need the logo as `assets/logo/logo.ico` and `logo.icns`.

Where the application keeps its files:

- **Project data, read-only** (`gui/config/DataRoot`): the scenario under
  `scenarios/milan`, the node declarations in `data/nodes`, the committed
  GTFS feed, the station track survey and the default costs, laid out as in
  this repository. In development this is the working directory; the package
  copies these files into `lib/app/share` and the launcher passes
  `-Dmilanrailsim.data` pointing there. Rebuild the package after changing
  them. The application never writes here.
- **User state** (`gui/config/AppPaths`): `~/MilanRailSim/runs` (one folder
  per simulation: scenario, engine log, MATSim output, analysis, charts),
  `~/MilanRailSim/config` (imported GTFS feed, cost parameters, rolling stock
  catalogue with its photos) and `~/MilanRailSim/cache/tiles` (map tiles).
  The same folders are used in development and once installed.

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
