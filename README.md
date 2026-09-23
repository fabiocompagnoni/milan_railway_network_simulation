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
runtime, so the installed application needs neither Maven nor a JDK. On Linux it is installed under `/opt/milanrailsim` with a menu entry, and the window is tied to its icon through `StartupWMClass` in `assets/jpackage/linux/MilanRailSim.desktop`
(the stock `jpackage` template lacks it, and GNOME would show a generic icon).

`jpackage` only builds for the system it runs on, so the same command must be run on each platform; the profile picks the package type, the icon (`assets/logo/logo.png`, `logo.ico` or `logo.icns`, all exported from the SVG)
and the platform options in `assets/jpackage/<platform>/options.txt`:

- **Windows** (`.exe` installer, Start menu entry and desktop shortcut, per-user
  install): JDK 25 and [WiX Toolset 3.x](https://wixtoolset.org) on the `PATH`.
- **macOS** (`.dmg`): JDK 25 and the Xcode command line tools. The app is not
  signed, so the first launch needs a right click, *Open*.

Where the application keeps its files:

- **Project data, read-only** (`gui/config/DataRoot`): the scenario under
  `scenarios/milan`, the node declarations in `data/nodes`, the committed
  GTFS feed, the station track survey, the default costs and the photos of
  the train types in `data/trains` (one file per type id; a photo the user
  uploads takes precedence), laid out as in this repository. In development this is the working directory; the package
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

### Releases from GitHub Actions

Every push runs the tests (`.github/workflows/ci.yml`). A tag `v<version>`
runs `.github/workflows/release.yml`: the jar and the installer are built on
Linux, Windows and macOS runners and attached to a GitHub Release of the tag,
so no machine of each platform is needed.

```bash
git tag v0.1.0 && git push origin v0.1.0
```

What keeps it fast: the MATSim snapshot artifacts, which are published
nowhere, are built once from the pinned `matsim-libs` commit by the composite
action `.github/actions/matsim-libs` and cached across every job and runner
OS; the project's other dependencies come from the Maven cache of
`setup-java`, keyed on `pom.xml`. The three platform jobs run in parallel and a superseded run on the same ref is cancelled. Run by hand from the Actions tab, the workflow only produces the artifacts, without a release.

## Git workflow

`master` holds integration only; each piece of work happens on a dedicated
`feature/*` branch and is merged back.
