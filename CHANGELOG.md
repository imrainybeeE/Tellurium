# Changelog

## Unreleased

**Builds**: Minecraft 26.1.2, Fabric only (`tellurium-fabric-26.1.2-<version>.jar`; Fabric Loader 0.19.5 or
later, Fabric API 0.155.3+26.1.2, Java 25 or later). NeoForge 26.1.2 is not ported.

**What is in it**

- The 26.1.2 port: 26.1 is not obfuscated, so the build has no mappings and no remapping
  (`mod/fabric-unobfuscated.gradle`, `mod/versions/26.1.2`). Details:
  [docs/evidence/minecraft-26.1.2.md](docs/evidence/minecraft-26.1.2.md).
- A kernel dialect for GPUs without 64-bit floats (`IEEE_BITS`: every double and float operation is exact
  32-bit integer code) and a native Metal backend for macOS (module `runtime-metal`, Java 25), bundled in the
  26.1.2 jar only; `-Dtellurium.fast.backend=vulkan` selects MoltenVK instead.
- On Apple GPUs `gpu.mode = "auto"` keeps terrain on the processor. Measured on an M3 (Chunky radius 1000,
  16,129 chunks): Tellurium with the GPU off 179 chunks/s, with the Metal GPU forced 129. The reason is shown
  in the log and in `/tellurium status`; `"check"` and `"force"` work.
- Two bugs of the helper library fixed: a subnormal that rounds up to the smallest normal lost its carry in
  `wg_fp32_pack` and `wg_fp64_pack`, and the End's islands relied on Java's `int` overflow, which Metal may
  assume does not happen.
- The build runs Gradle 9.4.0 on JDK 25 with a JDK 21 also installed (Fabric Loom 1.15.5, ModDevGradle
  2.0.148).
- Scripts: `scripts/bench-cps.sh`, `verify-fast-matrix.sh` and `verify-save-reopen.sh` run without PowerShell
  (the latter two with `CPU_ONLY=1` for a host without a usable GPU), and
  `scripts/compare-chunk-mods-fabric.sh`.

**Known limits**

- 26.1.2 was run on one machine, an Apple M3 MacBook Air, and only with the GPU off (digests identical to
  vanilla, 3 of 3 contexts at SURFACE and at CARVERS) or on the Metal GPU (178 of 178 chunks compared with
  vanilla, 3 of 3 digest rows). Not run: a GPU with 64-bit floats (the native 26.1.2 list is empty), Windows,
  Linux, another Apple GPU.
- C2ME and zfastnoise must be disabled alongside Tellurium.

## 0.2.0-alpha.1

First public alpha.

**Builds**: Minecraft 1.21.1 and 1.21.11, for NeoForge and for Fabric.

**What is in it**

- Terrain and surface generation on the GPU (Vulkan) for tested world generators: vanilla Overworld,
  Nether and End, Terralith, Tectonic, both together, Incendium; terrain for Amplified Nether and
  Nullscape. On 1.21.11: vanilla, Terralith, Tectonic and both together. Everything else is built by
  vanilla code.
- Structure, carver and feature steps of different chunks in parallel.
- Background chunk saving, with a choice of compression level.
- A pregenerator: `/tellurium pregen start <radius>`, pause, resume (also after a restart), stop.
- `/tellurium status`, and `config/tellurium.toml`; `gpu.mode = "check"` compares the GPU with vanilla
  on any world.
- Distant Horizons and Voxy are fed from Tellurium's generation, paced to keep the game smooth.
- Chunky's limit on chunks in progress is raised when Chunky is installed.

**Known limits**

- Run on one machine only (Windows 11, RTX 5070 Ti). Linux and other graphics cards are untested.
- On 1.21.11, Distant Horizons, Voxy and a released client have not been run.
- Saved worlds are about 12% larger by default (`saving.compression_level = 6` for vanilla's size).
- Chunk save listeners of other NeoForge 1.21.1 mods may see chunk data before it is complete;
  `saving.async = false` avoids that.

The mod was called WorldgenNext during development; `config/worldgennext.toml` is copied to
`config/tellurium.toml` on first start.
