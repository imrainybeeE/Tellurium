// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the generated part of the fused kernels spells its floating-point values and operations: the one place
 * where the two {@link FusedNoiseCompiler.Dialect}s differ in the code the compiler writes itself (the router
 * functions, the accessors, the header; the hand-written kernel text has its own tables in
 * {@link FusedKernels} and {@link FusedSurfaceKernels}).
 *
 * <p>Every method takes and returns GLSL expression text.  The native strategy returns exactly the text the
 * compiler wrote before the dialects existed, and it relies on the same thing the old code did: its operands
 * are names, literals and calls, so the operators need no parentheses, and nesting calls in the order of the
 * C-style grammar ({@code add(mul(a, b), c)} for {@code a * b + c}) reproduces the old text.  The IEEE
 * strategy turns each call into a call of an exact integer helper, in the same nesting, so the evaluation
 * order of the operations is the order of the native text.</p>
 */
interface FusedArith {
    FusedNoiseCompiler.Dialect dialect();

    /** Declaration type of a 64-bit floating value. */
    String type();
    /** Declaration type of a 64-bit floating value whose operations must not be contracted. */
    String preciseType();
    /** Declaration type of a 32-bit floating value. */
    String floatType();

    /** A literal the compiler computed. */
    String dlit(double value);
    /** A 32-bit literal the compiler computed (a spline constant, a table float). */
    String flit(float value);
    /** A hand-written 64-bit literal in the spelling of the native text, e.g. {@code 0.390625lf} or {@code -1.0lf}. */
    String lit(String nativeText);

    String add(String a, String b);
    String sub(String a, String b);
    String mul(String a, String b);
    String neg(String a);
    String abs(String a);
    String floor(String a);

    String lt(String a, String b);
    String le(String a, String b);
    String gt(String a, String b);
    String ge(String a, String b);
    String eq(String a, String b);
    String ne(String a, String b);

    /** The 64-bit value of an int expression. */
    String fromInt(String intExpression);
    /** The 32-bit value (rounded to nearest) of a 64-bit expression. */
    String toFloat(String doubleExpression);
    /** The 64-bit value of a 32-bit expression. */
    String fromFloat(String floatExpression);

    /**
     * One replacement of native kernel text by IEEE text, for the hand-written kernels.  {@code from} is native
     * text that must occur exactly once where the swap is applied; {@code to} has its constants in IEEE bits.
     */
    record Swap(String from, String to) {
        public Swap {
            if (from.equals(to)) throw new IllegalArgumentException("A swap that changes nothing: " + from);
        }
    }

    static FusedArith of(FusedNoiseCompiler.Dialect dialect) {
        return switch (dialect) {
            case NATIVE_FP64 -> Native.INSTANCE;
            case IEEE_BITS -> Ieee.INSTANCE;
        };
    }

    /** The text of today's native kernels. */
    final class Native implements FusedArith {
        static final Native INSTANCE = new Native();
        private Native() {}

        @Override public FusedNoiseCompiler.Dialect dialect() { return FusedNoiseCompiler.Dialect.NATIVE_FP64; }
        @Override public String type() { return "double"; }
        @Override public String preciseType() { return "precise double"; }
        @Override public String floatType() { return "float"; }

        @Override public String dlit(double value) { return FusedNoiseCompiler.dlit(value); }
        @Override public String flit(float value) { return FusedNoiseCompiler.flit(value); }
        @Override public String lit(String nativeText) { return nativeText; }

        @Override public String add(String a, String b) { return a + " + " + b; }
        @Override public String sub(String a, String b) { return a + " - " + b; }
        @Override public String mul(String a, String b) { return a + " * " + b; }
        @Override public String neg(String a) { return "-" + a; }
        @Override public String abs(String a) { return "abs(" + a + ")"; }
        @Override public String floor(String a) { return "floor(" + a + ")"; }

        @Override public String lt(String a, String b) { return a + " < " + b; }
        @Override public String le(String a, String b) { return a + " <= " + b; }
        @Override public String gt(String a, String b) { return a + " > " + b; }
        @Override public String ge(String a, String b) { return a + " >= " + b; }
        @Override public String eq(String a, String b) { return a + " == " + b; }
        @Override public String ne(String a, String b) { return a + " != " + b; }

        @Override public String fromInt(String intExpression) { return "double(" + intExpression + ")"; }
        @Override public String toFloat(String doubleExpression) { return "float(" + doubleExpression + ")"; }
        @Override public String fromFloat(String floatExpression) { return "double(" + floatExpression + ")"; }
    }

    /**
     * Every 64-bit value is a {@code uvec2(lo, hi)} of its IEEE bits and every 32-bit value a {@code uint}; every
     * operation is a call of an exact integer helper ({@code IntegerIeeeEmitter}, {@link FusedIeeeHelpers}).
     * {@code a > b} is {@code less(b, a)}: that swaps the order in which the two operands are evaluated, which
     * is only done where the operands are names and literals, as they are in everything the compiler writes.
     */
    final class Ieee implements FusedArith {
        static final Ieee INSTANCE = new Ieee();
        private static final Pattern NATIVE_LITERAL = Pattern.compile("-?[0-9]+\\.[0-9]+(?:[eE][+-]?[0-9]+)?lf");
        private Ieee() {}

        @Override public FusedNoiseCompiler.Dialect dialect() { return FusedNoiseCompiler.Dialect.IEEE_BITS; }
        @Override public String type() { return "uvec2"; }
        @Override public String preciseType() { return "uvec2"; }
        @Override public String floatType() { return "uint"; }

        @Override public String dlit(double value) { return doubleBits(value); }
        @Override public String flit(float value) { return floatBits(value); }

        /** The native text is parsed, correctly rounded, as the GLSL compiler parses it. */
        @Override public String lit(String nativeText) {
            if (!NATIVE_LITERAL.matcher(nativeText).matches()) throw new IllegalArgumentException("Not a native 64-bit literal: " + nativeText);
            return doubleBits(Double.parseDouble(nativeText.substring(0, nativeText.length() - 2)));
        }

        @Override public String add(String a, String b) { return call("wg_fp64_add", a, b); }
        @Override public String sub(String a, String b) { return call("wg_fp64_sub", a, b); }
        @Override public String mul(String a, String b) { return call("wg_fp64_mul", a, b); }
        @Override public String neg(String a) { return call("wg_fp64_negate", a); }
        @Override public String abs(String a) { return call("wg_fp64_abs", a); }
        @Override public String floor(String a) { return call("wg_fp64_floor", a); }

        @Override public String lt(String a, String b) { return call("wg_fp64_less", a, b); }
        @Override public String le(String a, String b) { return call("wg_fp64_less_equal", a, b); }
        @Override public String gt(String a, String b) { return call("wg_fp64_less", b, a); }
        @Override public String ge(String a, String b) { return call("wg_fp64_less_equal", b, a); }
        @Override public String eq(String a, String b) { return call("wg_fp64_equal", a, b); }
        @Override public String ne(String a, String b) { return "!" + call("wg_fp64_equal", a, b); }

        @Override public String fromInt(String intExpression) { return call("wg_fp64_from_int", intExpression); }
        @Override public String toFloat(String doubleExpression) { return call("wg_fp64_to_fp32", doubleExpression); }
        @Override public String fromFloat(String floatExpression) { return call("wg_fp64_from_fp32", floatExpression); }

        private static String call(String function, String... arguments) {
            return function + "(" + String.join(", ", arguments) + ")";
        }

        /** {@code uvec2(lo, hi)} of the raw bits, so a NaN or an infinity keeps its exact bits. */
        static String doubleBits(double value) {
            long bits = Double.doubleToRawLongBits(value);
            return String.format(Locale.ROOT, "uvec2(0x%08xu, 0x%08xu)", (int) bits, (int) (bits >>> 32));
        }

        static String floatBits(float value) {
            return String.format(Locale.ROOT, "0x%08xu", Float.floatToRawIntBits(value));
        }

        /** A swap whose replacement spells its constants as the native text does; see {@link #literals}. */
        static Swap swap(String from, String to) {
            return new Swap(from, literals(to));
        }

        /** Applies the swaps in order, each to the result of the one before; one that does not match exactly once throws. */
        static String apply(String text, List<Swap> swaps) {
            for (Swap swap : swaps) text = FusedKernels.swap(text, swap.from(), swap.to());
            return text;
        }

        private static final Pattern LITERAL = Pattern.compile(
                "(?<![\\w.])([0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE]([+-]?[0-9]+))?(lf|f)(?![\\w.])");

        /**
         * Hand-written IEEE text spells its constants as the native text does, {@code 0.5lf} for a 64-bit
         * value and {@code 0.125f} for a 32-bit one, and this turns each into its bits.  A minus sign is part of
         * the literal only after an opening parenthesis, a comma, {@code ?}, {@code :} or {@code =}; after
         * anything else it is a subtraction, and the text must call the helper.  Text without a suffix is left
         * as it is, so a forgotten one shows up as a floating literal in the generated source.
         */
        static String literals(String text) {
            StringBuilder out = new StringBuilder(text.length() + 64);
            Matcher match = LITERAL.matcher(text);
            int copied = 0;
            while (match.find()) {
                int start = match.start();
                boolean negative = false;
                int before = start - 1;
                while (before >= 0 && text.charAt(before) == ' ') before--;
                if (before >= 0 && text.charAt(before) == '-') {
                    int prior = before - 1;
                    while (prior >= 0 && text.charAt(prior) == ' ') prior--;
                    if (prior < 0 || "(,?:=".indexOf(text.charAt(prior)) >= 0) {
                        negative = true;
                        start = before;
                    }
                }
                out.append(text, copied, start);
                String digits = match.group(1) + (match.group(2) == null ? "" : "E" + match.group(2));
                String suffix = match.group(3);
                if (suffix.equals("lf")) {
                    double value = Double.parseDouble(digits);
                    out.append(doubleBits(negative ? -value : value));
                } else {
                    float value = Float.parseFloat(digits);
                    out.append(floatBits(negative ? -value : value));
                }
                copied = match.end();
            }
            return out.append(text, copied, text.length()).toString();
        }
    }
}
