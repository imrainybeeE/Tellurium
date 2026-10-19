// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

/**
 * How the generated part of the fused kernels spells its floating-point values and operations: the one place
 * where the {@link FusedNoiseCompiler.Dialect}s differ in the code the compiler writes itself (the router
 * functions, the accessors, the header; the hand-written kernel text has its own tables in
 * {@link FusedKernels} and {@link FusedSurfaceKernels}).
 *
 * <p>Every method takes and returns GLSL expression text.  The native strategy returns exactly the text the
 * compiler wrote before the dialects existed, and it relies on the same thing the old code did: its operands
 * are names, literals and calls, so the operators need no parentheses, and nesting calls in the order of the
 * C-style grammar ({@code add(mul(a, b), c)} for {@code a * b + c}) reproduces the old text.  Another strategy
 * can turn each call into a call of a helper in the same nesting, which keeps the evaluation order of the
 * native text.</p>
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

    static FusedArith of(FusedNoiseCompiler.Dialect dialect) {
        return switch (dialect) {
            case NATIVE_FP64 -> Native.INSTANCE;
            case IEEE_BITS -> throw new FusedNoiseCompiler.UnsupportedFusedProgramException("The IEEE_BITS dialect is not implemented");
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
}
