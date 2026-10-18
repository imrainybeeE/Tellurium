// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.InterpolationGeometry;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.BlockStateDescriptor;
import dev.tellurium.semantic.snapshot.EndIslandParameters;
import dev.tellurium.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.tellurium.semantic.snapshot.NoiseParameters;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requests for {@link FusedNoiseCompiler} that are built with no game running: the router graphs are written by
 * hand in the shapes the frontend lowers the game's density functions to, and every noise table is generated
 * from a fixed seed with a generator of this class's own, so that the same fixture gives the same request on
 * every JDK and platform.
 *
 * <p>Together the fixtures use every {@link ProgramNode} case and every operation the compiler emits (see
 * {@code FusedFixtureCoverageTest}, which enforces that), and every combination of the request's switches:
 * both {@link FusedNoiseCompiler.FluidUpdates}, both {@link FusedNoiseCompiler.PreliminarySurface} kinds (the
 * level function in its find-top-surface form and as a plain level), aquifers and ore veins on and off, with
 * and without a {@link SurfaceProgram}, both random algorithms, and the Overworld, a Nether-like and an
 * End-like geometry.  Whoever adds a fixture records its row in {@code FusedNativeGoldenTest}.</p>
 */
final class FusedFixtures {
    private FusedFixtures() {}

    record Fixture(String name, FusedNoiseCompiler.Request request) {}

    private static final EvaluationDomain WORLD = EvaluationDomain.WORLD;
    private static final EvaluationDomain BLOCK = EvaluationDomain.BLOCK;
    private static final EvaluationDomain COLUMN = EvaluationDomain.COLUMN;

    /** The Request of every fixture, in a fixed order.  Built anew on each call: the graphs are not shared between callers. */
    static List<Fixture> all() {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(overworldClassic());
        fixtures.add(overworldFluidRuleSince1212());
        fixtures.add(overworldFindTopSurface());
        fixtures.add(tallPlainLevelOresOnly());
        fixtures.add(netherLike());
        fixtures.add(netherLikeWithSurface());
        fixtures.add(endLike());
        fixtures.add(everyOperation());
        fixtures.add(spaghettiAndBlend());
        fixtures.add(blendedAndEndParameters());
        return List.copyOf(fixtures);
    }

    /**
     * The smallest request that compiles around one finalDensity graph (Overworld geometry, aquifers and ores
     * off, no surface), for tests that probe what the compiler accepts and refuses.
     */
    static FusedNoiseCompiler.Request requestWithFinalDensity(ProgramNode finalDensity) {
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(new Kit()));
        roots.put("finalDensity", finalDensity);
        return new FusedNoiseCompiler.Request(roots, overworldSettings(false, false), null, null,
                overworldPalette(), beardKernel(), null);
    }

    // ------------------------------------------------------------------ fixtures

    /** 1.21.1 Overworld: aquifers and ores, the density search for the preliminary surface, the first fluid-update rule. */
    private static Fixture overworldClassic() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = terrain(kit, 4, 8, kit.blended(0.25, 0.125, 80.0, 160.0, 8.0), false);
        return new Fixture("overworld-classic", new FusedNoiseCompiler.Request(roots, overworldSettings(true, true),
                PositionalRandomFactorySnapshot.xoroshiro(0x1234_5678_9abc_def0L, 0x0fed_cba9_8765_4321L),
                PositionalRandomFactorySnapshot.xoroshiro(-6_147_530_204_000_000_001L, 7_000_000_000_000_000_011L),
                overworldPalette(), beardKernel(), null,
                FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    /** The same terrain under the 1.21.2 fluid-update rule, with a surface program and a legacy ore random source. */
    private static Fixture overworldFluidRuleSince1212() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = terrain(kit, 4, 8, kit.blended(1.0, 1.0, 8.555150000000001, 4.277575000000001, 2.0), false);
        return new Fixture("overworld-fluid-rule-1.21.2-surface", new FusedNoiseCompiler.Request(roots, overworldSettings(true, true),
                PositionalRandomFactorySnapshot.xoroshiro(-1L, 1L),
                PositionalRandomFactorySnapshot.legacy(-4_172_144_997_902_289_642L),
                overworldPalette(), beardKernel(), surface(kit, 1),
                FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    /** 1.21.9 and later: the level function in its find-top-surface form, a surface program, a legacy aquifer source. */
    private static Fixture overworldFindTopSurface() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = terrain(kit, 4, 8, null, true);
        return new Fixture("overworld-find-top-surface", new FusedNoiseCompiler.Request(roots, overworldSettings(true, true),
                PositionalRandomFactorySnapshot.legacy(8_675_309L),
                PositionalRandomFactorySnapshot.xoroshiro(42L, 0L),
                overworldPalette(), beardKernel(), surface(kit, 2),
                FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION));
    }

    /** A datapack-shaped world: odd height, aquifers off, ores on, the level function as a plain value, no aquifer random source. */
    private static Fixture tallPlainLevelOresOnly() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = terrain(kit, 4, 8, null, false);
        var settings = new GeneratorSettingsSnapshot(-48, 352, 336, 40,
                BlockStateDescriptor.of("minecraft:stone"), BlockStateDescriptor.of("minecraft:water"), false, true, 4, 8);
        return new Fixture("tall-plain-level-ores-only", new FusedNoiseCompiler.Request(roots, settings,
                null, PositionalRandomFactorySnapshot.legacy(1L << 40),
                overworldPalette(), beardKernel(), null,
                FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION));
    }

    /** Lava sea, no aquifers, no ores, no random sources. */
    private static Fixture netherLike() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = netherRouter(kit);
        return new Fixture("nether-like", new FusedNoiseCompiler.Request(roots, netherSettings(),
                null, null, netherPalette(), beardKernel(), null,
                FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    /** The Nether-like router with a surface program: needs the shared-surface check although aquifers are off. */
    private static Fixture netherLikeWithSurface() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = netherRouter(kit);
        return new Fixture("nether-like-surface", new FusedNoiseCompiler.Request(roots, netherSettings(),
                null, null, netherPalette(), beardKernel(), surface(kit, 3),
                FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION));
    }

    /** Eight-wide, four-high cells and the End island, with an air default fluid. */
    private static Fixture endLike() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = endRouter(kit);
        var settings = new GeneratorSettingsSnapshot(0, 128, 128, 0,
                BlockStateDescriptor.of("minecraft:end_stone"), BlockStateDescriptor.of("minecraft:air"), false, false, 8, 4);
        var palette = new FusedNoiseCompiler.MaterialPalette(0, 1, 0, 2, 3, 4, 5, 6, 7, 8,
                false, false, false, true, 9, new int[]{0, 6, 6, 6, 6, 6, 6, 6, 6});
        return new Fixture("end-like-surface", new FusedNoiseCompiler.Request(roots, settings,
                null, null, palette, beardKernel(), surface(kit, 4),
                FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    /** Every unary, binary and Ap2 operation, every input, every cache-mode spelling, odd constants; ores on, aquifers off. */
    private static Fixture everyOperation() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(kit));
        ProgramNode x = in("x"), y = in("worldY"), z = in("z");
        // Unary: one operation per leaf, so that each shows in the text.
        List<ProgramNode> unaries = new ArrayList<>();
        for (String operation : List.of("negate", "-", "abs", "square", "cube", "floor", "sqrt", "half_negative", "quarter_negative", "squeeze")) {
            unaries.add(un(operation, bin("add", bin("multiply", x, c(0.0625)), noise(kit, "minecraft:jagged", 1500.0, 0.0))));
        }
        ProgramNode unarySum = sum(unaries);
        // Binary: every spelling.
        ProgramNode binarySum = sum(List.of(
                bin("+", x, y), bin("add", y, z), bin("-", z, x), bin("subtract", x, c(3.0)), bin("*", y, c(0.5)), bin("multiply", z, z),
                bin("/", x, bin("add", un("abs", y), c(1.0))), bin("divide", y, bin("add", un("abs", z), c(2.0))),
                bin("min", x, y), bin("max", y, z)));
        // Ap2: each operation with finite, negative, extreme and infinite right-hand bounds.
        ProgramNode ap2Sum = sum(List.of(
                ap2("add", x, c(1.5), 1.5, 1.5),
                ap2("+", y, noise(kit, "minecraft:temperature", 0.25, 0.0), -1.0, 1.0),
                ap2("multiply", x, noise(kit, "minecraft:vegetation", 0.25, 0.0), -0.5, 0.5),
                ap2("mul", y, c(2.0), 2.0, 2.0),
                ap2("min", z, noise(kit, "minecraft:continentalness", 0.25, 0.0), -1.0E-5, 1.0E10),
                ap2("min", x, c(0.0), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                ap2("max", y, c(-7.0), 4.9E-324, Double.MAX_VALUE),
                ap2("max", z, noise(kit, "minecraft:erosion", 0.25, 0.0), -0.25, 0.75)));
        // Constants the literal writer has to get right: signs, zeros, extremes, non-finite values.
        ProgramNode constantSum = sum(List.of(c(0.0), c(-0.0), c(-1.0), c(1.0 / 3.0), c(100.0), c(1.0E-7), c(-2.5E-300),
                c(Double.MIN_VALUE), c(Double.MAX_VALUE), c(Double.NaN), c(Double.POSITIVE_INFINITY), c(Double.NEGATIVE_INFINITY)));
        // Inputs under both spellings of each axis.
        ProgramNode inputSum = sum(List.of(in("x"), in("worldX"), in("y"), in("worldY"), in("z"), in("worldZ")));
        // Every cache-mode spelling the compiler accepts, around a distinct child each.
        ProgramNode markers = sum(List.of(
                marker("a", "FlatCache", noise(kit, "minecraft:ridge", 0.25, 0.0)),
                marker("b", "FLAT_CACHE", noise(kit, "minecraft:ridge", 0.5, 0.0)),
                marker("c", "ONCE", un("abs", x)), marker("d", "cache_once", un("abs", y)),
                marker("e", "Cache2D", un("abs", z)), marker("f", "CACHE_2D", un("square", x)),
                marker("g", "CacheAllInCell", un("square", y)), marker("h", "ALL_IN_CELL", un("square", z)),
                marker("i", "all_in_cell", un("cube", x)), marker("j", "NONE", un("cube", y)), marker("k", "Transparent", un("cube", z))));
        ProgramNode all = sum(List.of(unarySum, binarySum, ap2Sum, constantSum, inputSum, markers));
        roots.put("finalDensity", interp(4, 8, bin("add", all, flat(noise(kit, "minecraft:cave_entrance", 0.25, 0.0)))));
        roots.put("veinToggle", interp(4, 8, ap2("max", un("abs", y), c(0.0), 0.0, 0.0)));
        return new Fixture("every-operation", new FusedNoiseCompiler.Request(roots, overworldSettings(false, true),
                null, PositionalRandomFactorySnapshot.xoroshiro(0x5555_5555_5555_5555L, 0x2aaa_aaaa_aaaa_aaaaL),
                overworldPalette(), beardKernel(), null,
                FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    /** Spaghetti rarity samplers, plain and shifted noise, XYZ shifts, lazy branches, the blend nodes and spline shapes. */
    private static Fixture spaghettiAndBlend() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(kit));
        ProgramNode x = in("x"), y = in("y"), z = in("z");
        ProgramNode rarity1 = new ProgramNode.WeirdScaledSampler(noise(kit, "minecraft:spaghetti_3d_rarity", 2.0, 1.0),
                kit.noise("minecraft:spaghetti_3d_1", -7, 1.0, 1.0), "TYPE1", ValueType.FP64, BLOCK);
        ProgramNode rarity2 = new ProgramNode.WeirdScaledSampler(un("abs", x),
                kit.noise("minecraft:spaghetti_2d", -7, 1.0, 1.0, 0.0, 1.0), "TYPE2", ValueType.FP64, BLOCK);
        ProgramNode shifted = new ProgramNode.ShiftedNoise("minecraft:cave_layer", kit.noise("minecraft:cave_layer", -8, 1.0),
                0.5, 8.0, shift(kit, "minecraft:offset", "XYZ", 0.25), shift(kit, "minecraft:offset", "X0Z", 0.125),
                shift(kit, "minecraft:offset", "ZX0", 1.0), ValueType.FP64, BLOCK);
        // A selector chosen from a noise: both branches are lazy and each holds work of its own.
        ProgramNode select = new ProgramNode.Select("select", ValueType.FP64, BLOCK, bin("subtract", y, c(-20.0)),
                bin("multiply", rarity1, c(2.0)), bin("add", rarity2, shifted));
        ProgramNode range = new ProgramNode.RangeChoice(noise(kit, "minecraft:pillar", 25.0, 0.3333333333333333), -0.5, 0.25,
                bin("max", select, c(-1.0)), blendWrapped(), ValueType.FP64, BLOCK);
        // Spline shapes: a constant spline, a spline whose coordinate is a spline, equal coordinates built twice.
        ProgramNode constantSpline = new ProgramNode.Spline(new ProgramNode.SplineConstant(-0.0625f), ValueType.FP64, WORLD);
        ProgramNode coordinate = new ProgramNode.Spline(mp(noise(kit, "minecraft:erosion", 0.25, 0.0),
                List.of(-1.0f, 0.0f, 1.0f), List.of(sc(-1.0f), sc(0.0f), sc(1.0f)), List.of(0.0f, 1.0f, 0.0f)), ValueType.FP64, WORLD);
        ProgramNode nested = new ProgramNode.Spline(mp(coordinate, List.of(-1.0f, 1.0f),
                List.of(mp(noise(kit, "minecraft:ridge", 0.25, 0.0), List.of(-0.5f, 0.5f), List.of(sc(2.0f), sc(-2.0f)), List.of(0.5f, 0.5f)),
                        mp(noise(kit, "minecraft:ridge", 0.25, 0.0), List.of(-0.25f, 0.75f), List.of(sc(-3.0f), sc(3.0f)), List.of(-0.5f, 0.25f))),
                List.of(0.0f, 0.0f)), ValueType.FP64, WORLD);
        ProgramNode all = sum(List.of(range, constantSpline, nested));
        roots.put("finalDensity", interp(4, 8, bin("add", all, new ProgramNode.Beardifier(ValueType.FP64, BLOCK))));
        roots.put("veinGap", sum(List.of(rarity1, shifted)));
        roots.put("veinRidged", interp(4, 8, rarity2));
        return new Fixture("spaghetti-and-blend", new FusedNoiseCompiler.Request(roots, overworldSettings(true, false),
                PositionalRandomFactorySnapshot.legacy(-99L), null, overworldPalette(), beardKernel(), surface(kit, 5),
                FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION));
    }

    /** Two sets of blended-noise parameters and two End-island states in one graph, with no aquifers and no ores. */
    private static Fixture blendedAndEndParameters() {
        Kit kit = new Kit();
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(kit));
        ProgramNode blendedA = new ProgramNode.BlendedNoise(kit.blended(0.25, 0.125, 80.0, 160.0, 8.0), ValueType.FP64, BLOCK);
        ProgramNode blendedB = new ProgramNode.BlendedNoise(kit.blended(2.0, 0.5, 40.0, 80.0, 4.0), ValueType.FP64, BLOCK);
        ProgramNode islandA = new ProgramNode.EndIsland(kit.end(17.0, -3.0), ValueType.FP64, COLUMN);
        ProgramNode islandB = new ProgramNode.EndIsland(kit.end(-5.0, 11.0), ValueType.FP64, COLUMN);
        roots.put("finalDensity", interp(4, 8, sum(List.of(blendedA, blendedB, flat(islandA), islandB))));
        roots.put("initialDensityWithoutJaggedness", bin("add", flat(islandA), bin("multiply", c(0.5), flat(islandB))));
        return new Fixture("blended-and-end-parameters", new FusedNoiseCompiler.Request(roots, overworldSettings(false, false),
                null, null, overworldPalette(), beardKernel(), null,
                FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH));
    }

    // ------------------------------------------------------------------ routers

    /**
     * An Overworld-shaped router: flat-cached shifted noises feed nested splines, the depth, the sloped
     * density under an interpolator, a Y-independent spline outside the interpolator (it is hoisted per
     * column), the ore-vein and aquifer roots.  The preliminary-surface density keeps to Y-independent flat
     * caches, as the compiler requires wherever aquifers or a surface program read it.
     */
    private static Map<String, ProgramNode> terrain(Kit kit, int w, int h, BlendedNoiseParameters blended, boolean findTopSurface) {
        ProgramNode offsetX = flat(once(shift(kit, "minecraft:offset", "X0Z", 0.25)));
        ProgramNode offsetZ = flat(once(shift(kit, "minecraft:offset", "ZX0", 0.25)));
        ProgramNode continents = flat(shiftedNoise(kit, "minecraft:continentalness", 0.25, offsetX, offsetZ));
        ProgramNode erosion = flat(shiftedNoise(kit, "minecraft:erosion", 0.25, offsetX, offsetZ));
        ProgramNode ridges = flat(shiftedNoise(kit, "minecraft:ridge", 0.25, offsetX, offsetZ));
        ProgramNode folded = bin("multiply", c(-3.0), bin("subtract",
                un("abs", bin("subtract", un("abs", ridges), c(0.6666666666666666))), c(0.3333333333333333)));
        // The same coordinates built a second time: equal graphs, different objects.
        ProgramNode continentsAgain = flat(shiftedNoise(kit, "minecraft:continentalness", 0.25,
                flat(once(shift(kit, "minecraft:offset", "X0Z", 0.25))), flat(once(shift(kit, "minecraft:offset", "ZX0", 0.25)))));
        ProgramNode offset = new ProgramNode.Spline(terrainSpline(continents, erosion, folded, 0.044f), ValueType.FP64, COLUMN);
        ProgramNode factor = new ProgramNode.Spline(terrainSpline(continentsAgain, erosion, folded, 6.25f), ValueType.FP64, COLUMN);
        ProgramNode jaggedness = new ProgramNode.Spline(terrainSpline(continents, erosion, ridges, 0.0f), ValueType.FP64, COLUMN);
        ProgramNode gradient = bin("add", c(1.5), bin("multiply", c(-2.0), bin("min", bin("max",
                bin("divide", bin("subtract", in("y"), c(-64.0)), c(384.0)), c(0.0)), c(1.0))));
        ProgramNode depth = bin("add", bin("add", gradient, c(-0.50375)), offset);
        ProgramNode jagged = bin("multiply", jaggedness, un("half_negative", noise(kit, "minecraft:jagged", 1500.0, 0.0)));
        ProgramNode slope = un("quarter_negative", bin("multiply", bin("add", depth, jagged), factor));
        ProgramNode threeD = blended != null ? new ProgramNode.BlendedNoise(blended, ValueType.FP64, BLOCK)
                : noise(kit, "minecraft:cave_cheese", 1.0, 0.6666666666666666);
        ProgramNode sloped = interp(w, h, new ProgramNode.BlendDensity(bin("add", slope, bin("multiply", c(0.125), threeD)), ValueType.FP64, BLOCK));
        ProgramNode beard = new ProgramNode.Beardifier(ValueType.FP64, BLOCK);
        Map<String, ProgramNode> roots = new LinkedHashMap<>();
        roots.put("finalDensity", bin("add", bin("add", un("squeeze", bin("multiply", c(0.64), sloped)), beard),
                bin("multiply", c(0.0078125), offset)));
        // Y-independent and expensive, outside the interpolator: hoisted to one value per column.
        // veinToggle and veinRidged read the interpolator's running value, veinGap an expensive Y-independent noise.
        ProgramNode veinA = noise(kit, "minecraft:ore_vein_a", 4.0, 4.0);
        ProgramNode veinB = noise(kit, "minecraft:ore_vein_b", 4.0, 4.0);
        roots.put("veinToggle", interp(w, h, new ProgramNode.RangeChoice(in("y"), -60.0, 51.0, veinA, c(0.0), ValueType.FP64, BLOCK)));
        roots.put("veinRidged", interp(w, h, bin("add", c(-0.07999999821186066), bin("max", un("abs", veinA), un("abs", veinB)))));
        roots.put("veinGap", bin("multiply", c(2.0), noise(kit, "minecraft:ore_gap", 1.0, 0.0)));
        roots.put("barrierNoise", noise(kit, "minecraft:aquifer_barrier", 1.0, 0.5));
        roots.put("fluidLevelFloodedness", noise(kit, "minecraft:aquifer_fluid_level_floodedness", 1.0, 0.67));
        roots.put("fluidLevelSpread", noise(kit, "minecraft:aquifer_fluid_level_spread", 1.0, 0.7142857142857143));
        roots.put("lava", noise(kit, "minecraft:aquifer_lava", 1.0, 1.0));
        roots.put("erosion", erosion);
        roots.put("depth", depth);
        ProgramNode surfaceDensity = bin("multiply", c(4.0), bin("multiply", bin("add", bin("add", c(1.5), c(-0.50375)), offset), factor));
        if (findTopSurface) {
            // The game's find-top-surface function: density, upper bound, then lower bound and step.
            ProgramNode upper = bin("add", c(320.0), un("abs", continents));
            ProgramNode step = new ProgramNode.Binary(FusedNoiseCompiler.FIND_TOP_SURFACE_STEP, ValueType.FP64, WORLD, c(-64.0), c(8.0));
            ProgramNode from = new ProgramNode.Binary(FusedNoiseCompiler.FIND_TOP_SURFACE_FROM, ValueType.FP64, WORLD, upper, step);
            roots.put("initialDensityWithoutJaggedness",
                    new ProgramNode.Binary(FusedNoiseCompiler.FIND_TOP_SURFACE, ValueType.FP64, WORLD, surfaceDensity, from));
        } else {
            roots.put("initialDensityWithoutJaggedness", bin("add", surfaceDensity, bin("multiply", c(-0.0078125), in("y"))));
        }
        return roots;
    }

    /** A spline over continents, erosion and a third coordinate, three levels deep, with constants at the leaves. */
    private static ProgramNode.SplineNode terrainSpline(ProgramNode continents, ProgramNode erosion, ProgramNode third, float base) {
        ProgramNode.SplineNode thirdLevel = mp(third, List.of(-1.0f, -0.4f, 0.0f, 0.4f, 1.0f),
                List.of(sc(base), sc(base + 0.25f), sc(base + 0.5f), sc(base + 0.25f), sc(base)),
                List.of(0.0f, 0.5f, 0.0f, -0.5f, 0.0f));
        ProgramNode.SplineNode erosionLevel = mp(erosion, List.of(-0.78f, -0.375f, 0.45f, 1.0f),
                List.of(thirdLevel, sc(base - 0.125f), thirdLevel, sc(base + 0.0625f)),
                List.of(0.0f, 0.25f, -0.125f, 0.0f));
        return mp(continents, List.of(-1.1f, -0.51f, -0.19f, 0.03f, 1.0f),
                List.of(sc(base - 1.0f), erosionLevel, erosionLevel, sc(base + 0.0625f), erosionLevel),
                List.of(0.35f, 0.0f, 0.1f, 0.25f, 0.0f));
    }

    /** A Nether-shaped router: two-dimensional noises in flat caches, a clamped Y gradient pair, no interpolated ore roots. */
    private static Map<String, ProgramNode> netherRouter(Kit kit) {
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(kit));
        ProgramNode ceiling = bin("add", bin("multiply", c(-0.05), bin("subtract", in("y"), c(0.0))), c(2.5));
        ProgramNode floor = bin("add", bin("multiply", c(0.0625), bin("subtract", in("y"), c(31.0))), c(-1.5));
        ProgramNode caves = noise(kit, "minecraft:nether_wart", 1.0, 0.75);
        ProgramNode column = flat(shiftedNoise(kit, "minecraft:nether_cheese", 0.5, c(0.0), c(0.0)));
        roots.put("finalDensity", interp(4, 8, bin("add", un("squeeze", bin("min", bin("add", ceiling, caves), bin("max", floor, column))),
                new ProgramNode.Beardifier(ValueType.FP64, BLOCK))));
        roots.put("initialDensityWithoutJaggedness", bin("add", column, bin("multiply", c(0.015625), in("y"))));
        roots.put("erosion", column);
        return roots;
    }

    /** An End-shaped router: the island in a flat cache and under the interpolator, plus a noise. */
    private static Map<String, ProgramNode> endRouter(Kit kit) {
        Map<String, ProgramNode> roots = new LinkedHashMap<>(plainRoots(kit));
        ProgramNode island = new ProgramNode.EndIsland(kit.end(0.0, 0.0), ValueType.FP64, COLUMN);
        ProgramNode ground = bin("add", flat(island), bin("multiply", c(0.0625), bin("subtract", c(70.0), in("y"))));
        roots.put("finalDensity", interp(8, 4, bin("add", un("squeeze", bin("multiply", c(0.64), ground)),
                noise(kit, "minecraft:end_base", 0.25, 0.0))));
        roots.put("initialDensityWithoutJaggedness", flat(island));
        roots.put("erosion", flat(island));
        roots.put("depth", ground);
        return roots;
    }

    /** All eleven roots the compiler requires, each a cheap noise or constant; fixtures replace the ones they exercise. */
    private static Map<String, ProgramNode> plainRoots(Kit kit) {
        Map<String, ProgramNode> roots = new LinkedHashMap<>();
        roots.put("finalDensity", noise(kit, "minecraft:plain_final", 0.25, 0.25));
        roots.put("initialDensityWithoutJaggedness", bin("add", noise(kit, "minecraft:plain_initial", 0.25, 0.0), c(-0.125)));
        roots.put("barrierNoise", noise(kit, "minecraft:aquifer_barrier", 1.0, 0.5));
        roots.put("fluidLevelFloodedness", noise(kit, "minecraft:aquifer_fluid_level_floodedness", 1.0, 0.67));
        roots.put("fluidLevelSpread", noise(kit, "minecraft:aquifer_fluid_level_spread", 1.0, 0.7142857142857143));
        roots.put("lava", noise(kit, "minecraft:aquifer_lava", 1.0, 1.0));
        roots.put("erosion", noise(kit, "minecraft:erosion", 0.25, 0.0));
        roots.put("depth", bin("add", c(0.5), noise(kit, "minecraft:depth", 0.25, 0.0)));
        roots.put("veinToggle", c(0.0));
        roots.put("veinRidged", c(-0.07999999821186066));
        roots.put("veinGap", c(0.0));
        return roots;
    }

    private static ProgramNode blendWrapped() {
        return bin("add", new ProgramNode.BlendDensity(new ProgramNode.BlendOffset(ValueType.FP64, BLOCK), ValueType.FP64, BLOCK),
                bin("multiply", new ProgramNode.BlendAlpha(ValueType.FP64, BLOCK), c(0.25)));
    }

    // ------------------------------------------------------------------ settings, palette, kernel, surface

    private static GeneratorSettingsSnapshot overworldSettings(boolean aquifers, boolean ores) {
        var base = GeneratorSettingsSnapshot.overworld();
        return new GeneratorSettingsSnapshot(base.minY(), base.height(), base.logicalHeight(), base.seaLevel(),
                base.defaultBlock(), base.defaultFluid(), aquifers, ores, base.cellWidth(), base.cellHeight());
    }

    private static GeneratorSettingsSnapshot netherSettings() {
        return new GeneratorSettingsSnapshot(0, 128, 128, 32,
                BlockStateDescriptor.of("minecraft:netherrack"), BlockStateDescriptor.of("minecraft:lava"), false, false, 4, 8);
    }

    private static FusedNoiseCompiler.MaterialPalette overworldPalette() {
        // Air, stone, water, lava, then the ore-vein states; flags as the mod derives them (fluid 1, motion 2, not air 4).
        return new FusedNoiseCompiler.MaterialPalette(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, true, false, false, false,
                10, new int[]{0, 6, 5, 5, 6, 6, 6, 6, 6, 6});
    }

    private static FusedNoiseCompiler.MaterialPalette netherPalette() {
        return new FusedNoiseCompiler.MaterialPalette(0, 1, 2, 2, 4, 5, 6, 7, 8, 9, false, true, true, false,
                10, new int[]{0, 6, 5, 5, 6, 6, 6, 6, 6, 6});
    }

    /** 24 x 24 x 24 beardifier kernel values from a fixed pattern. */
    private static float[] beardKernel() {
        float[] kernel = new float[24 * 24 * 24];
        for (int i = 0; i < kernel.length; i++) kernel[i] = ((i * 37) % 251 - 125) / 64.0f;
        return kernel;
    }

    /** A surface program with every opcode the interpreter has; the compiler only lays it into the tables. */
    private static SurfaceProgram surface(Kit kit, int variant) {
        int none = 0;
        int[] code = {
                SurfaceProgram.OP_BIOME, 0, none, none, none, 3,
                SurfaceProgram.OP_NOISE | SurfaceProgram.NEGATE, 0, 0, none, none, 3,
                SurfaceProgram.OP_RETURN_STATE, 1, none, none, none, none,
                SurfaceProgram.OP_VERTICAL_GRADIENT, 0, -64, -60, none, 5,
                SurfaceProgram.OP_RETURN_BAND, none, none, none, none, none,
                SurfaceProgram.OP_Y_ABOVE | SurfaceProgram.NEGATE, 63, 1, none, none, 7,
                SurfaceProgram.OP_WATER, -1, 0, 0, none, 7,
                SurfaceProgram.OP_TEMPERATURE, none, none, none, none, 9,
                SurfaceProgram.OP_STEEP, none, none, none, none, 9,
                SurfaceProgram.OP_HOLE | SurfaceProgram.NEGATE, none, none, none, none, 11,
                SurfaceProgram.OP_ABOVE_PRELIMINARY, none, none, none, none, 12,
                SurfaceProgram.OP_STONE_DEPTH, 0, 1, 0, 1, 12,
                SurfaceProgram.OP_RETURN_NULL, none, none, none, none, none,
        };
        int[] clayBands = new int[192];
        for (int i = 0; i < clayBands.length; i++) clayBands[i] = (i * 7 + variant) % 5;
        NoiseParameters.CapturedNoise[] noises = new NoiseParameters.CapturedNoise[9];
        String[] names = {"surface", "surface_secondary", "clay_bands_offset", "badlands_pillar", "badlands_pillar_roof",
                "badlands_surface", "iceberg_pillar", "iceberg_pillar_roof", "iceberg_surface"};
        for (int i = 0; i < noises.length; i++) noises[i] = kit.noise("minecraft:" + names[i], -6 - i % 3, 1.0, 1.0, 0.5).captured();
        var temperature = new SurfaceProgram.SimplexOctaves(0.0625, 1.0, List.of(kit.permutation(), List.of(), kit.permutation()));
        var frozen = new SurfaceProgram.SimplexOctaves(0.125, 2.0, List.of(kit.permutation()));
        var info = new SurfaceProgram.SimplexOctaves(0.015625, 0.5, List.of(kit.permutation(), kit.permutation()));
        return new SurfaceProgram(code, new int[]{0b101, 0b010}, 1,
                List.of(kit.noise("minecraft:surface_condition_a", -3, 1.0, 1.0).captured(),
                        kit.noise("minecraft:surface_condition_b", -4, 1.0, 0.5).captured()),
                new double[]{-0.5, 0.5, 0.1, 0.9},
                List.of(PositionalRandomFactorySnapshot.xoroshiro(11L + variant, 13L), PositionalRandomFactorySnapshot.legacy(17L)),
                noises[0], noises[1], noises[2], noises[3], noises[4], noises[5], noises[6], noises[7], noises[8],
                PositionalRandomFactorySnapshot.xoroshiro(0x1111_2222_3333_4444L, 0x5555_6666_7777_8888L + variant),
                clayBands, new int[]{0, 22, 25, 2, 20, 4}, 3, 4, 1,
                new float[]{0.8f, -0.5f, 0.2f}, new int[]{0, SurfaceProgram.BIOME_FROZEN_MODIFIER,
                        SurfaceProgram.BIOME_ERODED_BADLANDS | SurfaceProgram.BIOME_FROZEN_OCEAN},
                variant % 2 == 0, 0x0123_4567_89ab_cdefL * variant, temperature, frozen, info, "fixture-surface-v1|variant=" + variant);
    }

    // ------------------------------------------------------------------ node helpers

    private static ProgramNode c(double value) {
        return new ProgramNode.Constant(ValueType.FP64, value, WORLD);
    }

    private static ProgramNode in(String name) {
        return new ProgramNode.Input(name, ValueType.FP64, BLOCK);
    }

    private static ProgramNode un(String operation, ProgramNode child) {
        return new ProgramNode.Unary(operation, ValueType.FP64, BLOCK, child);
    }

    private static ProgramNode bin(String operation, ProgramNode left, ProgramNode right) {
        return new ProgramNode.Binary(operation, ValueType.FP64, BLOCK, left, right);
    }

    private static ProgramNode ap2(String operation, ProgramNode left, ProgramNode right, double rightMin, double rightMax) {
        return new ProgramNode.Ap2(operation, ValueType.FP64, BLOCK, left, right, rightMin, rightMax);
    }

    private static ProgramNode marker(String name, String cacheMode, ProgramNode child) {
        return new ProgramNode.Marker(name, cacheMode, ValueType.FP64, BLOCK, child, Map.of());
    }

    private static ProgramNode flat(ProgramNode child) {
        return marker("FlatCache", "FLAT_CACHE", child);
    }

    private static ProgramNode once(ProgramNode child) {
        return marker("CacheOnce", "ONCE", child);
    }

    private static ProgramNode interp(int horizontal, int vertical, ProgramNode child) {
        return new ProgramNode.Interpolated(child, new InterpolationGeometry(horizontal, vertical), ValueType.FP64);
    }

    /** Left-leaning sum of the terms, as the lowerer nests an n-ary addition. */
    private static ProgramNode sum(List<ProgramNode> terms) {
        ProgramNode total = terms.get(0);
        for (int i = 1; i < terms.size(); i++) total = bin("add", total, terms.get(i));
        return total;
    }

    private static ProgramNode noise(Kit kit, String key, double xzScale, double yScale) {
        return new ProgramNode.Noise(key, kit.noise(key, -7, 1.0, 1.0, 0.5), xzScale, yScale, ValueType.FP64, BLOCK);
    }

    private static ProgramNode shift(Kit kit, String key, String axis, double scale) {
        return new ProgramNode.Shift(key, kit.noise(key, -3, 1.0, 1.0, 1.0, 0.0), axis, scale, ValueType.FP64, COLUMN);
    }

    private static ProgramNode shiftedNoise(Kit kit, String key, double xzScale, ProgramNode shiftX, ProgramNode shiftZ) {
        return new ProgramNode.ShiftedNoise(key, kit.noise(key, -9, 1.0, 1.0, 0.0, 1.0, 1.0), xzScale, 0.0,
                shiftX, c(0.0), shiftZ, ValueType.FP64, COLUMN);
    }

    private static ProgramNode.SplineNode sc(float value) {
        return new ProgramNode.SplineConstant(value);
    }

    private static ProgramNode.SplineNode mp(ProgramNode coordinate, List<Float> locations, List<ProgramNode.SplineNode> values,
                                             List<Float> derivatives) {
        return new ProgramNode.SplineMultipoint(coordinate, locations, values, derivatives);
    }

    // ------------------------------------------------------------------ seeded noise tables

    /** Noise tables from a fixed seed; one set of parameters per key, so nodes that share a key share the tables. */
    private static final class Kit {
        private final Map<String, NoiseParameters> noises = new HashMap<>();
        private long state = 0x2545F4914F6CDD1DL;

        private int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) ((state >>> 33) % bound);
        }

        List<Integer> permutation() {
            Integer[] values = new Integer[256];
            for (int i = 0; i < 256; i++) values[i] = i;
            for (int i = 255; i > 0; i--) {
                int j = next(i + 1);
                Integer swap = values[i]; values[i] = values[j]; values[j] = swap;
            }
            return List.of(values);
        }

        private NoiseParameters.PerlinNoiseSnapshot perlin(int firstOctave, double... amplitudes) {
            List<Double> amps = new ArrayList<>();
            List<NoiseParameters.ImprovedNoiseSnapshot> levels = new ArrayList<>();
            for (double amplitude : amplitudes) {
                amps.add(amplitude);
                // A zero amplitude leaves a null slot, as the game's PerlinNoise does.
                levels.add(amplitude == 0.0 ? null : new NoiseParameters.ImprovedNoiseSnapshot(
                        next(65536) / 256.0, next(65536) / 256.0, next(65536) / 256.0, permutation()));
            }
            return new NoiseParameters.PerlinNoiseSnapshot(firstOctave, amps, levels);
        }

        NoiseParameters noise(String key, int firstOctave, double... amplitudes) {
            NoiseParameters known = noises.get(key);
            if (known != null) return known;
            List<Double> amps = new ArrayList<>();
            for (double amplitude : amplitudes) amps.add(amplitude);
            var captured = new NoiseParameters.CapturedNoise(1234L + noises.size(), 0.3333333333333333 * (amplitudes.length + 1) / amplitudes.length,
                    perlin(firstOctave, amplitudes), perlin(firstOctave, amplitudes));
            var parameters = new NoiseParameters(key, firstOctave, amps, 0L, captured);
            noises.put(key, parameters);
            return parameters;
        }

        BlendedNoiseParameters blended(double xzScale, double yScale, double xzFactor, double yFactor, double smear) {
            double[] sixteen = new double[16];
            java.util.Arrays.fill(sixteen, 1.0);
            double[] eight = new double[8];
            java.util.Arrays.fill(eight, 1.0);
            return new BlendedNoiseParameters(4321L, perlin(-15, sixteen), perlin(-15, sixteen), perlin(-7, eight),
                    xzScale, yScale, xzFactor, yFactor, smear);
        }

        EndIslandParameters end(double xOffset, double yOffset) {
            return new EndIslandParameters(xOffset, yOffset, permutation());
        }
    }
}
