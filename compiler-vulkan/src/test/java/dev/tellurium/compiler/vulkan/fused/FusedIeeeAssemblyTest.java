// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The IEEE_BITS source with the real library: {@code compile(request, IEEE_BITS)} as the runtime calls it.  The
 * library, {@code fused_lib_ieee.glsl}, is written on another branch, and these tests are skipped on a tree that
 * does not have it yet; once it is there they run with no further change, and nothing in them is to be removed.
 * {@link FusedIeeeDialectTest} holds the checks that need no library.
 */
class FusedIeeeAssemblyTest {
    @BeforeEach
    void onlyWhereTheLibraryResourceIsThere() {
        assumeTrue(FusedNoiseCompiler.class.getResource("fused_lib_ieee.glsl") != null, "fused_lib_ieee.glsl is not on this tree yet");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> fixtures() {
        return FusedFixtures.all().stream().map(fixture -> org.junit.jupiter.params.provider.Arguments.of(fixture.name(), fixture));
    }

    private static FusedNoiseCompiler.Compiled compileIeee(FusedFixtures.Fixture fixture) {
        return new FusedNoiseCompiler().compile(fixture.request(), FusedNoiseCompiler.Dialect.IEEE_BITS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theIeeeSourceHoldsTheNumericContractOfTheProfile(String name, FusedFixtures.Fixture fixture) {
        FusedNoiseCompiler.Compiled compiled = compileIeee(fixture);
        assertEquals(FusedNoiseCompiler.Dialect.IEEE_BITS, compiled.dialect());
        var inspection = new SpirvNumericContract().inspect(compiled.source(), NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), name + ": " + inspection.violations());
        assertEquals(List.of(), FusedIeeeDialectTest.nativeFloatingConstructs(FusedIeeeDialectTest.generated(
                new FusedNoiseCompiler().compile(fixture.request(), FusedNoiseCompiler.Dialect.IEEE_BITS, FusedIeeeDialectTest.LIBRARY_END))), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theIeeeFingerprintIsTakenOverTheIeeeTextAndDiffersFromTheNativeOne(String name, FusedFixtures.Fixture fixture) {
        FusedNoiseCompiler.Compiled ieee = compileIeee(fixture);
        FusedNoiseCompiler.Compiled nativeCompiled = new FusedNoiseCompiler().compile(fixture.request());
        assertEquals(FusedNoiseCompiler.sha256(ieee.source()), ieee.fingerprint(), name);
        assertNotEquals(nativeCompiled.fingerprint(), ieee.fingerprint(), name);
        assertNotEquals(nativeCompiled.structureFingerprint(), ieee.structureFingerprint(), name);
    }

    /** The parts in the order of the native text: header, the helpers they call, the conversions, the library, then what the compiler generates. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theIeeeSourceIsHeaderHelpersConversionsLibraryAndThenTheGeneratedText(String name, FusedFixtures.Fixture fixture) {
        String source = compileIeee(fixture).source();
        String library = FusedNoiseCompiler.ieeeLibrary();
        assertTrue(source.startsWith("#version 460\n#extension GL_ARB_gpu_shader_int64 : require\n// tellurium-fused-ieee-bits-v1\n"), name);
        assertTrue(source.contains(FusedIeeeHelpers.source() + FastIeeeHelpers.source() + library + "\n"), name + ": the conversions, the fast helpers and the library follow the helpers");
        int libraryAt = source.indexOf(library);
        assertEquals(libraryAt, source.lastIndexOf(library), name + ": the library is in the text once");
        assertTrue(source.indexOf("uvec2 wg_fp64_add(") < source.indexOf(FusedIeeeHelpers.source()), name);
        assertTrue(source.indexOf("uvec2 wg_fp64_mul(") < source.indexOf(FastIeeeHelpers.source()), name + ": the fallbacks come before the fast helpers");
        assertTrue(source.indexOf(FusedIeeeHelpers.source()) < source.indexOf(FastIeeeHelpers.source()), name);
        assertTrue(libraryAt < source.indexOf("uvec2 xzv(int c, ivec3 p)"), name);
        assertTrue(source.indexOf("uvec2 xzv(int c, ivec3 p)") < source.indexOf("void setupChunk(uint slot)"), name);
        assertTrue(source.indexOf("void setupChunk(uint slot)") < source.indexOf("void main()"), name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyLibraryFunctionTheNativeGeneratedTextCallsIsInTheIeeeLibrary(String name, FusedFixtures.Fixture fixture) {
        String nativeLibrary = FusedNoiseCompiler.library();
        String nativeSource = new FusedNoiseCompiler().compile(fixture.request()).source();
        String nativeGenerated = nativeSource.substring(nativeSource.indexOf(nativeLibrary) + nativeLibrary.length());
        Set<String> ieeeFunctions = declaredFunctions(FusedNoiseCompiler.ieeeLibrary());
        Set<String> missing = new TreeSet<>();
        Matcher call = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(").matcher(nativeGenerated);
        Set<String> nativeFunctions = declaredFunctions(nativeLibrary);
        while (call.find()) {
            if (nativeFunctions.contains(call.group(1)) && !ieeeFunctions.contains(call.group(1))) missing.add(call.group(1));
        }
        assertEquals(Set.of(), missing, name);
    }

    @Test
    void theIeeeLibraryDeclaresEveryFunctionOfTheNativeLibraryUnderTheSameName() {
        Set<String> missing = new TreeSet<>(declaredFunctions(FusedNoiseCompiler.library()));
        missing.removeAll(declaredFunctions(FusedNoiseCompiler.ieeeLibrary()));
        assertEquals(Set.of(), missing);
    }

    /** A function declared twice would not compile; this finds it without a shader compiler.  {@code main} is one per kernel, each under its own macro. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void noFunctionIsDeclaredTwiceInTheWholeIeeeSource(String name, FusedFixtures.Fixture fixture) {
        String source = compileIeee(fixture).source();
        var seen = new HashSet<String>();
        var twice = new TreeSet<String>();
        Matcher declaration = FUNCTION_DECLARATION.matcher(source);
        while (declaration.find()) {
            String signature = declaration.group(1) + " " + declaration.group(2);
            if (!declaration.group(2).equals("main") && !seen.add(signature)) twice.add(signature);
        }
        assertEquals(Set.of(), twice, name);
    }

    @Test
    void theIeeeLibraryTextHasLineFeedsOnly() {
        assertFalse(FusedNoiseCompiler.ieeeLibrary().contains("\r"), "fused_lib_ieee.glsl must have LF line ends (see .gitattributes)");
    }

    private static final Pattern FUNCTION_DECLARATION = Pattern.compile("(?m)^(?!#|const|layout|struct|return)(?:precise\\s+)?(\\w+)\\s+(\\w+)\\s*\\([^;{]*\\)\\s*\\{");

    private static Set<String> declaredFunctions(String text) {
        var names = new ArrayList<String>();
        Matcher match = FUNCTION_DECLARATION.matcher(text);
        while (match.find()) names.add(match.group(2));
        return new HashSet<>(names);
    }
}
