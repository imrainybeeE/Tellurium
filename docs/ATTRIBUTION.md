# Provenance and dependencies

v0.1 uses new implementation code. The earlier GPUWorldGen project was inspected for architecture and evidence; no implementation source, shaders, assets, or build logic was copied into these modules. The design proposal and evidence documents were authored for this new project.

The only files copied from the reference checkout are standard Gradle wrapper files. Their upstream was [Gradle8.14.2](https://github.com/gradle/gradle/tree/v8.14.2), Apache2.0 (wrapper JAR SHA-256 at initial copy `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`); they were regenerated with the wrapper task of [Gradle 9.4.0](https://github.com/gradle/gradle/tree/v9.4.0), Apache 2.0, wrapper JAR SHA-256 `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`. The official distribution checksum is pinned in gradle/wrapper/gradle-wrapper.properties.

Build/runtime dependencies retain their own licenses:
- Gradle: Apache2.0.
- NeoForge/ModDevGradle: their upstream LGPL terms; development integration, not relicensed.
- Minecraft: Mojang/Microsoft terms; fetched by the development toolchain, never published as project sources.
- LWJGL: BSD3-Clause. Vulkan/Shaderc/native libraries are isolated in the standalone runtime/replay distribution.
- JUnit5: EPL2.0, test-only.

Future C2ME/FlowSched reuse requires a commit/file inventory and preservation of notices. C2ME base's permissive license does not cover its excluded ARR OpenCL module. No such code is incorporated in v0.1. Refer to the pinned research in docs/design/EVIDENCE.md before choosing a future scheduler lineage.
