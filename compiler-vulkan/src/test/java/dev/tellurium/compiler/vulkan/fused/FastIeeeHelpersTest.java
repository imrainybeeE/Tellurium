// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FastIeeeModel.Mutation;
import dev.tellurium.compiler.vulkan.fused.FastIeeeVectors.Format;
import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;
import java.util.stream.IntStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FastIeeeHelpers} is checked as text (no floating type, the contract with {@code IntegerIeeeEmitter}) and
 * as {@link FastIeeeModel}, a statement-for-statement Java copy of the shader text, against Java's own
 * {@code a + b}, {@code a - b} and {@code a * b} (binary32 with {@code float}) on listed edge values and on
 * seeded random operands. A mutation test shows the vectors notice a broken rounding step. That the GLSL itself
 * behaves as the model is checked on the device by {@code nativeIntegerIeee -Ptellurium.ieeeFast=true}.
 */
class FastIeeeHelpersTest {
    private static final int RANDOM_PER_FUNCTION = 10_000_000;
    private static final int MUTATION_INPUTS = 300_000;
    private static final int BLOCK = 100_000;

    private static final Format F64 = Format.BINARY64;
    private static final Format F32 = Format.BINARY32;

    private enum Op {
        ADD64(F64, true, false), SUB64(F64, true, false), MUL64(F64, false, true),
        ADD32(F32, true, false), SUB32(F32, true, false), MUL32(F32, false, true);

        final Format format;
        final boolean additive, multiplicative;
        Op(Format format, boolean additive, boolean multiplicative) { this.format = format; this.additive = additive; this.multiplicative = multiplicative; }
    }

    private static long model(FastIeeeModel m, Op op, long a, long b) {
        return switch (op) {
            case ADD64 -> m.fp64Add(a, b);
            case SUB64 -> m.fp64Sub(a, b);
            case MUL64 -> m.fp64Mul(a, b);
            case ADD32 -> m.fp32Add((int) a, (int) b) & 0xffffffffL;
            case SUB32 -> m.fp32Sub((int) a, (int) b) & 0xffffffffL;
            case MUL32 -> m.fp32Mul((int) a, (int) b) & 0xffffffffL;
        };
    }

    private static long java(Op op, long a, long b) {
        double da = Double.longBitsToDouble(a), db = Double.longBitsToDouble(b);
        float fa = Float.intBitsToFloat((int) a), fb = Float.intBitsToFloat((int) b);
        return switch (op) {
            case ADD64 -> Double.doubleToRawLongBits(da + db);
            case SUB64 -> Double.doubleToRawLongBits(da - db);
            case MUL64 -> Double.doubleToRawLongBits(da * db);
            case ADD32 -> Float.floatToRawIntBits(fa + fb) & 0xffffffffL;
            case SUB32 -> Float.floatToRawIntBits(fa - fb) & 0xffffffffL;
            case MUL32 -> Float.floatToRawIntBits(fa * fb) & 0xffffffffL;
        };
    }

    private static boolean sameResult(Op op, long expected, long actual) {
        if (op.format == F64 ? Double.isNaN(Double.longBitsToDouble(expected)) : Float.isNaN(Float.intBitsToFloat((int) expected))) {
            return op.format == F64 ? Double.isNaN(Double.longBitsToDouble(actual)) : Float.isNaN(Float.intBitsToFloat((int) actual));
        }
        return expected == actual;
    }

    /** Runs the pairs and returns {mismatches, fast-path results}; the first mismatches are collected for the failure message. */
    private static long[] run(FastIeeeModel m, Op op, long[][] pairs, List<String> firstFailures) {
        long mismatches = 0;
        for (long[] pair : pairs) mismatches += one(m, op, pair[0], pair[1], firstFailures);
        return new long[] {mismatches, m.fast};
    }

    private static int one(FastIeeeModel m, Op op, long a, long b, List<String> firstFailures) {
        long expected = java(op, a, b), actual = model(m, op, a, b);
        if (sameResult(op, expected, actual)) return 0;
        if (firstFailures != null && firstFailures.size() < 8) {
            firstFailures.add(op + " a=0x" + Long.toHexString(a) + " b=0x" + Long.toHexString(b) + " expected=0x" + Long.toHexString(expected) + " actual=0x" + Long.toHexString(actual));
        }
        return 1;
    }

    private static long[] pairFor(Op op, SplittableRandom random) {
        return op.multiplicative ? FastIeeeVectors.mulPair(op.format, random) : FastIeeeVectors.addPair(op.format, random);
    }

    // ---- listed vectors ---------------------------------------------------------------------------------

    @Test
    void listedEdgeVectorsMatchJavaForEveryPairOnEveryFunction() {
        for (Op op : Op.values()) {
            var model = new FastIeeeModel(Mutation.NONE);
            List<Long> listed = FastIeeeVectors.listed(op.format);
            var failures = new ArrayList<String>();
            long mismatches = 0, pairs = 0;
            for (long a : listed) for (long b : listed) { mismatches += one(model, op, a, b, failures); pairs++; }
            if (op.additive) for (long[] tie : FastIeeeVectors.addTies(op.format)) { mismatches += one(model, op, tie[0], tie[1], failures); pairs++; }
            assertEquals(0, mismatches, op + " " + failures);
            assertTrue(model.fast > pairs / 4, op + " exercised the fast path " + model.fast + " of " + pairs);
            assertTrue(model.fallback > 0, op + " never fell back");
        }
    }

    @Test
    void namedBoundaryCasesMatchJava() {
        double minNormal = Double.MIN_NORMAL, maxValue = Double.MAX_VALUE;
        double[][] cases = {
                {minNormal, minNormal}, {minNormal, -minNormal}, {minNormal * 1.5, -minNormal}, {minNormal * 1.5, -minNormal * 1.25},
                {Math.nextUp(minNormal), -minNormal}, {minNormal, -Math.nextUp(minNormal)}, {maxValue, maxValue}, {maxValue, Math.ulp(maxValue) / 2},
                {maxValue, Math.ulp(maxValue) / 2 * 1.0000000000000002}, {maxValue, -maxValue}, {maxValue, 1.0}, {1.0, Math.ulp(1.0) / 2},
                {1.0, Math.ulp(1.0) / 2 * 1.0000000000000002}, {1.0 + Math.ulp(1.0), Math.ulp(1.0) / 2}, {1.0, -Math.ulp(1.0) / 4},
                {1.0, -Math.ulp(1.0) / 2}, {2.0, -Math.ulp(2.0) / 4}, {1e308, 1e308}, {1e-308, -1e-308}, {0.1, 0.2}, {1.0, 1e-17},
                {Math.scalb(1.0, 1023), Math.scalb(1.0, 1023)}, {Math.scalb(1.0, -1022), Math.scalb(1.0, -1074)},
                {Math.scalb(1.0, 600), Math.scalb(1.0, 600)}, {Math.scalb(1.0, -600), Math.scalb(1.0, -600)},
                {Math.scalb(1.0, -511), Math.scalb(1.0, -511)}, {Math.scalb(1.0, 511), Math.scalb(1.0, 512)},
                {Math.scalb(1.0, -512), Math.scalb(1.0, -510)}, {Math.sqrt(2), Math.sqrt(2)}, {1.0 - Math.ulp(0.5), 1.0 - Math.ulp(0.5)}
        };
        for (double[] pair : cases) {
            for (int signs = 0; signs < 4; signs++) {
                double a = (signs & 1) == 0 ? pair[0] : -pair[0], b = (signs & 2) == 0 ? pair[1] : -pair[1];
                for (Op op : new Op[] {Op.ADD64, Op.SUB64, Op.MUL64}) {
                    assertEquals(0, one(new FastIeeeModel(Mutation.NONE), op, Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b), null), op + " " + a + " " + b);
                }
                float fa = (float) a, fb = (float) b;
                for (Op op : new Op[] {Op.ADD32, Op.SUB32, Op.MUL32}) {
                    assertEquals(0, one(new FastIeeeModel(Mutation.NONE), op, Float.floatToRawIntBits(fa) & 0xffffffffL, Float.floatToRawIntBits(fb) & 0xffffffffL, null), op + " " + fa + " " + fb);
                }
            }
        }
        float[][] floats = {
                {Float.MIN_NORMAL, -Float.MIN_NORMAL}, {Float.MIN_NORMAL * 1.5f, -Float.MIN_NORMAL}, {Float.MAX_VALUE, Float.MAX_VALUE},
                {Float.MAX_VALUE, Math.ulp(Float.MAX_VALUE) / 2}, {1.0f, Math.ulp(1.0f) / 2}, {1.0f + Math.ulp(1.0f), Math.ulp(1.0f) / 2},
                {16777216f, 1f}, {16777217f, 1f}, {16777215f, 0.5f}, {0.1f, 0.2f}, {Float.MAX_VALUE, 1e30f}, {1e-20f, 1e-20f}, {1e20f, 1e20f}
        };
        for (float[] pair : floats) for (int signs = 0; signs < 4; signs++) {
            float a = (signs & 1) == 0 ? pair[0] : -pair[0], b = (signs & 2) == 0 ? pair[1] : -pair[1];
            for (Op op : new Op[] {Op.ADD32, Op.SUB32, Op.MUL32}) {
                assertEquals(0, one(new FastIeeeModel(Mutation.NONE), op, Float.floatToRawIntBits(a) & 0xffffffffL, Float.floatToRawIntBits(b) & 0xffffffffL, null), op + " " + a + " " + b);
            }
        }
    }

    /** binary32 exponent distance x lowest bits x signs, exhaustively over the interesting significand patterns. */
    @Test
    void binary32AddAndMulOverAllExponentDistancesAndSignificandEdgePatterns() {
        long[] fractions = {0, 1, 2, 3, 0x7fffff, 0x7ffffe, 0x400000, 0x3fffff, 0x400001, 0x555555, 0x2aaaaa, 0x7fff00, 0x0000ff, 0x100000, 0x00ffff};
        var model = new FastIeeeModel(Mutation.NONE);
        long count = 0;
        for (int ea = 1; ea <= 254; ea += 1) for (int eb = Math.max(1, ea - 40); eb <= Math.min(254, ea + 40); eb++) {
            for (long fa : fractions) for (long fb : fractions) for (int signs = 0; signs < 4; signs++) {
                long a = F32.make(signs & 1, ea, fa), b = F32.make(signs >> 1, eb, fb);
                for (Op op : new Op[] {Op.ADD32, Op.SUB32, Op.MUL32}) {
                    assertEquals(0, one(model, op, a, b, null), op + " a=0x" + Long.toHexString(a) + " b=0x" + Long.toHexString(b));
                    count++;
                }
            }
        }
        assertTrue(count > 20_000_000L);
        assertTrue(model.fast > count / 2);
    }

    // ---- random ----------------------------------------------------------------------------------------

    @Test
    void seededRandomOperandsMatchJavaOnEveryFunction() {
        long seed = 0x7e11a1c0f05ed002L;
        for (Op op : Op.values()) {
            AtomicLong mismatches = new AtomicLong(), fast = new AtomicLong(), fallback = new AtomicLong();
            var failures = java.util.Collections.synchronizedList(new ArrayList<String>());
            int blocks = RANDOM_PER_FUNCTION / BLOCK;
            long opSeed = seed + op.ordinal() * 0x9e3779b97f4a7c15L;
            IntStream.range(0, blocks).parallel().forEach(block -> {
                var random = new SplittableRandom(opSeed ^ (block * 0xbf58476d1ce4e5b9L));
                var model = new FastIeeeModel(Mutation.NONE);
                long bad = 0;
                for (int i = 0; i < BLOCK; i++) {
                    long[] pair = pairFor(op, random);
                    bad += one(model, op, pair[0], pair[1], failures);
                }
                mismatches.addAndGet(bad);
                fast.addAndGet(model.fast);
                fallback.addAndGet(model.fallback);
            });
            assertEquals(0, mismatches.get(), op + " " + failures);
            long total = (long) blocks * BLOCK;
            assertTrue(fast.get() > total / 2, op + " fast path ran " + fast.get() + " of " + total);
            assertTrue(fallback.get() > total / 100, op + " fallback ran " + fallback.get() + " of " + total);
        }
    }

    // ---- mutations -------------------------------------------------------------------------------------

    @Test
    void aBrokenRoundingStepIsCaught() {
        Map<Mutation, Op> targets = new LinkedHashMap<>();
        targets.put(Mutation.ADD64_NO_STICKY, Op.ADD64);
        targets.put(Mutation.ADD64_HALF_UP, Op.ADD64);
        targets.put(Mutation.MUL64_NO_LOW_WORD, Op.MUL64);
        targets.put(Mutation.MUL64_TIE_UP, Op.MUL64);
        targets.put(Mutation.ADD32_NO_STICKY, Op.ADD32);
        targets.put(Mutation.ADD32_HALF_UP, Op.ADD32);
        targets.put(Mutation.MUL32_TIE_UP, Op.MUL32);
        for (var entry : targets.entrySet()) {
            Op op = entry.getValue();
            var random = new SplittableRandom(42);
            var model = new FastIeeeModel(entry.getKey());
            var reference = new FastIeeeModel(Mutation.NONE);
            long caught = 0, referenceBad = 0;
            for (int i = 0; i < MUTATION_INPUTS; i++) {
                long[] pair = pairFor(op, random);
                caught += one(model, op, pair[0], pair[1], null);
                referenceBad += one(reference, op, pair[0], pair[1], null);
            }
            assertEquals(0, referenceBad, "the unmutated model is exact on the same inputs: " + op);
            assertTrue(caught > 0, "mutation " + entry.getKey() + " was not caught by " + MUTATION_INPUTS + " vectors");
        }
    }

    // ---- the text --------------------------------------------------------------------------------------

    private static final Pattern DECLARATION = Pattern.compile("(?m)^(uint64_t|uint|int|bool|uvec2)\\s+(wgx_[A-Za-z0-9_]+)\\(([^)]*)\\)\\s*\\{");

    @Test
    void theTextNamesNoFloatingTypeAndPassesTheNumericContract() {
        String source = FastIeeeHelpers.source();
        assertFalse(Pattern.compile("(?s).*\\b(float|double|half|[df]?vec[234]|f16vec[234]|d?mat[234](x[234])?)\\b.*").matcher(source).matches());
        assertFalse(Pattern.compile("(?<![A-Za-z0-9_])\\d+\\.\\d*|\\.\\d").matcher(source).find(), "floating literal");
        assertFalse(source.contains("#extension"), "the directive belongs to the shader header");
        assertFalse(source.contains("\r"));
        var inspection = new SpirvNumericContract().inspect(source + "\nuint index = gl_GlobalInvocationID.x;\n", NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
    }

    @Test
    void theFallbacksAreExactlyTheWgHelpersTheTextCallsAndTheyExist() {
        Matcher calls = Pattern.compile("\\b(wg_[A-Za-z0-9_]+)\\s*\\(").matcher(FastIeeeHelpers.source());
        var called = new LinkedHashSet<String>();
        while (calls.find()) called.add(calls.group(1));
        assertEquals(FastIeeeHelpers.HELPERS, called);
        String closure = IntegerIeeeEmitter.helperSource(FastIeeeHelpers.HELPERS);
        for (String name : FastIeeeHelpers.HELPERS) assertTrue(closure.contains(" " + name + "("), name);
        assertFalse(closure.contains("wg_failed"));
    }

    @Test
    void everyHelperIsDeclaredOnceBeforeUseWithTheSignatureOfItsWgCounterpart() {
        String source = FastIeeeHelpers.source();
        Matcher header = DECLARATION.matcher(source);
        var order = new ArrayList<String>();
        var signatures = new LinkedHashMap<String, String>();
        while (header.find()) {
            order.add(header.group(2));
            signatures.put(header.group(2), signature(header.group(1), header.group(3)));
        }
        assertEquals(FastIeeeHelpers.NAMES, order);
        Matcher uses = Pattern.compile("\\b(wgx_[A-Za-z0-9_]+)\\s*\\(").matcher(source);
        while (uses.find()) assertTrue(order.contains(uses.group(1)), uses.group(1));
        Matcher wg = Pattern.compile("(?m)^(uint|int|bool|uvec2|uvec4)\\s+(wg_[A-Za-z0-9_]+)\\(([^)]*)\\)\\s*\\{").matcher(IntegerIeeeEmitter.source());
        Map<String, String> counterparts = new LinkedHashMap<>();
        while (wg.find()) counterparts.put(wg.group(2), signature(wg.group(1), wg.group(3)));
        for (var entry : signatures.entrySet()) {
            assertEquals(counterparts.get("wg_" + entry.getKey().substring(4)), entry.getValue(), entry.getKey());
        }
        // sub is declared after the add it calls
        assertTrue(source.indexOf("uvec2 wgx_fp64_add(") < source.indexOf("wgx_fp64_add(left, uvec2(right.x"));
    }

    private static String signature(String returnType, String parameters) {
        var types = new ArrayList<String>();
        for (String parameter : parameters.split(",")) {
            String trimmed = parameter.trim();
            if (!trimmed.isEmpty()) types.add(trimmed.split("\\s+")[0]);
        }
        return returnType + "(" + String.join(",", types) + ")";
    }
}
