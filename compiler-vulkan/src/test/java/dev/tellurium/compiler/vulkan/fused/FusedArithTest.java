// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two strategies on their own: the text each returns for the operations the compiler writes, and the failure paths of the IEEE one. */
class FusedArithTest {
    private final FusedArith nativeArith = FusedArith.of(FusedNoiseCompiler.Dialect.NATIVE_FP64);
    private final FusedArith ieee = FusedArith.of(FusedNoiseCompiler.Dialect.IEEE_BITS);

    @Test
    void everyDialectHasItsOwnStrategy() {
        assertEquals(FusedNoiseCompiler.Dialect.NATIVE_FP64, nativeArith.dialect());
        assertEquals(FusedNoiseCompiler.Dialect.IEEE_BITS, ieee.dialect());
    }

    @Test
    void nativeStrategyWritesTheTextOfTheOriginalCompiler() {
        assertEquals("precise double", nativeArith.preciseType());
        assertEquals("double", nativeArith.type());
        assertEquals("float", nativeArith.floatType());
        assertEquals("0.390625lf", nativeArith.lit("0.390625lf"));
        assertEquals("a + b", nativeArith.add("a", "b"));
        assertEquals("a - b", nativeArith.sub("a", "b"));
        assertEquals("a * b", nativeArith.mul("a", "b"));
        assertEquals("-a", nativeArith.neg("a"));
        assertEquals("abs(a)", nativeArith.abs("a"));
        assertEquals("floor(a)", nativeArith.floor("a"));
        assertEquals("a < b", nativeArith.lt("a", "b"));
        assertEquals("a <= b", nativeArith.le("a", "b"));
        assertEquals("a > b", nativeArith.gt("a", "b"));
        assertEquals("a >= b", nativeArith.ge("a", "b"));
        assertEquals("a == b", nativeArith.eq("a", "b"));
        assertEquals("a != b", nativeArith.ne("a", "b"));
        assertEquals("double(i)", nativeArith.fromInt("i"));
        assertEquals("float(d)", nativeArith.toFloat("d"));
        assertEquals("double(f)", nativeArith.fromFloat("f"));
    }

    @Test
    void ieeeStrategyCarriesValuesAsIntegersAndCallsTheExactHelpers() {
        assertEquals("uvec2", ieee.type());
        assertEquals("uvec2", ieee.preciseType());
        assertEquals("uint", ieee.floatType());
        assertEquals("wg_fp64_add(a, b)", ieee.add("a", "b"));
        assertEquals("wg_fp64_sub(a, b)", ieee.sub("a", "b"));
        assertEquals("wg_fp64_mul(a, b)", ieee.mul("a", "b"));
        assertEquals("wg_fp64_negate(a)", ieee.neg("a"));
        assertEquals("wg_fp64_abs(a)", ieee.abs("a"));
        assertEquals("wg_fp64_floor(a)", ieee.floor("a"));
        assertEquals("wg_fp64_from_int(i)", ieee.fromInt("i"));
        assertEquals("wg_fp64_to_fp32(d)", ieee.toFloat("d"));
        assertEquals("wg_fp64_from_fp32(f)", ieee.fromFloat("f"));
    }

    /** a &gt; b is less(b, a) and a &gt;= b is less_equal(b, a); not-equal is the unordered one, as in Java. */
    @Test
    void ieeeComparisonsMapToLessAndEqualWithTheOrderOfTheNativeOperators() {
        assertEquals("wg_fp64_less(a, b)", ieee.lt("a", "b"));
        assertEquals("wg_fp64_less_equal(a, b)", ieee.le("a", "b"));
        assertEquals("wg_fp64_less(b, a)", ieee.gt("a", "b"));
        assertEquals("wg_fp64_less_equal(b, a)", ieee.ge("a", "b"));
        assertEquals("wg_fp64_equal(a, b)", ieee.eq("a", "b"));
        assertEquals("!wg_fp64_equal(a, b)", ieee.ne("a", "b"));
    }

    /** The nesting of calls is the parse of the native text: left-associative, with the unary minus binding tighter than the product. */
    @Test
    void ieeeNestingFollowsTheNativeParse() {
        // -d0 * f * 0.5lf
        assertEquals("wg_fp64_mul(wg_fp64_mul(wg_fp64_negate(d0), f), x)", ieee.mul(ieee.mul(ieee.neg("d0"), "f"), "x"));
        // v * v * v
        assertEquals("wg_fp64_mul(wg_fp64_mul(v, v), v)", ieee.mul(ieee.mul("v", "v"), "v"));
    }

    @Test
    void ieeeLiteralsAreTheRawBitsLowWordFirst() {
        assertEquals("uvec2(0x00000000u, 0x00000000u)", ieee.dlit(0.0));
        assertEquals("uvec2(0x00000000u, 0x80000000u)", ieee.dlit(-0.0));
        assertEquals("uvec2(0x00000000u, 0x3ff00000u)", ieee.dlit(1.0));
        assertEquals("uvec2(0x9999999au, 0x3fb99999u)", ieee.dlit(0.1));
        assertEquals("uvec2(0x00000001u, 0x00000000u)", ieee.dlit(Double.MIN_VALUE));
        assertEquals("uvec2(0x00000000u, 0x7ff00000u)", ieee.dlit(Double.POSITIVE_INFINITY));
        assertEquals("uvec2(0x00000000u, 0xfff00000u)", ieee.dlit(Double.NEGATIVE_INFINITY));
        assertEquals("uvec2(0x00000000u, 0x7ff80000u)", ieee.dlit(Double.NaN));
        assertEquals("0x3dcccccdu", ieee.flit(0.1f));
        assertEquals("0x80000000u", ieee.flit(-0.0f));
        assertEquals("0x7fc00000u", ieee.flit(Float.NaN));
    }

    /** The native text is parsed as the GLSL compiler parses it, correctly rounded, so a literal that is not exact still gives the same bits. */
    @Test
    void ieeeParsesTheNativeLiteralTextWithCorrectRounding() {
        assertEquals("uvec2(0x9999999au, 0x3fb99999u)", ieee.lit("0.1lf"));
        assertEquals("uvec2(0x00000000u, 0x3fd80000u)", ieee.lit("0.375lf"));
        assertEquals("uvec2(0x00000000u, 0xbff00000u)", ieee.lit("-1.0lf"));
        assertEquals("uvec2(0x33333334u, 0x3fd33333u)", ieee.lit("0.30000000000000004lf"));
        assertEquals("uvec2(0x00000000u, 0x3ca00000u)", ieee.lit("1.1102230246251565E-16lf"));
        assertEquals("uvec2(0x00000001u, 0x00000000u)", ieee.lit("4.9E-324lf"));
    }

    @Test
    void ieeeRefusesLiteralTextThatIsNotANativeLiteral() {
        for (String text : List.of("1.0", "1.0f", "1lf", "abc", "1.0lf + 2.0lf", "", "0x1p3lf")) {
            assertThrows(IllegalArgumentException.class, () -> ieee.lit(text), text);
        }
    }

    @Test
    void handWrittenConstantsAreTurnedIntoBitsAndASubtractionIsNotTakenForASign() {
        assertEquals("x = uvec2(0x00000000u, 0xbff00000u);", FusedArith.Ieee.literals("x = -1.0lf;"));
        assertEquals("f(uvec2(0x00000000u, 0xbff00000u), 0u)", FusedArith.Ieee.literals("f(-1.0lf, 0u)"));
        assertEquals("c ? uvec2(0x00000000u, 0xbff00000u) : y", FusedArith.Ieee.literals("c ? -1.0lf : y"));
        // After a name the minus is a subtraction: the sign is not folded into the constant, and the text stays wrong on purpose.
        assertEquals("a - uvec2(0x00000000u, 0x3ff00000u)", FusedArith.Ieee.literals("a - 1.0lf"));
        assertEquals("0x3e000000u", FusedArith.Ieee.literals("0.125f"));
        assertEquals("0xbe000000u", FusedArith.Ieee.literals("-0.125f"));
        assertEquals("uvec2(0x00000000u, 0x80000000u)", FusedArith.Ieee.literals("-0.0lf"));
        // A literal without a suffix is not one of the native text's 64-bit or 32-bit constants and is left for the text check to find.
        assertEquals("v = 1.0;", FusedArith.Ieee.literals("v = 1.0;"));
        // Names with digits and dots in them are not literals.
        assertEquals("p.x1.5y", FusedArith.Ieee.literals("p.x1.5y"));
    }

    @Test
    void aSwapThatChangesNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FusedArith.Swap("int a;", "int a;"));
    }

    @Test
    void aSwapAppliesExactlyOnceAndAMissingOrRepeatedAnchorThrows() {
        String text = "uvec2 a;\nint b;\nint b;\n";
        assertEquals("uvec2 c;\nint b;\nint b;\n", FusedArith.Ieee.apply(text, List.of(new FusedArith.Swap("uvec2 a;", "uvec2 c;"))));
        assertTrue(assertThrows(IllegalStateException.class, () -> FusedArith.Ieee.apply(text, List.of(new FusedArith.Swap("int b;", "int c;"))))
                .getMessage().contains("int b;"));
        assertThrows(IllegalStateException.class, () -> FusedArith.Ieee.apply(text, List.of(new FusedArith.Swap("int z;", "int c;"))));
        // Each swap sees the result of the one before: the second anchor exists only after the first has been applied.
        assertEquals("uvec2 d;\nint b;\nint b;\n", FusedArith.Ieee.apply(text,
                List.of(new FusedArith.Swap("uvec2 a;", "uvec2 c;"), new FusedArith.Swap("uvec2 c;", "uvec2 d;"))));
        assertThrows(IllegalStateException.class, () -> FusedArith.Ieee.apply(text,
                List.of(new FusedArith.Swap("uvec2 c;", "uvec2 d;"), new FusedArith.Swap("uvec2 a;", "uvec2 c;"))));
    }
}
