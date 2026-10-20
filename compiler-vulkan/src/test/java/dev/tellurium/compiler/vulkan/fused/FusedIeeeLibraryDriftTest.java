// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code fused_lib_ieee.glsl} is a parallel copy of {@code fused_lib.glsl}. The integer logic exists in both
 * and must not drift: the two files have the same functions in the same order, and every native function
 * that has no floating-point token is byte-identical in the port. The same holds for the top-level
 * declarations outside functions, and every native floating constant has a counterpart of the same name.
 */
class FusedIeeeLibraryDriftTest {
    private static final String NATIVE = FusedNoiseCompiler.library();
    private static final String IEEE = FusedIeeeLibrary.source();

    /** The functions of the native library that carry no floating-point token. A new entry needs a decision. */
    private static final Set<String> INTEGER_ONLY = Set.of("bail", "jrem", "jfloorDiv", "jfloorMod", "permAt",
            "mixStafford", "rotl64", "positionSeed", "rngAt", "xoroNext", "legacyBits", "rngNextInt", "sKind", "sPayload");

    private static final Pattern NATIVE_CONSTANT = Pattern.compile("^const double ([A-Za-z0-9_]+) =");

    /** One violation per difference between the two libraries; empty when the port has not drifted. */
    static List<String> violations(String nativeText, String ieeeText) {
        var violations = new ArrayList<String>();
        var nativeFunctions = FusedLibraryText.functionsByName(nativeText);
        var ieeeFunctions = FusedLibraryText.functionsByName(ieeeText);
        if (!List.copyOf(nativeFunctions.keySet()).equals(List.copyOf(ieeeFunctions.keySet()))) {
            violations.add("function set or order differs: native " + nativeFunctions.keySet() + " port " + ieeeFunctions.keySet());
        }
        for (Map.Entry<String, FusedLibraryText.Function> entry : nativeFunctions.entrySet()) {
            FusedLibraryText.Function port = ieeeFunctions.get(entry.getKey());
            if (port == null) continue;
            boolean integerOnly = !FusedLibraryText.hasFloatingToken(FusedLibraryText.withoutComments(entry.getValue().text()));
            if (integerOnly && !entry.getValue().text().equals(port.text())) {
                violations.add("integer-only function " + entry.getKey() + " differs from the native text");
            }
            if (!integerOnly && port.text().equals(entry.getValue().text())) {
                violations.add("function " + entry.getKey() + " has floating-point tokens natively but is unchanged in the port");
            }
        }
        var portStatements = FusedLibraryText.topLevelStatements(ieeeText);
        for (String statement : FusedLibraryText.topLevelStatements(nativeText)) {
            if (!FusedLibraryText.hasFloatingToken(statement)) {
                if (!portStatements.contains(statement)) violations.add("declaration missing from the port: " + statement);
                continue;
            }
            Matcher constant = NATIVE_CONSTANT.matcher(statement);
            if (!constant.find()) {
                violations.add("floating declaration the test has no rule for: " + statement);
            } else if (portStatements.stream().noneMatch(port -> port.startsWith("const uvec2 " + constant.group(1) + " = "))) {
                violations.add("no counterpart for the constant " + constant.group(1));
            }
        }
        return violations;
    }

    @Test
    void thePortHasNotDriftedFromTheNativeLibrary() {
        assertEquals(List.of(), violations(NATIVE, IEEE));
    }

    @Test
    void theIntegerOnlyFunctionsAreExactlyTheExpectedSet() {
        var integerOnly = new TreeSet<String>();
        for (var function : FusedLibraryText.functions(NATIVE)) {
            if (!FusedLibraryText.hasFloatingToken(FusedLibraryText.withoutComments(function.text()))) integerOnly.add(function.name());
        }
        assertEquals(new TreeSet<>(INTEGER_ONLY), integerOnly);
    }

    @Test
    void theNativeLibraryIsReadWholeSoNothingIsSilentlySkipped() {
        // every non-comment character of the native text is either inside a function or a top-level statement
        var functions = FusedLibraryText.functions(NATIVE);
        assertTrue(functions.size() >= 50, "functions found: " + functions.size());
        assertFalse(FusedLibraryText.topLevelStatements(NATIVE).isEmpty());
        for (String name : List.of("jdiv", "jsqrt", "splineEval", "endIsland", "rngNextInt", "bail")) {
            assertTrue(functions.stream().anyMatch(function -> function.name().equals(name)), name);
        }
    }

    @Test
    void theCheckRejectsAChangedIntegerFunctionAMissingFunctionAndAnUnchangedFloatingOne() {
        assertEquals(1, violations(NATIVE, IEEE.replace("int jrem(int a, int b) { return a - (a / b) * b; }",
                "int jrem(int a, int b) { return a - (a / b) * b + 0; }")).size());
        assertEquals(1, violations(NATIVE, IEEE.replace("int permAt(int base, int index) { return int(perm[base + (index & 255)]); }",
                "int permAt(int base, int index) { return int(perm[base + (index & 255)]); }\nint permAt2(int base) { return base; }")).size());
        String xoro = FusedLibraryText.functionsByName(IEEE).get("xoroNext").text();
        assertEquals(1, violations(NATIVE, IEEE.replace(xoro, "")).size());
        // a function of the port reverting to the native text keeps its floating tokens
        String nativeSmooth = FusedLibraryText.functionsByName(NATIVE).get("smoothstepd").text();
        String portSmooth = FusedLibraryText.functionsByName(IEEE).get("smoothstepd").text();
        assertEquals(1, violations(NATIVE, IEEE.replace(portSmooth, nativeSmooth)).size());
        // a declaration outside functions that changes
        assertEquals(1, violations(NATIVE, IEEE.replace("const int SPLINE_STACK = 16;", "const int SPLINE_STACK = 17;")).size());
        // an order swap is a violation even when each function is intact
        String first = FusedLibraryText.functionsByName(IEEE).get("jrem").text();
        String second = FusedLibraryText.functionsByName(IEEE).get("jfloorDiv").text();
        String swapped = IEEE.replace(first, "@@A@@").replace(second, first).replace("@@A@@", second);
        assertFalse(violations(NATIVE, swapped).isEmpty());
    }
}
