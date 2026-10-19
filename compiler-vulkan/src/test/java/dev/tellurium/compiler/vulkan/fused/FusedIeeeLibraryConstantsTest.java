// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every constant of {@code fused_lib_ieee.glsl} states its decimal value in a trailing comment, and the bits
 * must be exactly those of Java's correctly rounded parse of that comment: {@code Double.parseDouble} for a
 * {@code uvec2}, {@code Float.parseFloat} for a {@code uint}. A hex constant without such a comment, or whose
 * bits differ, fails here; a constant the native library uses and the port forgot fails too.
 */
class FusedIeeeLibraryConstantsTest {
    private static final Pattern ANNOTATED_DOUBLE = Pattern.compile(
            "^const uvec2 ([A-Za-z0-9_]+) = uvec2\\(0x([0-9a-f]{8})u, 0x([0-9a-f]{8})u\\); // (-?\\d+\\.\\d+(?:E-?\\d+)?)$");
    private static final Pattern ANNOTATED_FLOAT = Pattern.compile(
            "^const uint ([A-Za-z0-9_]+) = 0x([0-9a-f]{8})u; // (-?\\d+\\.\\d+(?:E-?\\d+)?)$");
    private static final Pattern HEX = Pattern.compile("\\b0[xX][0-9a-fA-F]+");

    private static final String LIBRARY = FusedIeeeLibrary.source();
    private static final String NATIVE = FusedNoiseCompiler.library();

    /** One violation per constant that is wrong; empty when the text is sound. */
    static List<String> violations(String source) {
        var violations = new ArrayList<String>();
        var names = new HashSet<String>();
        for (String line : source.split("\n", -1)) {
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            if (!HEX.matcher(code).find()) continue;
            Matcher bits64 = ANNOTATED_DOUBLE.matcher(line);
            Matcher bits32 = ANNOTATED_FLOAT.matcher(line);
            if (bits64.matches()) {
                long actual = (Long.parseLong(bits64.group(3), 16) << 32) | Long.parseLong(bits64.group(2), 16);
                long expected = Double.doubleToRawLongBits(Double.parseDouble(bits64.group(4)));
                if (actual != expected) {
                    violations.add(bits64.group(1) + " is 0x" + Long.toHexString(actual) + " but " + bits64.group(4)
                            + " is 0x" + Long.toHexString(expected));
                }
                if (!names.add(bits64.group(1))) violations.add(bits64.group(1) + " is declared twice");
            } else if (bits32.matches()) {
                int actual = (int) Long.parseLong(bits32.group(2), 16);
                int expected = Float.floatToRawIntBits(Float.parseFloat(bits32.group(3)));
                if (actual != expected) {
                    violations.add(bits32.group(1) + " is 0x" + Integer.toHexString(actual) + " but " + bits32.group(3)
                            + " is 0x" + Integer.toHexString(expected));
                }
                if (!names.add(bits32.group(1))) violations.add(bits32.group(1) + " is declared twice");
            } else {
                violations.add("hex constant without its decimal value: " + line.strip());
            }
        }
        return violations;
    }

    @Test
    void everyConstantEqualsTheBitsOfItsDecimalComment() {
        assertEquals(List.of(), violations(LIBRARY));
    }

    @Test
    void theCheckRejectsAWrongBitAnUnannotatedConstantAndANonDecimalComment() {
        String line = "const uvec2 D64_HALF = uvec2(0x00000000u, 0x3fe00000u); // 0.5";
        assertTrue(LIBRARY.contains(line));
        assertEquals(1, violations(LIBRARY.replace(line, line.replace("0x3fe00000u", "0x3fe00001u"))).size());
        assertEquals(1, violations(LIBRARY.replace(line, line.replace("// 0.5", "// 0.25"))).size());
        assertEquals(1, violations(LIBRARY.replace(line, line.replace(" // 0.5", ""))).size());
        assertEquals(1, violations(LIBRARY.replace(line, line.replace("// 0.5", "// half"))).size());
        String single = "const uint F32_ONE = 0x3f800000u; // 1.0";
        assertTrue(LIBRARY.contains(single));
        assertEquals(1, violations(LIBRARY.replace(single, single.replace("0x3f800000u", "0x3f800001u"))).size());
        // a hex constant hidden inside a function body is not annotated either
        assertEquals(1, violations(LIBRARY.replace("uint jminf(uint a, uint b) {", "uint jminf(uint a, uint b) {\n    uint stray = 0x3f800000u;")).size());
        // a duplicated declaration
        assertEquals(1, violations(LIBRARY.replace(single, single + "\n" + single)).size());
    }

    @Test
    void bothWidthsAreCoveredAndEveryConstantIsUsed() {
        long doubles = LIBRARY.lines().filter(line -> ANNOTATED_DOUBLE.matcher(line).matches()).count();
        long floats = LIBRARY.lines().filter(line -> ANNOTATED_FLOAT.matcher(line).matches()).count();
        assertTrue(doubles >= 15, "binary64 constants: " + doubles);
        assertTrue(floats >= 10, "binary32 constants: " + floats);
        String code = FusedLibraryText.withoutComments(LIBRARY);
        Set<String> names = new HashSet<>();
        for (String line : LIBRARY.split("\n")) {
            Matcher a = ANNOTATED_DOUBLE.matcher(line), b = ANNOTATED_FLOAT.matcher(line);
            if (a.matches()) names.add(a.group(1));
            if (b.matches()) names.add(b.group(1));
        }
        for (String name : names) {
            int uses = 0;
            Matcher use = Pattern.compile("\\b" + name + "\\b").matcher(code);
            while (use.find()) uses++;
            assertTrue(uses >= 2, name + " is declared but never used");
        }
    }

    @Test
    void foldedConstantsAreTheExactValueOfTheNativeExpression() {
        assertEquals(1.0 / 512.0, valueOf("D64_INV_512"));
        assertEquals(1.0 / 128.0, valueOf("D64_INV_128"));
        assertEquals(2.0 * valueOf("SIMPLEX_G2"), valueOf("SIMPLEX_2G2"));
        assertTrue(NATIVE.contains("1.0lf / 512.0lf") && NATIVE.contains("1.0lf / 128.0lf") && NATIVE.contains("2.0lf * SIMPLEX_G2"),
                "the native expressions these constants fold have changed");
    }

    /** Every decimal literal of the native library has a constant of the same value (or its negation) in the port. */
    @Test
    void everyNativeLiteralHasAMatchingConstant() {
        String nativeCode = FusedLibraryText.withoutComments(NATIVE);
        Matcher literal = Pattern.compile("(?<![A-Za-z0-9_.])(\\d+\\.\\d*(?:[eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)(lf|LF)?(?![A-Za-z0-9_.])")
                .matcher(FusedLibraryText.hexLiteralsBlanked(nativeCode));
        Set<Long> doubles = new HashSet<>();
        Set<Integer> floats = new HashSet<>();
        for (String line : LIBRARY.split("\n")) {
            Matcher a = ANNOTATED_DOUBLE.matcher(line), b = ANNOTATED_FLOAT.matcher(line);
            if (a.matches()) doubles.add(Double.doubleToRawLongBits(Math.abs(Double.parseDouble(a.group(4)))));
            if (b.matches()) floats.add(Float.floatToRawIntBits(Math.abs(Float.parseFloat(b.group(3)))));
        }
        // Folded away or absorbed: 512 only occurs in 1/512, and the two int-narrowing guards are what
        // wgf_fp64_to_i32_java decides for every input.
        Set<String> exempt = Set.of("512.0lf", "2147483647.0lf", "2147483648.0lf");
        var missing = new ArrayList<String>();
        int checked = 0;
        while (literal.find()) {
            String text = literal.group();
            if (exempt.contains(text)) continue;
            checked++;
            boolean found = literal.group(2) != null
                    ? doubles.contains(Double.doubleToRawLongBits(Double.parseDouble(literal.group(1))))
                    : floats.contains(Float.floatToRawIntBits(Float.parseFloat(literal.group(1))));
            if (!found) missing.add(text);
        }
        assertTrue(checked > 40, "literals checked: " + checked);
        assertEquals(List.of(), missing, "native literals without a constant in the port");
        assertFalse(NATIVE.isEmpty());
    }

    private static double valueOf(String name) {
        for (String line : LIBRARY.split("\n")) {
            Matcher a = ANNOTATED_DOUBLE.matcher(line);
            if (a.matches() && a.group(1).equals(name)) return Double.parseDouble(a.group(4));
        }
        throw new AssertionError("no constant " + name);
    }
}
