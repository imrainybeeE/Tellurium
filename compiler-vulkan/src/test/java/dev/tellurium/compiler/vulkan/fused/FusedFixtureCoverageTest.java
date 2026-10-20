// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage of {@link FusedFixtures} is enforced here, not claimed: whatever the compiler switches over is
 * enumerated, and a construct that no fixture exercises fails the build, so that a pinned hash in
 * {@code FusedNativeGoldenTest} always stands for the construct it is meant to pin.
 *
 * <p>Node kinds and enums are enumerated by reflection.  The operation, cache-mode and input names are
 * strings in the compiler, which cannot be listed by reflection; they are listed here and checked from both
 * sides: every listed name must be used by a fixture and accepted by the compiler, and a probe of names the
 * compiler is known not to support must be refused.  A new operation that the compiler starts to accept shows
 * up as a probe that is no longer refused; the person adding it extends the lists and a fixture.</p>
 */
class FusedFixtureCoverageTest {
    private static final List<FusedFixtures.Fixture> FIXTURES = FusedFixtures.all();

    /** The operation names the compiler's unary, binary and Ap2 emitters switch over (Ap2 as canonicalised by the node). */
    private static final Set<String> UNARY = Set.of("negate", "-", "abs", "square", "cube", "floor", "sqrt",
            "half_negative", "quarter_negative", "squeeze");
    private static final Set<String> BINARY = Set.of("+", "add", "-", "subtract", "*", "multiply", "/", "divide", "min", "max");
    private static final Set<String> AP2 = Set.of("add", "multiply", "min", "max");
    /** Cache modes after the compiler's own normalisation (upper case, underscores removed). */
    private static final Set<String> CACHE_MODES = Set.of("FLATCACHE", "ONCE", "CACHEONCE", "CACHE2D", "CACHEALLINCELL",
            "ALLINCELL", "NONE", "TRANSPARENT");
    private static final Set<String> INPUTS = Set.of("x", "worldX", "y", "worldY", "z", "worldZ");

    // ------------------------------------------------------------------ node kinds

    @Test
    void everyProgramNodeKindIsUsedByAFixture() {
        Set<Class<?>> used = new java.util.HashSet<>();
        forEachNode(node -> used.add(node.getClass()));
        Set<String> missing = new TreeSet<>();
        for (Class<?> kind : ProgramNode.class.getPermittedSubclasses()) if (!used.contains(kind)) missing.add(kind.getSimpleName());
        assertEquals(Set.<String>of(), missing, "ProgramNode kinds that no fixture uses");
    }

    @Test
    void everySplineNodeKindIsUsedByAFixture() {
        Set<Class<?>> used = new java.util.HashSet<>();
        int[] deepest = {0};
        forEachNode(node -> {
            if (node instanceof ProgramNode.Spline spline) collectSplineKinds(spline.spline(), 1, used, deepest);
        });
        Set<String> missing = new TreeSet<>();
        for (Class<?> kind : ProgramNode.SplineNode.class.getPermittedSubclasses()) if (!used.contains(kind)) missing.add(kind.getSimpleName());
        assertEquals(Set.<String>of(), missing, "SplineNode kinds that no fixture uses");
        assertTrue(deepest[0] >= 3, "a fixture needs a spline with nested multipoints, deepest is " + deepest[0]);
    }

    @Test
    void thereIsASplineWhoseCoordinateIsAnotherSplineAndOneWithEqualCoordinatesBuiltTwice() {
        boolean[] splineCoordinate = {false};
        forEachNode(node -> {
            if (node instanceof ProgramNode.Spline spline && spline.spline() instanceof ProgramNode.SplineMultipoint point
                    && point.coordinate() instanceof ProgramNode.Spline) splineCoordinate[0] = true;
        });
        assertTrue(splineCoordinate[0], "no fixture has a spline whose coordinate is a spline");
        // Equal subgraphs that are different objects reach the compiler's coordinate de-duplication.
        Map<String, Set<ProgramNode>> byFingerprint = new LinkedHashMap<>();
        forEachNode(node -> {
            if (node instanceof ProgramNode.Spline spline && spline.spline() instanceof ProgramNode.SplineMultipoint) {
                for (ProgramNode coordinate : node.children()) {
                    Set<ProgramNode> same = byFingerprint.computeIfAbsent(dev.tellurium.semantic.program.WorldgenProgram.nodeFingerprint(coordinate),
                            key -> Collections.newSetFromMap(new IdentityHashMap<>()));
                    same.add(coordinate);
                }
            }
        });
        assertTrue(byFingerprint.values().stream().anyMatch(same -> same.size() > 1), "no fixture builds one spline coordinate twice");
    }

    private static void collectSplineKinds(ProgramNode.SplineNode node, int depth, Set<Class<?>> used, int[] deepest) {
        used.add(node.getClass());
        deepest[0] = Math.max(deepest[0], depth);
        if (node instanceof ProgramNode.SplineMultipoint point) for (var value : point.values()) collectSplineKinds(value, depth + 1, used, deepest);
    }

    // ------------------------------------------------------------------ operations and names

    @Test
    void everyOperationTheCompilerEmitsIsUsedByAFixture() {
        Set<String> unary = new TreeSet<>(), binary = new TreeSet<>(), ap2 = new TreeSet<>();
        forEachNode(node -> {
            if (node instanceof ProgramNode.Unary u) unary.add(u.operation());
            if (node instanceof ProgramNode.Binary b) binary.add(b.operation());
            if (node instanceof ProgramNode.Ap2 a) ap2.add(a.operation());
        });
        assertEquals(Set.<String>of(), difference(UNARY, unary), "unary operations that no fixture uses");
        assertEquals(Set.<String>of(), difference(BINARY, binary), "binary operations that no fixture uses");
        assertEquals(Set.<String>of(), difference(AP2, ap2), "Ap2 operations that no fixture uses");
    }

    @Test
    void theCompilerAcceptsEveryListedOperationAndRefusesTheOnesItIsKnownNotToSupport() {
        ProgramNode x = input("x");
        for (String operation : UNARY) {
            assertDoesNotThrow(() -> compile(new ProgramNode.Unary(operation, ValueType.FP64, EvaluationDomain.BLOCK, x)), "unary " + operation);
        }
        // Known to ProgramNode but not lowered by the compiler, and names no node knows at all.
        for (String operation : List.of("tanh", "reciprocal", "negative")) {
            refused("Unknown unary", new ProgramNode.Unary(operation, ValueType.FP64, EvaluationDomain.BLOCK, x));
        }
        for (String operation : BINARY) {
            assertDoesNotThrow(() -> compile(new ProgramNode.Binary(operation, ValueType.FP64, EvaluationDomain.BLOCK, x, x)), "binary " + operation);
        }
        for (String operation : List.of("floor_div", "floor_mod", "pow", "mod")) {
            refused("Unsupported binary", new ProgramNode.Binary(operation, ValueType.FP64, EvaluationDomain.BLOCK, x, x));
        }
        for (String operation : AP2) {
            assertDoesNotThrow(() -> compile(new ProgramNode.Ap2(operation, ValueType.FP64, EvaluationDomain.BLOCK, x, x, -1.0, 1.0)), "ap2 " + operation);
        }
    }

    @Test
    void everyCacheModeSpellingIsUsedAndTheCompilerRefusesAnUnknownMode() {
        Set<String> used = new TreeSet<>();
        forEachNode(node -> {
            if (node instanceof ProgramNode.Marker marker) used.add(marker.cacheMode().toUpperCase(Locale.ROOT).replace("_", ""));
        });
        assertEquals(Set.<String>of(), difference(CACHE_MODES, used), "cache modes that no fixture uses");
        ProgramNode x = input("x");
        for (String mode : CACHE_MODES) {
            assertDoesNotThrow(() -> compile(new ProgramNode.Marker("m", mode, ValueType.FP64, EvaluationDomain.BLOCK, x, Map.of())), mode);
        }
        refused("Unknown marker cache mode", new ProgramNode.Marker("m", "Cache3D", ValueType.FP64, EvaluationDomain.BLOCK, x, Map.of()));
    }

    @Test
    void everyInputNameShiftAxisAndRarityMapperIsUsedAndAnUnknownInputIsRefused() {
        Set<String> inputs = new TreeSet<>(), axes = new TreeSet<>(), mappers = new TreeSet<>();
        forEachNode(node -> {
            if (node instanceof ProgramNode.Input i) inputs.add(i.name());
            if (node instanceof ProgramNode.Shift s) axes.add(s.axis());
            if (node instanceof ProgramNode.WeirdScaledSampler w) mappers.add(w.rarityMapper());
        });
        assertEquals(Set.<String>of(), difference(INPUTS, inputs), "inputs that no fixture reads");
        assertEquals(Set.of("XYZ", "X0Z", "ZX0"), axes);
        assertEquals(Set.of("TYPE1", "TYPE2"), mappers);
        for (String name : INPUTS) assertDoesNotThrow(() -> compile(input(name)), name);
        refused("Unsupported input", input("w"));
    }

    @Test
    void theCompilerOnlyTakesFp64NodesOutsideSplines() {
        refused("Only FP64 router nodes", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.WORLD));
        refused("Only FP64 router nodes", new ProgramNode.Constant(ValueType.INT32, 1, EvaluationDomain.WORLD));
    }

    // ------------------------------------------------------------------ request switches

    @Test
    void everyValueOfEveryRequestSwitchIsUsed() {
        Set<FusedNoiseCompiler.FluidUpdates> fluidUpdates = new java.util.HashSet<>();
        Set<FusedNoiseCompiler.PreliminarySurface> preliminary = new java.util.HashSet<>();
        Set<String> switches = new TreeSet<>();
        for (var fixture : FIXTURES) {
            var request = fixture.request();
            fluidUpdates.add(request.fluidUpdates());
            preliminary.add(request.preliminarySurface());
            var settings = request.settings();
            switches.add("aquifers " + settings.aquifersEnabled());
            switches.add("ores " + settings.oresEnabled());
            switches.add("surface " + (request.surface() != null));
            switches.add("aquifer random " + describe(request.aquiferRandom()));
            switches.add("ore random " + describe(request.oreRandom()));
            switches.add("cell " + settings.cellWidth() + "x" + settings.cellHeight());
            var palette = request.palette();
            if (palette.defaultFluidIsWater()) switches.add("default fluid water");
            if (palette.defaultFluidIsLava()) switches.add("default fluid lava");
            if (palette.defaultFluidIsLavaDefaultState()) switches.add("default fluid lava default state");
            if (palette.defaultFluidIsAir()) switches.add("default fluid air");
        }
        assertEquals(Set.of(FusedNoiseCompiler.FluidUpdates.values()), fluidUpdates);
        assertEquals(Set.of(FusedNoiseCompiler.PreliminarySurface.values()), preliminary);
        Set<String> expected = new TreeSet<>(List.of("aquifers true", "aquifers false", "ores true", "ores false",
                "surface true", "surface false", "cell 4x8", "cell 8x4",
                "aquifer random none", "aquifer random LEGACY", "aquifer random XOROSHIRO",
                "ore random none", "ore random LEGACY", "ore random XOROSHIRO",
                "default fluid water", "default fluid lava", "default fluid lava default state", "default fluid air"));
        assertEquals(Set.<String>of(), difference(expected, switches), "request switch values that no fixture uses");
    }

    @Test
    void bothFormsOfTheLevelFunctionAreUsed() {
        boolean findTop = false, plain = false;
        for (var fixture : FIXTURES) {
            if (fixture.request().preliminarySurface() != FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION) continue;
            var root = fixture.request().roots().get("initialDensityWithoutJaggedness");
            if (root instanceof ProgramNode.Binary binary && binary.operation().equals(FusedNoiseCompiler.FIND_TOP_SURFACE)) findTop = true;
            else plain = true;
        }
        assertTrue(findTop, "no fixture has the find-top-surface function");
        assertTrue(plain, "no fixture has the level function as a plain value");
    }

    /** Every combination of fluid rule, preliminary-surface kind and the two features each shows up with at least one other value. */
    @Test
    void theFixturesCoverEachPairOfSwitchValuesThatChangeTheKernelText() {
        Set<String> pairs = new TreeSet<>();
        for (var fixture : FIXTURES) {
            var request = fixture.request();
            pairs.add(request.fluidUpdates() + "/" + request.preliminarySurface());
            pairs.add(request.preliminarySurface() + "/aquifers=" + request.settings().aquifersEnabled());
            pairs.add(request.fluidUpdates() + "/aquifers=" + request.settings().aquifersEnabled());
            pairs.add("aquifers=" + request.settings().aquifersEnabled() + "/ores=" + request.settings().oresEnabled());
        }
        Set<String> expected = new TreeSet<>();
        for (var fluid : FusedNoiseCompiler.FluidUpdates.values()) {
            for (var surface : FusedNoiseCompiler.PreliminarySurface.values()) expected.add(fluid + "/" + surface);
            for (boolean aquifers : new boolean[]{true, false}) expected.add(fluid + "/aquifers=" + aquifers);
        }
        for (var surface : FusedNoiseCompiler.PreliminarySurface.values()) {
            for (boolean aquifers : new boolean[]{true, false}) expected.add(surface + "/aquifers=" + aquifers);
        }
        for (boolean aquifers : new boolean[]{true, false}) for (boolean ores : new boolean[]{true, false}) expected.add("aquifers=" + aquifers + "/ores=" + ores);
        assertEquals(Set.<String>of(), difference(expected, pairs), "switch combinations that no fixture builds");
    }

    // ------------------------------------------------------------------ what the compiler emits

    /** The compiler's evaluation modes, each with text that only that mode produces, found in the generated functions of some fixture. */
    private static final Map<FusedNoiseCompiler.Mode, String> MODE_FOOTPRINTS = Map.of(
            FusedNoiseCompiler.Mode.COLUMN, "precise double flat_\\d+_column\\(ivec3 p\\) \\{",
            FusedNoiseCompiler.Mode.XZ, "precise double xz_\\d+_col\\(ivec3 p\\) \\{",
            FusedNoiseCompiler.Mode.CORNER, "precise double interp_\\d+_corner\\(ivec3 p\\) \\{",
            FusedNoiseCompiler.Mode.CELL, "precise double v\\d+ = interpCell\\(\\d+, p\\);",
            FusedNoiseCompiler.Mode.VALUE, "precise double v\\d+ = interpValue\\(\\d+, p\\);",
            FusedNoiseCompiler.Mode.POINT, "precise double root_erosion_point\\(ivec3 p\\) \\{\\n    if \\(flatInBounds\\(p\\)\\) \\{",
            // A FlatCache reads the table inside the chunk's range and evaluates its child outside it.
            FusedNoiseCompiler.Mode.POINT_IN, "root_erosion_point\\(ivec3 p\\) \\{\n    if \\(flatInBounds\\(p\\)\\) \\{\n(?:(?!    \\} else \\{)[^\n]*\n)*?[^\n]*colv\\(\\d+, p\\)",
            FusedNoiseCompiler.Mode.POINT_OUT, "root_erosion_point\\(ivec3 p\\) \\{\n    if \\(flatInBounds\\(p\\)\\) \\{\n(?:[^\n]*\n)*?    \\} else \\{\n(?:(?!\n\\}\n)[\\s\\S])*?normalNoise\\(");

    @Test
    void everyEvaluationModeLeavesItsMarkInTheTextOfSomeFixture() {
        assertEquals(Set.of(FusedNoiseCompiler.Mode.values()), MODE_FOOTPRINTS.keySet(), "a Mode has no footprint listed");
        Map<String, String> byName = new LinkedHashMap<>();
        MODE_FOOTPRINTS.forEach((mode, regex) -> byName.put(mode.name(), regex));
        assertFound("evaluation modes", byName, true);
    }

    /** A value as the compiler names it in a condition: a generated variable or a coordinate. */
    private static final String VARIABLE = "(?:v\\d+|double\\(p\\.[xyz]\\))";
    private static final String OPERAND = "(?:v\\d+|double\\(p\\.[xyz]\\)|[0-9][0-9.E-]*lf|\\(-[0-9][0-9.E-]*lf\\))";

    /** Text in the generated functions that only the construct named produces. */
    private static final Map<String, String> EMITTED = linked(
            "negative literal", "\\(-[0-9][0-9.E-]*lf\\)",
            "signed zero literal", "\\(-0\\.0lf\\)",
            "exponent literal", "[0-9]E-?[0-9]+lf",
            "NaN literal", "bitsd\\(int64_t\\(0x7ff8000000000000ul\\)\\)",
            "+inf literal", "bitsd\\(int64_t\\(0x7ff0000000000000ul\\)\\)",
            "-inf literal", "bitsd\\(int64_t\\(0xfff0000000000000ul\\)\\)",
            "Noise", "= normalNoise\\(\\d+, double\\(p\\.x\\) \\* [^,\\n]+lf, double\\(p\\.y\\) \\* [^,\\n]+lf, double\\(p\\.z\\) \\* [^,\\n]+lf\\);",
            "ShiftedNoise", "= normalNoise\\(\\d+, double\\(p\\.x\\) \\* [^,\\n]+lf \\+ v\\d+, double\\(p\\.y\\) \\* [^,\\n]+lf \\+ v\\d+, double\\(p\\.z\\) \\* [^,\\n]+lf \\+ v\\d+\\);",
            "Shift XYZ", "double\\(p\\.x\\) \\* [^,\\n]+lf, double\\(p\\.y\\) \\* [^,\\n]+lf, double\\(p\\.z\\) \\* [^,\\n]+lf\\) \\* 4\\.0lf;",
            "Shift X0Z", "double\\(p\\.x\\) \\* [^,\\n]+lf, 0\\.0lf, double\\(p\\.z\\) \\* [^,\\n]+lf\\) \\* 4\\.0lf;",
            "Shift ZX0", "double\\(p\\.z\\) \\* [^,\\n]+lf, double\\(p\\.x\\) \\* [^,\\n]+lf, 0\\.0lf\\) \\* 4\\.0lf;",
            "BlendedNoise", "= blendedNoise\\(\\d+, p\\.x, p\\.y, p\\.z\\);",
            "EndIsland", "= endIsland\\(\\d+, p\\.x, p\\.z\\);",
            "WeirdScaledSampler TYPE1", "= rarity3D\\(v\\d+\\);",
            "WeirdScaledSampler TYPE2", "= rarity2D\\(v\\d+\\);",
            "WeirdScaledSampler body", "= v\\d+ \\* abs\\(normalNoise\\(\\d+, jdiv\\(double\\(p\\.x\\), v\\d+\\), jdiv\\(double\\(p\\.y\\), v\\d+\\), jdiv\\(double\\(p\\.z\\), v\\d+\\)\\)\\);",
            "Beardifier", "= beardifier\\(p\\);",
            "Spline", "float cs\\d+\\[SPLINE_COORDS\\];\\n(?:.*\\n)*?.*= double\\(splineEval\\(\\d+, cs\\d+\\)\\);",
            "Spline coordinate store", "cs\\d+\\[\\d+\\] = float\\(v\\d+\\);",
            "constant Spline", "= double\\(intBitsToFloat\\(-?\\d+\\)\\);",
            "Select", "\\n\\s+if \\(" + VARIABLE + " != 0\\.0lf\\) \\{\\n",
            "RangeChoice", "\\n\\s+if \\(" + VARIABLE + " >= [^\\n]* && " + VARIABLE + " < [^\\n]*\\) \\{\\n",
            "Ap2 multiply", "\\n\\s+if \\(" + VARIABLE + " == 0\\.0lf\\) \\{\\n",
            "Ap2 min", "\\n\\s+if \\(" + VARIABLE + " < [^\\n]*\\) \\{\\n",
            "Ap2 max", "\\n\\s+if \\(" + VARIABLE + " > [^\\n]*\\) \\{\\n",
            "Interpolated in CELL", "= interpCell\\(\\d+, p\\);",
            "Interpolated in VALUE", "= interpValue\\(\\d+, p\\);",
            "FlatCache read", "= colv\\(\\d+, p\\);",
            "hoisted XZ read", "= xzv\\(\\d+, p\\);",
            "unary negate", "= -v\\d+;",
            "unary abs", "= abs\\(v\\d+\\);",
            "unary square", "= (v\\d+) \\* \\1;",
            "unary cube", "= (v\\d+) \\* \\1 \\* \\1;",
            "unary floor", "= floor\\(v\\d+\\);",
            "unary sqrt", "= jsqrt\\(v\\d+\\);",
            "unary half_negative", "= \\(v\\d+ > 0\\.0lf \\? v\\d+ : v\\d+ \\* 0\\.5lf\\);",
            "unary quarter_negative", "= \\(v\\d+ > 0\\.0lf \\? v\\d+ : v\\d+ \\* 0\\.25lf\\);",
            "unary squeeze", "= mclamp\\(v\\d+, -1\\.0lf, 1\\.0lf\\);\\n\\s+precise double v\\d+ = v\\d+ \\* 0\\.5lf - jdiv\\(v\\d+ \\* v\\d+ \\* v\\d+, 24\\.0lf\\);",
            "binary subtract", "= " + OPERAND + " - " + OPERAND + ";",
            "binary divide", "= jdiv\\(",
            "binary min", "= jmin\\(",
            "binary max", "= jmax\\(",
            "density search", "if \\(v\\d+ > 0\\.390625lf\\) return y;",
            "level function", "return mfloor\\(v\\d+\\);",
            "find top surface", "int top = mfloor\\(jdiv\\(v\\d+, \\d+\\.0lf\\)\\) \\* \\d+;");

    @Test
    void everyConstructAndOperationLeavesItsMarkInTheGeneratedFunctionsOfSomeFixture() {
        assertFound("generated functions", EMITTED, true);
    }

    /** Text anywhere in the source that depends on a switch of the request. */
    private static final Map<String, String> SWITCHED = linked(
            "aquifers on", "const bool AQUIFERS = true;",
            "aquifers off", "const bool AQUIFERS = false;",
            "ores on", "const bool ORES = true;",
            "ores off", "const bool ORES = false;",
            "legacy aquifer random", "const bool AQ_LEGACY = true;",
            "xoroshiro aquifer random", "const bool AQ_LEGACY = false;",
            "legacy ore random", "const bool ORE_LEGACY = true;",
            "xoroshiro ore random", "const bool ORE_LEGACY = false;",
            "water default fluid", "const bool DF_IS_WATER = true, DF_IS_LAVA = false, DF_IS_LAVA_DEFAULT = false, DF_IS_AIR = false;",
            "lava default fluid", "DF_IS_LAVA = true, DF_IS_LAVA_DEFAULT = true",
            "air default fluid", "DF_IS_AIR = true;",
            "cells 4 wide, 8 high", "const int CELL_W = 4;\nconst int CELL_H = 8;",
            "cells 8 wide, 4 high", "const int CELL_W = 8;\nconst int CELL_H = 4;",
            "fluid rule before 1.21.2", "schedule = d1 >= FLOWING_UPDATE_SIMILARITY;",
            "fluid rule since 1.21.2", "bool sameStatus\\(ivec2 a, ivec2 b\\)",
            "aquifer shortcut of the level function", "aquifer\\[o \\+ 5\\] = \\(jfloorDiv\\(highest");

    @Test
    void everySwitchOfTheRequestLeavesItsMarkInTheSourceOfSomeFixture() {
        assertFound("request switches", SWITCHED, false);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertFound(String what, Map<String, String> footprints, boolean generatedFunctionsOnly) {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        footprints.forEach((name, regex) -> patterns.put(name, Pattern.compile(regex)));
        Set<String> missing = new TreeSet<>(footprints.keySet());
        for (var fixture : FIXTURES) {
            String source = new FusedNoiseCompiler().compile(fixture.request()).source();
            String text = generatedFunctionsOnly ? generatedFunctions(source) : source;
            missing.removeIf(name -> patterns.get(name).matcher(text).find());
        }
        assertEquals(Set.<String>of(), missing, what + " that no fixture's text contains");
    }

    /**
     * The part of the source the compiler generates from the graphs: from the first root function to the first
     * line of the kernel templates.  The library and the templates have text of their own that must not count.
     */
    private static String generatedFunctions(String source) {
        int start = source.indexOf("precise double root_final_cell(ivec3 p) {");
        int end = source.indexOf("// ---------------------------------------------------------- fluids");
        assertTrue(start >= 0 && end > start, "the markers that delimit the generated functions moved; update this test");
        return source.substring(start, end);
    }

    private static void forEachNode(java.util.function.Consumer<ProgramNode> visit) {
        Set<ProgramNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var fixture : FIXTURES) for (ProgramNode root : fixture.request().roots().values()) walk(root, seen, visit);
    }

    private static void walk(ProgramNode node, Set<ProgramNode> seen, java.util.function.Consumer<ProgramNode> visit) {
        if (!seen.add(node)) return;
        visit.accept(node);
        for (ProgramNode child : node.children()) walk(child, seen, visit);
    }

    private static FusedNoiseCompiler.Compiled compile(ProgramNode finalDensity) {
        return new FusedNoiseCompiler().compile(FusedFixtures.requestWithFinalDensity(finalDensity));
    }

    private static void refused(String messagePart, ProgramNode finalDensity) {
        var failure = assertThrows(FusedNoiseCompiler.UnsupportedFusedProgramException.class, () -> compile(finalDensity));
        assertTrue(failure.getMessage().contains(messagePart), "expected a refusal mentioning '" + messagePart + "' but got: " + failure.getMessage());
    }

    private static ProgramNode input(String name) {
        return new ProgramNode.Input(name, ValueType.FP64, EvaluationDomain.BLOCK);
    }

    private static String describe(PositionalRandomFactorySnapshot random) {
        return random == null ? "none" : random.algorithm().name();
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static Map<String, String> linked(String... namesAndRegexes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndRegexes.length; i += 2) map.put(namesAndRegexes[i], namesAndRegexes[i + 1]);
        return map;
    }
}
