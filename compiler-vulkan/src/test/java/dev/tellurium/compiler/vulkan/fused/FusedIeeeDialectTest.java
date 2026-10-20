// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IEEE_BITS text of {@link FusedNoiseCompiler}: what the compiler itself generates (the accessors, the
 * prelude, the constants, the router functions, the kernels and the surface body) is checked here on its own,
 * with a marker standing in for the hand-written IEEE library, which has its own tests.  The text that
 * contains the library is checked in {@code FusedIeeeAssemblyTest}.
 */
class FusedIeeeDialectTest {
    /** Stands in for the library; everything the compiler generates itself follows it. */
    static final String LIBRARY_END = "// ieee library stand-in ends here\n";

    private static final FusedArith IEEE = FusedArith.of(FusedNoiseCompiler.Dialect.IEEE_BITS);
    private static final FusedArith NATIVE = FusedArith.of(FusedNoiseCompiler.Dialect.NATIVE_FP64);

    static Stream<org.junit.jupiter.params.provider.Arguments> fixtures() {
        return FusedFixtures.all().stream().map(fixture -> org.junit.jupiter.params.provider.Arguments.of(fixture.name(), fixture));
    }

    static FusedNoiseCompiler.Compiled compileIeee(FusedFixtures.Fixture fixture) {
        return new FusedNoiseCompiler().compile(fixture.request(), FusedNoiseCompiler.Dialect.IEEE_BITS, LIBRARY_END);
    }

    /** The text the compiler generates itself: everything after the library. */
    static String generated(FusedNoiseCompiler.Compiled compiled) {
        int at = compiled.source().indexOf(LIBRARY_END);
        assertTrue(at > 0, "the library stand-in is in the source");
        return compiled.source().substring(at + LIBRARY_END.length());
    }

    // ------------------------------------------------------------------ the text of what the compiler generates

    private static final Pattern FLOATING_WORD = Pattern.compile("\\b(double|float|precise|fvec[234]|dvec[234])\\b");
    private static final Pattern FLOATING_LITERAL = Pattern.compile("(?<![\\w.])(\\d+\\.\\d*|\\.\\d+|\\d+(?=[eE][+-]?\\d))([eE][+-]?\\d+)?(lf|LF|f|F)?(?![\\w.])");
    private static final Pattern FLOATING_BUILTIN = Pattern.compile("\\b(isnan|isinf|floor|ceil|sqrt|inversesqrt|fma|frexp|ldexp|round|roundEven|trunc|fract|mix|step|smoothstep|sign|exp|exp2|log|log2|pow|sin|cos)\\s*\\(");
    private static final Pattern BITS_CONVERSION = Pattern.compile("\\w*BitsTo\\w*");

    /** The text of a source with its line comments cut off: a number in a comment is not a literal. */
    private static String withoutComments(String text) {
        return Pattern.compile("//[^\n]*").matcher(text).replaceAll("");
    }

    static List<String> nativeFloatingConstructs(String text) {
        var found = new ArrayList<String>();
        String code = withoutComments(text);
        for (Pattern pattern : List.of(FLOATING_WORD, FLOATING_BUILTIN, BITS_CONVERSION, FLOATING_LITERAL)) {
            Matcher match = pattern.matcher(pattern == FLOATING_WORD ? text : code);
            while (match.find() && found.size() < 20) found.add(match.group().trim());
        }
        return found;
    }

    @Test
    void theCheckFindsEachKindOfNativeConstructItIsMeantToFind() {
        for (String text : List.of("double a;", "float a;", "precise uvec2 a;", "dvec3 a;", "x = 0.5lf;", "x = 1.0;", "x = .5f;", "x = 1e5;", "x = 2.5E-3lf;",
                "isnan(a)", "floor (a)", "sqrt(a)", "fma(a, b, c)", "frexp(a, e)", "uintBitsToFloat(a)", "int64BitsToDouble(a)", "floatBitsToInt(a)")) {
            assertFalse(nativeFloatingConstructs(text).isEmpty(), text);
        }
        for (String text : List.of("uvec2 a = uvec2(0x7ff80000u, 0u);", "a = wg_fp64_floor(b);", "int i = mfloor(d);", "a = p.x1.5;", "x = 0x1e5u;", "v = wg_fp64_sign(a);", "int k = 12 + 1;")) {
            assertEquals(List.of(), nativeFloatingConstructs(text), text);
        }
        // Comments are held to the type words only: a number in a comment is not a literal, but a type word is a violation.
        assertEquals(List.of(), nativeFloatingConstructs("int x; // 0.5 of the range\n"));
        assertFalse(nativeFloatingConstructs("int x; // a double\n").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theGeneratedIeeeTextHasNoNativeFloatingConstruct(String name, FusedFixtures.Fixture fixture) {
        assertEquals(List.of(), nativeFloatingConstructs(generated(compileIeee(fixture))), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theIeeeSourceKeepsTheNativeSourcesOrderOfParts(String name, FusedFixtures.Fixture fixture) {
        String source = compileIeee(fixture).source();
        String[] markers = {
                "#version 460", "// " + FusedNoiseCompiler.IEEE_VERSION, "layout(local_size_x = 64) in;", "uvec2 wg_fp64_add(", "int wgf_fp64_to_i32_java(",
                LIBRARY_END.trim(), "uvec2 xzv(int c, ivec3 p)", "FLOWING_UPDATE_SIMILARITY", "void setupChunk(uint slot)", "void main()"};
        int previous = -1;
        for (String marker : markers) {
            int at = source.indexOf(marker);
            assertTrue(at > previous, name + ": '" + marker + "' is missing or out of order");
            previous = at;
        }
        assertFalse(source.contains("// " + FusedNoiseCompiler.VERSION), "the native version line is not in the IEEE text");
        assertFalse(source.contains("GL_ARB_gpu_shader_fp64"), "the IEEE text declares no fp64 extension");
        assertTrue(source.startsWith("#version 460\n#extension GL_ARB_gpu_shader_int64 : require\n// tellurium-fused-ieee-bits-v1\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyHelperTheGeneratedTextCallsIsDeclaredBeforeTheLibrary(String name, FusedFixtures.Fixture fixture) {
        FusedNoiseCompiler.Compiled compiled = compileIeee(fixture);
        String declared = compiled.source().substring(0, compiled.source().indexOf(LIBRARY_END));
        Set<String> missing = new TreeSet<>();
        Matcher call = Pattern.compile("\\b(wgf?_\\w+)\\s*\\(").matcher(generated(compiled));
        while (call.find()) {
            String function = call.group(1);
            if (!Pattern.compile("(?m)^\\s*(?:uint|int|bool|void|uvec2|int64_t)\\s+" + function + "\\(").matcher(declared).find()) missing.add(function);
        }
        assertEquals(Set.of(), missing, name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theIeeeSourceIsTheSameOnEveryHostLineSeparator(String name, FusedFixtures.Fixture fixture) {
        assertFalse(compileIeee(fixture).source().contains("\r"), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theTablesAndTheShapeAreTheNativeOnes(String name, FusedFixtures.Fixture fixture) {
        FusedNoiseCompiler.Compiled nativeCompiled = new FusedNoiseCompiler().compile(fixture.request());
        FusedNoiseCompiler.Compiled ieee = compileIeee(fixture);
        assertEquals(FusedNoiseCompiler.Dialect.IEEE_BITS, ieee.dialect());
        assertEquals(FusedNoiseCompiler.Dialect.NATIVE_FP64, nativeCompiled.dialect());
        // The host uploads the same tables to both dialects: a uvec2 carries the little-endian bits of a double.
        assertArrayEquals(nativeCompiled.doubleTable(), ieee.doubleTable(), name);
        assertArrayEquals(nativeCompiled.permTable(), ieee.permTable(), name);
        assertEquals(nativeCompiled.flatChannels(), ieee.flatChannels());
        assertEquals(nativeCompiled.interpolatedChannels(), ieee.interpolatedChannels());
        assertEquals(nativeCompiled.xzChannels(), ieee.xzChannels());
        assertEquals(nativeCompiled.surfaceHeader(), ieee.surfaceHeader());
        assertEquals(nativeCompiled.surfaceFingerprint(), ieee.surfaceFingerprint(), "the surface program does not depend on the dialect");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theFingerprintsAreTakenOverTheIeeeTextAndDifferFromTheNativeOnes(String name, FusedFixtures.Fixture fixture) {
        FusedNoiseCompiler.Compiled nativeCompiled = new FusedNoiseCompiler().compile(fixture.request());
        FusedNoiseCompiler.Compiled ieee = compileIeee(fixture);
        assertEquals(FusedNoiseCompiler.sha256(ieee.source()), ieee.fingerprint(), name);
        assertEquals(FusedNoiseCompiler.structureFingerprint(ieee.source()), ieee.structureFingerprint(), name);
        assertNotEquals(nativeCompiled.fingerprint(), ieee.fingerprint(), name);
        assertNotEquals(nativeCompiled.structureFingerprint(), ieee.structureFingerprint(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void compilingTwiceGivesTheSameIeeeText(String name, FusedFixtures.Fixture fixture) {
        assertEquals(compileIeee(fixture).fingerprint(), compileIeee(fixture).fingerprint(), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theWholeIeeeSourceHoldsTheNumericContractOfTheProfile(String name, FusedFixtures.Fixture fixture) {
        var inspection = new SpirvNumericContract().inspect(compileIeee(fixture).source(), NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), name + ": " + inspection.violations());
    }

    @Test
    void theNativeSourceStillFailsTheContractSoTheCheckMeansSomething() {
        var fixture = FusedFixtures.all().get(0);
        var inspection = new SpirvNumericContract().inspect(new FusedNoiseCompiler().compile(fixture.request()).source(), NumericProfile.GPU_IEEE_BITS);
        assertFalse(inspection.valid());
    }

    @Test
    void theDialectsProfilesAreTheirNumericProfiles() {
        assertEquals(NumericProfile.NATIVE_QUALIFIED, FusedNoiseCompiler.Dialect.NATIVE_FP64.profile());
        assertEquals(NumericProfile.GPU_IEEE_BITS, FusedNoiseCompiler.Dialect.IEEE_BITS.profile());
    }

    // ------------------------------------------------------------------ the kernel text and its swaps

    private static final List<FusedNoiseCompiler.FluidUpdates> RULES = List.of(FusedNoiseCompiler.FluidUpdates.values());
    private static final List<FusedNoiseCompiler.PreliminarySurface> SURFACES = List.of(FusedNoiseCompiler.PreliminarySurface.values());

    static Stream<org.junit.jupiter.params.provider.Arguments> variants() {
        var out = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (var rule : RULES) for (var surface : SURFACES) out.add(org.junit.jupiter.params.provider.Arguments.of(rule, surface));
        return out.stream();
    }

    @Test
    void bothSwitchesHaveTwoValuesSoTheVariantTestsCoverFourCombinations() {
        assertEquals(2, RULES.size());
        assertEquals(2, SURFACES.size());
    }

    /** Applies each swap to the text the one before left, and checks that its anchor is there exactly once. */
    private static String applyEachExactlyOnce(String text, List<FusedArith.Swap> swaps, String table) {
        for (FusedArith.Swap swap : swaps) {
            int first = text.indexOf(swap.from());
            assertTrue(first >= 0, table + ": anchor is not in the text: " + swap.from());
            assertEquals(-1, text.indexOf(swap.from(), first + 1), table + ": anchor is in the text twice: " + swap.from());
            text = text.substring(0, first) + swap.to() + text.substring(first + swap.from().length());
        }
        return text;
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("variants")
    void everyKernelSwapAnchorAppliesExactlyOnceInEveryVariant(FusedNoiseCompiler.FluidUpdates rule, FusedNoiseCompiler.PreliminarySurface surface) {
        String body = FusedKernels.nativeBody(rule, surface);
        body = applyEachExactlyOnce(body, FusedKernels.KERNEL_COMMON, "COMMON");
        body = applyEachExactlyOnce(body, rule == FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER
                ? FusedKernels.KERNEL_COMPARING_ONLY : FusedKernels.KERNEL_BETWEEN_ONLY, rule.name());
        // The same text, by the entry point the compiler uses.
        assertEquals(body, FusedKernels.kernelBody(IEEE, rule, surface));
        assertEquals(List.of(), nativeFloatingConstructs(body), rule + " " + surface);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("variants")
    void theOtherRulesSwapsHaveNoAnchorInTheVariant(FusedNoiseCompiler.FluidUpdates rule, FusedNoiseCompiler.PreliminarySurface surface) {
        var other = rule == FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER ? FusedKernels.KERNEL_BETWEEN_ONLY : FusedKernels.KERNEL_COMPARING_ONLY;
        String body = FusedArith.Ieee.apply(FusedKernels.nativeBody(rule, surface), FusedKernels.KERNEL_COMMON);
        for (FusedArith.Swap swap : other) {
            assertEquals(-1, body.indexOf(swap.from()), "the text of the other rule is in the " + rule + " kernels: " + swap.from());
        }
        assertThrows(IllegalStateException.class, () -> FusedArith.Ieee.apply(body, other));
    }

    @Test
    void theNativeKernelBodyIsNotTouchedByTheIeeeTables() {
        for (var rule : RULES) for (var surface : SURFACES) {
            assertEquals(FusedKernels.nativeBody(rule, surface), FusedKernels.kernelBody(NATIVE, rule, surface));
        }
    }

    @Test
    void everySurfaceSwapAnchorAppliesExactlyOnceAndLeavesNothingNative() {
        String body = applyEachExactlyOnce(FusedSurfaceKernels.BODY, FusedSurfaceKernels.IEEE_SWAPS, "surface");
        assertEquals(body, FusedSurfaceKernels.body(IEEE));
        assertEquals(FusedSurfaceKernels.BODY, FusedSurfaceKernels.body(NATIVE));
        assertEquals(List.of(), nativeFloatingConstructs(body));
    }

    @Test
    void theStoreLinesOfTheIeeeKernelsEndInLineFeedWhateverTheHostSeparatorIs() {
        for (String separator : List.of("\n", "\r\n")) {
            String ieee = FusedKernels.kernels(IEEE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH, separator);
            assertFalse(ieee.contains("\r"), "IEEE kernels written on a host with separator " + separator.length() + " characters long");
        }
        String lf = FusedKernels.kernels(IEEE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION, "\n");
        String crlf = FusedKernels.kernels(IEEE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER, FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION, "\r\n");
        assertEquals(lf, crlf);
        assertTrue(lf.contains("] = flat_1_column(p);\n"));
        assertTrue(lf.contains("] = interp_2_corner(p);\n"));
        assertTrue(lf.contains("] = xz_0_col(p);\n"));
    }

    /** The test above only proves something if the parameter does reach the native text, which keeps the separator of the host. */
    @Test
    void theNativeKernelsKeepTheHostSeparatorSoThatTheListedFingerprintsStay() {
        String lf = FusedKernels.kernels(NATIVE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH, "\n");
        String crlf = FusedKernels.kernels(NATIVE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH, "\r\n");
        assertNotEquals(lf, crlf);
        assertTrue(crlf.contains("] = flat_1_column(p);\r\n"));
        assertEquals(FusedKernels.kernels(NATIVE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH),
                FusedKernels.kernels(NATIVE, 2, 3, 1, FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH, System.lineSeparator()));
    }

    // ------------------------------------------------------------------ the request

    @Test
    void anIeeeCompileWithoutTheLibraryTextIsRefused() {
        var fixture = FusedFixtures.all().get(0);
        assertThrows(NullPointerException.class, () -> new FusedNoiseCompiler().compile(fixture.request(), FusedNoiseCompiler.Dialect.IEEE_BITS, null));
        assertThrows(NullPointerException.class, () -> new FusedNoiseCompiler().compile(fixture.request(), null));
    }

    @Test
    void aMissingResourceFailsClosedNamingIt() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> FusedNoiseCompiler.resource("fused_lib_nonexistent.glsl"));
        assertTrue(failure.getMessage().contains("fused_lib_nonexistent.glsl"), failure.getMessage());
    }

    @Test
    void theNativeCompileNeverAsksForTheIeeeLibrary() {
        var fixture = FusedFixtures.all().get(0);
        assertEquals(FusedNoiseCompiler.Dialect.NATIVE_FP64, new FusedNoiseCompiler().compile(fixture.request()).dialect());
        assertEquals(FusedNoiseCompiler.Dialect.NATIVE_FP64, new FusedNoiseCompiler().compile(fixture.request(), FusedNoiseCompiler.Dialect.NATIVE_FP64).dialect());
    }
}
