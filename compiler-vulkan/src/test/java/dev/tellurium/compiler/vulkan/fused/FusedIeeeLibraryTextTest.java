// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the integer-carrier library may and may not contain. The text must pass the contract the GPU_IEEE_BITS
 * shaders are held to (no floating type word anywhere, comments included), contain no floating literal,
 * {@code precise}, floating builtin or bit-cast outside comments, and call only functions that exist: its own,
 * the {@code wg_} closure of {@link FusedIeeeLibrary#HELPER_ROOTS}, the {@code wgf_} helpers, or a short list
 * of integer builtins.
 */
class FusedIeeeLibraryTextTest {
    private static final String IEEE = FusedIeeeLibrary.source();
    private static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Set<String> NOT_FUNCTIONS = Set.of("if", "for", "while", "switch", "return", "do", "else");
    private static final Set<String> TYPES = Set.of("int", "uint", "bool", "uvec2", "uvec4", "int64_t", "uint64_t");
    /** Integer-only builtins the library uses. */
    private static final Set<String> BUILTINS = Set.of("packUint2x32", "unpackUint2x32", "atomicOr", "abs");

    @Test
    void theTextPassesTheTypeWordContractOfTheIntegerProfile() {
        // inspect() also wants the dispatch-bounds marker, which belongs to a whole shader, not to this text
        var inspection = new SpirvNumericContract().inspect(IEEE + "\nuint bounds;\n", NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
        assertFalse(IEEE.matches("(?s).*\\b(float|double|fvec[234]|dvec[234])\\b.*"));
    }

    @Test
    void theCheckThatTheContractAppliesIsNotVacuous() {
        var inspection = new SpirvNumericContract().inspect(IEEE + "\n// a double here\nuint bounds;\n", NumericProfile.GPU_IEEE_BITS);
        assertFalse(inspection.valid());
    }

    @Test
    void noFloatingLiteralTypeBuiltinOrBitCastAppearsOutsideComments() {
        var offending = new ArrayList<String>();
        String[] lines = IEEE.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (FusedLibraryText.hasFloatingToken(FusedLibraryText.withoutComments(lines[i]))) {
                offending.add((i + 1) + ": " + lines[i].strip());
            }
        }
        assertEquals(List.of(), offending);
    }

    @Test
    void theTokenCheckRecognisesEachKindItIsMeantToForbid() {
        for (String text : List.of("double x;", "float x;", "dvec2 x;", "precise uint x;", "uint x = 1.0;", "uint x = 1e5;", "uint x = .5;",
                "uint x = 2.5lf;", "uint x = 2lf;", "uint x = 1.0f;", "x = floor(y);", "x = ceil(y);", "x = sqrt(y);", "x = fma(a, b, c);",
                "x = frexp(a, e);", "x = isnan(a);", "x = isinf(a);", "x = uintBitsToFloat(a);", "x = floatBitsToInt(a);",
                "x = doubleBitsToInt64(a);", "x = packDouble2x32(a);", "x = unpackDouble2x32(a);")) {
            assertTrue(FusedLibraryText.hasFloatingToken(text), text);
        }
        for (String text : List.of("uint x = 0x3f800000u;", "uvec2 x = uvec2(0x00000000u, 0x7ff80000u);", "int jfloorDiv(int a, int b)",
                "x = int64_t(packUint2x32(d));", "uint mantissa = 0x1e5u;", "r.legacy = 25214903917l;")) {
            assertFalse(FusedLibraryText.hasFloatingToken(text), text);
        }
    }

    @Test
    void everyCallResolvesToTheLibraryTheHelperClosureOrAnIntegerBuiltin() {
        var known = new TreeSet<String>();
        FusedLibraryText.functions(IEEE).forEach(function -> known.add(function.name()));
        FusedLibraryText.functions(IntegerIeeeEmitter.helperSource(FusedIeeeLibrary.HELPER_ROOTS)).forEach(function -> known.add(function.name()));
        FusedLibraryText.functions(FusedIeeeHelpers.source()).forEach(function -> known.add(function.name()));
        FusedLibraryText.functions(FastIeeeHelpers.source()).forEach(function -> known.add(function.name()));
        var unknown = new TreeSet<String>();
        Matcher call = CALL.matcher(FusedLibraryText.withoutComments(IEEE));
        while (call.find()) {
            String name = call.group(1);
            if (NOT_FUNCTIONS.contains(name) || TYPES.contains(name) || BUILTINS.contains(name) || known.contains(name)) continue;
            unknown.add(name);
        }
        assertEquals(Set.of(), unknown);
        assertFalse(FusedLibraryText.withoutComments(IEEE).contains("wg_failed"),
                "the closure never declares wg_failed; a call to a helper that sets it would not compile");
    }

    @Test
    void helperRootsAreExactlyWhatTheLibraryCallsPlusTheFusedAndFastHelpersCalls() {
        var called = new TreeSet<String>();
        Matcher call = Pattern.compile("\\bwg_[A-Za-z0-9_]+(?=\\s*\\()").matcher(FusedLibraryText.withoutComments(IEEE));
        while (call.find()) called.add(call.group());
        called.addAll(FusedIeeeHelpers.HELPERS);
        called.addAll(FastIeeeHelpers.HELPERS);
        assertEquals(called, new TreeSet<>(FusedIeeeLibrary.HELPER_ROOTS));
        // the closure resolves, and fails closed on an unknown root
        String closure = IntegerIeeeEmitter.helperSource(FusedIeeeLibrary.HELPER_ROOTS);
        for (String root : FusedIeeeLibrary.HELPER_ROOTS) assertTrue(closure.contains(root + "("), root);
    }

    @Test
    void everyWgfHelperTheLibraryCallsIsDeclaredByFusedIeeeHelpers() {
        String helpers = FusedIeeeHelpers.source();
        Matcher call = Pattern.compile("\\bwgf_[A-Za-z0-9_]+(?=\\s*\\()").matcher(FusedLibraryText.withoutComments(IEEE));
        int calls = 0;
        while (call.find()) {
            calls++;
            assertTrue(helpers.contains(" " + call.group() + "("), call.group());
        }
        assertTrue(calls >= 10, "wgf_ calls: " + calls);
    }

    @Test
    void theTextHasLfLineEndsAndNoTabsSoItsHashIsTheSameOnEveryPlatform() {
        assertFalse(IEEE.contains("\r"));
        assertFalse(IEEE.contains("\t"));
        assertTrue(IEEE.endsWith("}\n"));
    }
}
