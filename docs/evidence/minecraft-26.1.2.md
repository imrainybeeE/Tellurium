# Minecraft 26.1.2 on Fabric, and Apple GPUs — 2026-10-20

`fabric-2612` builds `tellurium-fabric-26.1.2-<version>.jar` (Fabric Loader 0.19.5 or later, Fabric API
0.155.3+26.1.2, Java 25 or later). The work between 2026-10-17 and 2026-10-20 was done on an Apple M3
MacBook Air (8 cores, 16 GB, a 10-core GPU without 64-bit float support, macOS, JDK 25.0.1 and 21.0.9).
Every figure below is from that host.

Fabric only. NeoForge 26.1.2 is not ported.

This document has two parts that must not be read as one:

- the 26.1.2 port itself, verified on the CPU side only (digests identical to vanilla with the GPU off);
- a route for GPUs without 64-bit floats, built and measured on the M3. It is correct on the device, and it
  is slower than the CPU there, so `gpu.mode = "auto"` does not use it on Apple GPUs.

C2ME and zfastnoise must be disabled alongside Tellurium: they patch the same chunk-system and noise code.

## What exists

| Item | Value |
| --- | --- |
| Gradle project | `fabric-2612` (`mod/targets.gradle`; build script `mod/fabric-unobfuscated.gradle`) |
| Version folder | `mod/versions/26.1.2`, on top of `1.21.11`, `1.21.8` and `1.21.4` |
| Jar | `tellurium-fabric-26.1.2-<version>.jar` |
| Requires | Fabric Loader 0.19.5 or later, Fabric API 0.155.3+26.1.2, Java 25 or later |
| GPU runtime in the jar | LWJGL 3.4.1 (the game's), the macOS arm64 natives with SPIRV-Cross, and the Metal backend (`runtime-metal`) |
| Tested generators on the GPU (native fp64) | none: the 26.1.2 native list is empty |
| Tested generators on the GPU (emulated dialect) | none, on purpose: `fused-qualified-ieee.properties` is empty and carries `auto.reason` |

## How it is built

Toolchain, for every target: Gradle 9.4.0 running on JDK 25, with a JDK 21 also installed (the pure modules
and the 1.21.x targets compile with a Java 21 toolchain); Fabric Loom 1.15.5; ModDevGradle 2.0.148. CI
installs JDK 21 and 25. Every older target still builds and its unit tests pass.

The two Loom plugins are not interchangeable: `net.fabricmc.fabric-loom` (non-remapping) builds 26.1;
`net.fabricmc.fabric-loom-remap` builds the 1.21.x Fabric targets.

`fabric-2612` uses `mod/fabric-unobfuscated.gradle`, which has no mappings and no remapping. Its sources are
assembled as for the other versions (`mod/sources.gradle`): the shared source plus the files of the version
folders that `mod/targets.gradle` lists for 26.1.2, nearest first. The GPU runtime of this target is bundled by
`mod/gpu-runtime.gradle`; only targets with `macos: true` carry the macOS natives, SPIRV-Cross and the
`runtime-metal` jar (this is the only such target).

## What changed in Minecraft, and what the mod does about it

| Change | Handling |
| --- | --- |
| 26.1 is not obfuscated: no mappings, no remapping, no Fabric name table. `Loader.TRANSLATES` is true only in the intermediary namespace; 26.1 runs in "official" | The build script above. The access widener is a class tweaker with the header `classTweaker v1 official`. The mixin `compatibilityLevel` is `JAVA_25` |
| `ChunkPos` is a record: coordinates are accessors, and `toLong()` and `ChunkPos.asLong()` became `pack()` | `Version.chunkX` and `Version.chunkZ` in every `Version` class (75 reads on 40 lines in `mod/common`); the `pack()` change is a regular-expression rename in `mod/versions/26.1.2/renames.gradle` |
| `BlockState.getValues()` returns a stream | The mod iterates `getProperties()` with `getValue()`; the order is the same on both versions |
| `WorldData.worldGenOptions()` is gone | The seed comes from `ServerLevel.getSeed()` |
| `ChunkMap`'s constructor runs code before `super()`, and Mixin then drops the `@Unique` field initialisers | The unload-type cache is created on first use. Before the fix the first run had a `NullPointerException` and 91 chunk-load failures |
| Worlds keep dimensions under `dimensions/<namespace>/<id>/` | The scripts follow it |
| World generation, 1.21.11 against 26.1.2 | Decompiled and diffed (levelgen, synth, carver, material, `Climate`, `Aquifer`, `Beardifier`, `NoiseChunk`, `SurfaceRules` and others): no change relevant to the mod. The Nether router uses two new noises, `nether/temperature` and `nether/vegetation`, which the frontend handles |

## Verification on the M3 (CPU side)

The GPU is off (`tellurium.fast.gpu=off`) and every CPU-side change is on. `scripts/verify-fast-matrix.sh`
runs this with `CPU_ONLY=1` (new): a row then passes on identical digests alone and the result says "CPU
only". It is not GPU evidence. Radius 45 is 8,281 chunks.

| Check | Result |
| --- | --- |
| SURFACE digests against vanilla, 8,281 chunks per context (`scripts/verify-fast-matrix.sh` with `MODULE=fabric-2612` and `CPU_ONLY=1`) | 3 of 3 identical: Overworld, Nether, End |
| CARVERS digests, the same contexts | 3 of 3 identical |
| Save and reopen at FULL (`scripts/verify-save-reopen.sh` with `MODULE=fabric-2612` and `CPU_ONLY=1`) | PASS: 8,281 chunks equal after reopening, nothing regenerated (`noiseSteps=0`); region check 0 problems (24 region files, 14,387 readable chunks) |

Every older target still builds with the new toolchain and its unit tests pass.

## The Apple GPU route

Only the 26.1.2 build has it. It was developed on branch work as two parts: an emulated arithmetic dialect
for the kernels, and a Metal backend that runs them.

### Why a dialect

The fused kernels need 64-bit floats, and Apple GPUs have none. The kernel dialect `IEEE_BITS` makes every
double and float operation exact 32-bit integer code (`IntegerIeeeEmitter` `wg_` helpers, `FusedIeeeHelpers`
`wgf_` conversions, `FastIeeeHelpers` `wgx_` fast add, subtract and multiply). No floating-point type
appears in the kernels. The native kernel text is unchanged: golden hashes over 10 fixtures
(`KernelTextIdentityTest`). `jdiv` and `jsqrt` keep their guards and return the correctly rounded result of
the helper.

### Device checks

All on the M3 GPU, all with 0 mismatches:

| Check | Comparisons |
| --- | --- |
| Integer IEEE conformance | 1,829,120 |
| Fused helpers | 15,009,242 |
| Fused library, 42 functions | 9,480,096 |
| Fast add, subtract, multiply | 6,979,296 |

### Bugs found and fixed

| Bug | Effect | Fix |
| --- | --- | --- |
| `wg_fp32_pack` and `wg_fp64_pack` dropped the carry of a subnormal that rounds up to the smallest normal number | 5 of 1,000,056 vectors on the device; it changes the hash of every shader that embeds the helper library | carry kept |
| End islands relied on Java's `int` overflow (vanilla's rings beyond about 370,000 blocks), and Metal may assume signed arithmetic does not overflow | Metal may assume that signed arithmetic does not overflow | the sum is formed in unsigned arithmetic |

### MoltenVK findings

MoltenVK is Vulkan on Metal.

- Its SPIRV-Cross declares every function `always_inline`. One emulated kernel took 1,062 s to become a
  pipeline.
- Handing MoltenVK Metal source, with the large helpers as `noinline` calls, built the same kernel in 0.85 s
  (`MetalKernelSource`). The conversion equals MoltenVK 1.4.2's own output byte for byte (a test resource).
- Metal-source modules then need Metal argument buffers off, set per instance with
  `VK_EXT_layer_settings`.
- MoltenVK runs them with one thread per threadgroup: 31 of 32 SIMD lanes are idle.

### The Metal backend

Because of the last point the 26.1.2 build bundles a native Metal backend (module `runtime-metal`, using
`java.lang.foreign`, Java 25). It is the default on macOS. `-Dtellurium.fast.backend=vulkan` selects
MoltenVK.

The bundled GPU runtime of 26.1.2 uses LWJGL 3.4.1 (the game's) with the macOS arm64 natives and
SPIRV-Cross. An older SPIRV-Cross writes signed modulo as Metal's `%` and is refused.

### Correctness on the GPU

| Check | Result |
| --- | --- |
| Check mode (`gpu.mode = "check"`: vanilla generates, the GPU result is compared block by block) | 178 of 178 chunks with 0 differences in each dimension: the Overworld, the Nether and the End |
| SURFACE digest matrix, 8,281 chunks per dimension, the GPU generating every chunk and its surface, 0 bail-outs | 3 of 3 PASS |
| The same matrix, run again after the speed work below | 3 of 3 PASS |

### Speed work

Overworld SURFACE, GPU time per chunk: 14.3 ms before, 5.05 ms after.

| Change | Note |
| --- | --- |
| `wrapd`'s division by 2^25 is an exact product by 2^-25 | exact |
| Only the large helpers are calls | inlining add, subtract and multiply makes Metal's compiler run for more than ten minutes |
| Fast exact add, subtract and multiply (2.3x to 3.2x cheaper per operation) in every kernel but `K_CORNER` and `K_BLOCK` | in those two they measured slower, so they keep the plain `wg_` helpers |
| An exact skip of the Beardifier bury root and division from 6 blocks away | exact |

Kernels build in about 3 to 4 s per dimension from Metal's cache, and in 18 to 60 s the first time.

### Full generation against other chunk mods

Same session, other apps closed. Chunky radius 1000 (16,129 chunks) on an installed Fabric 26.1.2 server
(`scripts/compare-chunk-mods-fabric.sh`, new). Fabric API, ScalableLux and Chunky are installed in every
configuration. Each generator is run at its best Chunky limit. One run each.

| Configuration | Chunks per second |
| --- | --- |
| vanilla | 94 (Chunky's default limit of 50; 58 at 512) |
| C2ME 0.4.0-alpha.0.65 | 194 |
| C2ME with zfastnoise 1.1.1 | 190 |
| Tellurium, GPU off | 179 |
| Tellurium, Metal GPU forced | 129 |

### Decision

On Apple GPUs `gpu.mode = "auto"` keeps terrain on the CPU. The 26.1.2 emulated-profile list
(`fused-qualified-ieee.properties`) is empty on purpose and carries `auto.reason`, which is shown in the log
and in `/tellurium status`. The measured reason is the last two rows above: with the GPU forced, the
generation of whole chunks was slower (129 chunks/s) than with it off (179). `"check"` and `"force"` work.
A larger Apple GPU may do better; that was not measured.

## Not verified

- Native-fp64 matrix rows for 26.1.2: there is no GPU with fp64 here, so the native 26.1.2 list is empty and
  auto mode uses vanilla code on such a GPU too.
- Windows and Linux runs of 26.1.2.
- NeoForge 26.1.2 (not ported).
- Any Apple GPU other than the M3.

## Found, for upstream (not changed here)

- Native kernel fingerprints depend on the platform's line separator: `FusedKernels` stores lines with
  `System.lineSeparator()` and `structureFingerprint` splits on `\n`. Lists made on Windows would therefore
  not match on Linux or macOS. Confirmed in the code; which platform made the shipped lists is not
  confirmed.
- The native `jdiv` returns +0.0 where Java gives -0.0 when a quotient underflows. This is unreachable for
  real routers. The emulated dialect follows Java.

## In-game test (Remarkably Optimized)

2026-10-20, on the M3: a copy of the user's Prism instance "Remarkably Optimized" (Minecraft 26.1.2, Fabric
Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25; about 130 mods including ModernFix, Debugify,
structure_layout_optimizer, Lithium, ScalableLux, Iris and Distant Horizons 3.3.3), with C2ME and zfastnoise
disabled in the copy only and the 26.1.2 jar of this branch added. Singleplayer world `TelluriumTest` (seed 0,
made on a dedicated server first) with a datapack whose load function runs `tellurium pregen start 40`; default
settings (`gpu.mode = "auto"`).

| Check | Result |
| --- | --- |
| The mod loads beside the pack | Yes: "Tellurium 0.2.0-alpha.1 loaded (GPU mode auto ...)"; no mixin failure, and no warning or error from Tellurium in the log. The pack's own mixin warnings (JEI, Swinging Lanterns, ModernFix overwrite conflicts) are there with or without Tellurium |
| GPU decision | "GPU terrain generation on Apple M3 (mode auto)", "GPU arithmetic: emulated in integers, no fp64 on this GPU", then for each dimension "generates with vanilla code: on Apple GPUs auto mode keeps terrain on the CPU: ..." with the measured reason |
| Distant Horizons | The bridge attached in all three dimensions ("Distant Horizons' chunk generation for minecraft:overworld goes through the server's chunk system ...") |
| Pregeneration | "Pregeneration finished: 6,561 chunks in 2 min (53 chunks/s)", with the client rendering, Distant Horizons running and a player in the world |
| Errors | 4 error lines, all network calls (Realms feature flags, profile key pair, user properties, Realms); the same count as the instance's own log before Tellurium was added |

Not exercised: `/tellurium status` typed in chat (the same reason text is what it shows), the GPU in `force` or
`check` mode inside the client, and a long session.
