# Tellurium working instructions

Read Plan.md, docs/CONTRACTS.md and docs/TESTING.md before implementation.
This repository is independent of D:\\Projects\\GPUWorldGen. Treat that checkout as read-only reference.
No CUDA APIs, libraries, runtime dependencies, or CUDA-backed abstraction layers.
Run Gradle on JDK 25 and keep a JDK 21 installed as well: the pure modules and the 1.21.x targets compile with a Java 21 toolchain, the 26.1.2 target needs Java 25. Generally: `JAVA_HOME=<JDK 25 home> ./gradlew test build --no-daemon`. On the initial Windows host:
`$env:JAVA_HOME='C:\\Program Files\\Eclipse Adoptium\\<jdk-25 folder>'; .\\gradlew.bat test build --no-daemon`.
Without PowerShell (macOS, Linux) use `scripts/bench-cps.sh`, `scripts/verify-fast-matrix.sh` and `scripts/verify-save-reopen.sh`; `CPU_ONLY=1` runs the last two with the GPU off and is not GPU evidence. `scripts/compare-chunk-mods-fabric.sh` compares chunk-generation mods on an installed Fabric server.
Use delegated agents for disjoint modules when multiple independent packages are ready. Pin shared contracts first; each worker owns its module only. Do not overlap edits or run GPU tests concurrently. The coordinator owns root build integration and documentation.
Keep synthetic replay, real GPU execution, Minecraft oracle parity, NOISE throughput, FULL throughput, and saved-world verification distinct. Never report an unimplemented gate as passed or an unsupported feature as successful generation.
Unknown frontend operations fail closed with an explicit reason. No production generation hook until its independent same-stack oracle passes.
Tests should challenge invariants, failure paths and cross-module contracts; do not create disabled placeholders that imply coverage.
All shipping source is new code unless attribution explicitly records otherwise. Do not import C2ME OpenCL implementation.
