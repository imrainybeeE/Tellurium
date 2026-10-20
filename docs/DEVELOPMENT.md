# Developing Tellurium

For people building the mod or changing it. Players and server owners want the [README](../README.md).

Read [Plan.md](../Plan.md), [CONTRACTS.md](CONTRACTS.md) and [TESTING.md](TESTING.md) before changing
generation code. How the builds for different Minecraft versions and loaders share one source tree is in
[mod/README.md](../mod/README.md).

## Checks that matter for a change

```sh
bash scripts/release-check.sh        # build, unit tests, exactness, save/reopen, kill/resume, console (about 25 minutes, needs the GPU)
bash scripts/test-installed-fabric.sh 100          # the built Fabric jar on a real server
NEOFORGE_MC=1.21.11 bash scripts/test-installed-neoforge.sh 100
```

Without PowerShell (macOS, Linux) and, for a host without a usable GPU, without the GPU:

```sh
# Fresh-world throughput run: bash port of bench-cps.ps1, same flags
# (MODULE=<target>; BENCH_MAX_HEAP and BENCH_IN_FLIGHT change the defaults of -MaxHeap and -InFlight)
MODULE=fabric-2612 bash scripts/bench-cps.sh -Status NOISE -RadiusChunks 45
# Digests against serial vanilla with the GPU off and every CPU-side change on ("CPU only", not GPU evidence)
MODULE=fabric-2612 CPU_ONLY=1 bash scripts/verify-fast-matrix.sh 45
MODULE=fabric-2612 CPU_ONLY=1 bash scripts/verify-save-reopen.sh 45 vanilla
# A Chunky pregeneration on an installed Fabric server: vanilla, tellurium, c2me or c2me-zfastnoise
FABRIC_MC=26.1.2 bash scripts/compare-chunk-mods-fabric.sh tellurium 1000
```

`verify-fast-matrix.sh` and `verify-save-reopen.sh` run without PowerShell.

```sh
# One FULL benchmark run (label, extra -D properties, mods folder, radius, warm-up radius)
bash scripts/bench-full.sh myrun "" build/test-mods/vanilla 90 40
# Digest matrix against serial vanilla (15 contexts, about 45 minutes)
bash scripts/verify-fast-matrix.sh 45
# Save, reopen and compare
bash scripts/verify-save-reopen.sh 45 vanilla
# The user path end to end: default config, pregenerate, status report
bash scripts/run-pregen.sh 60 vanilla
```

## Repository

This repository also contains the earlier, stricter qualification route (typed
capture/program contracts, an independent Minecraft oracle, a bit-exact GPU
route behind evidence gates). It ships in the jar as developer tooling under
`/tellurium dev` and generates nothing unless an operator supplies
qualification evidence. Start with [Plan.md](../Plan.md),
[architecture](ARCHITECTURE.md), [file map](FILE_MAP.md) and
[validation status](STATUS.md).

## Build and test

Run Gradle on JDK 25 and have a JDK 21 installed as well: the pure modules and the 1.21.x targets compile with a Java 21 toolchain, and the 26.1.2 target needs Java 25. The wrapper downloads pinned Gradle 9.4.0 with checksum verification; initial dependency downloads require internet access. Fabric Loom is 1.15.5 (plugin `net.fabricmc.fabric-loom` for 26.1, the non-remapping one; `net.fabricmc.fabric-loom-remap` for 1.21.x) and ModDevGradle 2.0.148. CI installs JDK 21 and 25.

```sh
# macOS or Linux, with JAVA_HOME pointing to JDK 25 (and a JDK 21 installed)
JAVA_HOME=<JDK 25 home> bash ./gradlew test build verifyArchitecture testReport --no-daemon
python3 scripts/summarize-tests.py
```

```powershell
# Windows: select JDK 25 explicitly (a JDK 21 must also be installed)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\<jdk-25 folder>'
.\gradlew.bat test build verifyArchitecture testReport --no-daemon
python scripts/summarize-tests.py
```

`scripts\check.ps1` still looks for a JDK 21 for the Gradle launcher; use the explicit form above.

The default suite does not require a GPU or launch Minecraft. Aggregate HTML: `build/reports/tests/all/index.html`. Every module retains its own JUnit XML and HTML reports. Python is needed only for the convenience summary script.

## Run the staged-route diagnostics

With JDK21 selected, run these from the repository root:

```powershell
# Deterministic CPU replay with a counted JSON report
.\gradlew.bat :oracle-and-replay:replay --no-daemon

# Explicit native GPU check; fails if a qualifying GPU is unavailable
.\gradlew.bat :oracle-and-replay:gpuSmoke --no-daemon

# Separate, limited diagnostic for GPUs without FP64 subnormal preservation
.\gradlew.bat :oracle-and-replay:gpuSmokeNormal --no-daemon

# Isolated NeoForge startup and mapped-capture diagnostics
.\gradlew.bat :neoforge-1211:gameSmoke --no-daemon

# Original-only Minecraft NOISE corpus and clean-process determinism preflight
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1
.\gradlew.bat :oracle-and-replay:run --args='compare-corpus build/oracle-captures/original build/oracle-captures/original-repeat --failure-dir=build/oracle-failures' --no-daemon

# The default is the 10-case preflight. A release-sized core square is 5x5;
# use a distinct context ID and frozen stack fingerprint for each real stack.
# Captures are namespaced below CaptureRoot so equal coordinates do not collide.
# powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-corpus.ps1 `
#   -ContextId vanilla-overworld -CoreSquareSideChunks 5

# Reference-only FULL endpoint stability smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-full.ps1 `
  -OutputRoot .\build\oracle-full-check `
  -RunRoot .\build\oracle-runs\full-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Reference-only SAVED/reopened smoke (two fresh processes)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capture-original-saved.ps1 `
  -OutputRoot .\build\oracle-saved-check `
  -RunRoot .\build\oracle-runs\saved-check `
  -Seed 0 -Dimension minecraft:overworld -ChunkX 0 -ChunkZ 0

# Rebuild the portable source archive
.\gradlew.bat sourceBundle --no-daemon
```

Replay reports are in `oracle-and-replay/build/replay/`. The dedicated game run is under `mod/targets/neoforge-1211/build/run/gametest/`. GPU checks are bounded smoke workloads, not terrain benchmarks. Run them one at a time.

On the initial RTX5070Ti host, the normal-range diagnostic passed 917 comparisons. The strict GPU check failed its capability gate because the driver does not advertise FP64 subnormal preservation. The two results have different corpus IDs and neither establishes full floating-point or Minecraft qualification. See [validation status](STATUS.md).

The mod jars of every build are in `build/release/` after `gradlew build` (for NeoForge 1.21.1, `tellurium-neoforge-1.21.1-0.2.0.jar`). The staged route's diagnostics are under `/tellurium dev` (`status`, `status-json`, `write-default-config`, `selftest`); `selftest` compares synthetic CPU expressions and fixture material states and validates no world generation. 

## Modules

| Module | 0.2 checkpoint responsibility |
| --- | --- |
| semantic-core | Typed programs, immutable snapshots/identities, material/execution contracts and v0.1 language |
| compiler-jvm | Scalar typed interpreter, deterministic RNG/noise/aquifer/ore primitives and dense CPU baseline |
| compiler-vulkan | Synthetic emitter plus integer-carrier helper/source and dense worldgen shader contracts |
| material-codec | Registry-aware complete dense results, metadata, little-endian ABI and fixture encodings |
| spatial-data | Full spatial identities, typed sample leases, cached/uncached stores and bounded accounting |
| chunk-engine | Unique work/subscriptions, atomic resource admission, backend receipts and authoritative commit seam |
| frontend-mc1211 | Pure captured-node/router/snapshot lowering with explicit unsupported diagnostics |
| runtime-vulkan | Persistent service lifecycle, disjoint leases, visibility, pipeline and quarantine models |
| runtime-metal | Native Metal backend for macOS (`java.lang.foreign`, Java 25); bundled only by targets with `macos: true` (26.1.2). See [Minecraft 26.1.2](evidence/minecraft-26.1.2.md) |
| oracle-and-replay | Isolated corpus API, field-by-field comparator, failure bundles and endpoint baseline contracts |
| oracle-1211 | Independent original-only NeoForge/Minecraft capture process; no Tellurium candidate dependency |
| mod | The mod itself, one source tree for every Minecraft version and loader: `common` (written for 1.21.1), `loader/neoforge` and `loader/fabric`, `versions/<version>` for what later versions need differently, and the table of builds in `targets.gradle`. Each build is a Gradle project named after its loader and version (`neoforge-1211`, `fabric-1211`, `fabric-1214`, `fabric-1218`, `neoforge-1218`, `fabric-12111`, `neoforge-12111`, `fabric-2612`). See [mod/README.md](mod/README.md) |

See the module READMEs for runnable diagnostics and detailed API limits. [docs/CONTRACTS.md](CONTRACTS.md) fixes the shared boundaries for contributors.

## Validation and future work

CPU tests, native GPU replay, game startup, Minecraft parity and performance are separate gates. A successful synthetic replay is not vanilla parity, and synthetic samples are not chunks/sec. A strict GPU check cannot substitute CPU execution for missing hardware.

The original-only Minecraft oracle slice is now runnable and has a 10-case vanilla Overworld NOISE determinism preflight plus separate reference-only FULL and SAVED/reopened boundary smokes; the opt-in CPU candidate also passes those ten independent NOISE cases. The required six-context corpus, GPU lowering/execution, and the remaining same-stack, live, FULL and saved-world candidate gates are still open. See [roadmap](ROADMAP.md), [oracle evidence](evidence/v0.2-original-oracle.md) and [test layers](TESTING.md). The new scheduler, sparse GPU terrain production, lighting and saving arrive only after their correctness gates exist.

