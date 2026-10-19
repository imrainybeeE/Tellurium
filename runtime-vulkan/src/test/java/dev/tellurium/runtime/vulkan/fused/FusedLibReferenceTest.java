// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import java.util.Random;
import java.util.SplittableRandom;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device campaign trusts {@link FusedLibReference} as the answer for {@code fused_lib_ieee.glsl}, so the
 * reference is itself checked here against plain Java wherever Java has an expression for the same thing:
 * casts, {@code Math}, the float remainder, {@code java.util.Random}, and an independent recursive spline.
 * What has no plain Java counterpart (the noise octaves, the End island) is held by the campaign's
 * structure checks in {@link FusedLibCampaignTest}.
 */
class FusedLibReferenceTest {
    private static final int SAMPLES = 200_000;

    private static long[][] cases(String name, int randomCount) {
        FusedLibCampaign.Op op = FusedLibCampaignTest.operation(name);
        var random = new SplittableRandom(0x1234abcdL ^ name.hashCode());
        var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
        long[][] result = new long[op.fixed().length + randomCount][];
        System.arraycopy(op.fixed(), 0, result, 0, op.fixed().length);
        for (int i = op.fixed().length; i < result.length; i++) result[i] = op.gen().next(random, tables);
        return result;
    }

    private static double d(long bits) { return Double.longBitsToDouble(bits); }
    private static float f(long bits) { return Float.intBitsToFloat((int) bits); }

    private static void assertSameDouble(double expected, double actual, String message) {
        if (Double.isNaN(expected)) assertTrue(Double.isNaN(actual), message);
        else assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), message);
    }

    private static void assertSameFloat(float expected, float actual, String message) {
        if (Float.isNaN(expected)) assertTrue(Float.isNaN(actual), message);
        else assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), message);
    }

    @Test
    void jd2iIsTheJavaCast() {
        for (long[] c : cases("jd2i", SAMPLES)) assertEquals((int) d(c[0]), FusedLibReference.jd2i(d(c[0])), Long.toHexString(c[0]));
    }

    @Test
    void mfloorIsFloorWhereTheIntRangeHolds() {
        for (long[] c : cases("mfloor", SAMPLES)) {
            double value = d(c[0]);
            if (!(value > -2147483648.0 && value < 2147483648.0)) continue;
            assertEquals((int) Math.floor(value), FusedLibReference.mfloor(value), Long.toHexString(c[0]));
        }
        // outside the range Mth.floor saturates and then steps down: the library keeps that
        assertEquals(Integer.MAX_VALUE, FusedLibReference.mfloor(-2147483649.0));
        assertEquals(Integer.MAX_VALUE, FusedLibReference.mfloor(1e300));
        assertEquals(0, FusedLibReference.mfloor(Double.NaN));
    }

    @Test
    void minAndMaxAreJavasIncludingSignedZerosAndNaN() {
        for (long[] c : cases("jmin", SAMPLES)) {
            assertSameDouble(Math.min(d(c[0]), d(c[1])), FusedLibReference.jmin(d(c[0]), d(c[1])), Long.toHexString(c[0]) + "," + Long.toHexString(c[1]));
            assertSameDouble(Math.max(d(c[0]), d(c[1])), FusedLibReference.jmax(d(c[0]), d(c[1])), Long.toHexString(c[0]) + "," + Long.toHexString(c[1]));
        }
        for (long[] c : cases("jminf", SAMPLES)) {
            assertSameFloat(Math.min(f(c[0]), f(c[1])), FusedLibReference.jminf(f(c[0]), f(c[1])), Long.toHexString(c[0]) + "," + Long.toHexString(c[1]));
            assertSameFloat(Math.max(f(c[0]), f(c[1])), FusedLibReference.jmaxf(f(c[0]), f(c[1])), Long.toHexString(c[0]) + "," + Long.toHexString(c[1]));
        }
        // a NaN operand comes back as itself, the first one when both are NaN
        long payload = 0x7ff8000000000123L;
        assertEquals(payload, Double.doubleToRawLongBits(FusedLibReference.jmin(d(payload), 1.0)));
        assertEquals(payload, Double.doubleToRawLongBits(FusedLibReference.jmax(2.0, d(payload))));
        assertEquals(0x7ff8000000000001L, Double.doubleToRawLongBits(FusedLibReference.jmin(d(0x7ff8000000000001L), d(payload))));
    }

    @Test
    void divisionIsAlwaysTheCorrectlyRoundedQuotientOnceZeroSignsAreAccountedFor() {
        var lib = new FusedLibReference(new int[0], new double[0]);
        int bailed = 0, deviations = 0, clean = 0;
        for (long[] c : cases("jdiv", SAMPLES)) {
            double a = d(c[0]), b = d(c[1]);
            lib.clearBails();
            lib.correctlyRoundedZero = false;
            double nativeText = lib.jdiv(a, b);
            int bail = lib.bailCode;
            lib.clearBails();
            lib.correctlyRoundedZero = true;
            double port = lib.jdiv(a, b);
            assertEquals(bail, lib.bailCode);
            assertSameDouble(a / b, port, Long.toHexString(c[0]) + "/" + Long.toHexString(c[1]));
            if (bail != 0) bailed++;
            else clean++;
            if (Double.doubleToRawLongBits(nativeText) != Double.doubleToRawLongBits(port) && !(Double.isNaN(port) && Double.isNaN(nativeText))) {
                // the native text and the correctly rounded quotient disagree only in the sign of a zero
                assertTrue(port == 0.0 && nativeText == 0.0);
                assertEquals(0L, Double.doubleToRawLongBits(nativeText));
                assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(port));
                deviations++;
            }
        }
        assertTrue(bailed > 1000 && clean > 1000, "bailed " + bailed + " clean " + clean);
        assertTrue(deviations > 10, "negative-zero quotients reached: " + deviations);
    }

    @Test
    void theZeroSignDeviationCounterCountsExactlyTheCasesWhereTheTextsDiffer() {
        var lib = new FusedLibReference(new int[0], new double[0]);
        lib.correctlyRoundedZero = true;
        // 2^-899 / -2^898: both operands pass the magnitude guard and the quotient, far below the smallest subnormal, is -0.0
        double tiny = Math.scalb(1.0, -899), huge = -Math.scalb(1.0, 898);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(lib.jdiv(tiny, huge)));
        assertEquals(1, lib.zeroSignDeviations);
        assertEquals(0, lib.bailCode);
        lib.correctlyRoundedZero = false;
        assertEquals(0L, Double.doubleToRawLongBits(lib.jdiv(tiny, huge)));
        assertEquals(1, lib.zeroSignDeviations);
        // a positive-zero quotient is not a deviation
        lib.correctlyRoundedZero = true;
        assertEquals(0L, Double.doubleToRawLongBits(lib.jdiv(tiny, -huge)));
        assertEquals(1, lib.zeroSignDeviations);
    }

    @Test
    void squareRootIsMathSqrtWhetherOrNotItBails() {
        var lib = new FusedLibReference(new int[0], new double[0]);
        int bailed = 0;
        for (long[] c : cases("jsqrt", SAMPLES)) {
            lib.clearBails();
            assertSameDouble(Math.sqrt(d(c[0])), lib.jsqrt(d(c[0])), Long.toHexString(c[0]));
            if (lib.bailCode != 0) bailed++;
        }
        assertTrue(bailed > 1000, "bailed " + bailed);
        // x = -0.0 passes the guards and comes back as itself
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(lib.jsqrt(-0.0)));
    }

    @Test
    void floatDivisionAndSquareRootAreJavas() {
        var lib = new FusedLibReference(new int[0], new double[0]);
        for (long[] c : cases("jdivf", SAMPLES)) {
            assertSameFloat(f(c[0]) / f(c[1]), lib.jdivf(f(c[0]), f(c[1])), Long.toHexString(c[0]) + "/" + Long.toHexString(c[1]));
        }
        for (long[] c : cases("jsqrtf", SAMPLES)) {
            assertSameFloat((float) Math.sqrt((double) f(c[0])), lib.jsqrtf(f(c[0])), Long.toHexString(c[0]));
        }
        lib.clearBails();
        lib.jdivf(1.0f, 0.0f);
        assertEquals(FusedLibReference.BAIL_FLOAT, lib.bailCode);
    }

    @Test
    void floatRemainderIsJavaFmodWhileTheQuotientFitsADouble() {
        var lib = new FusedLibReference(new int[0], new double[0]);
        int compared = 0;
        for (long[] c : cases("jremf", SAMPLES)) {
            float a = f(c[0]), b = f(c[1]);
            lib.clearBails();
            float actual = lib.jremf(a, b);
            if (lib.bailCode != 0) {
                assertEquals(0.0f, actual);
                assertTrue(Float.isNaN(a) || Float.isInfinite(a) || !(b > 0.0f));
                continue;
            }
            // two cases where the library's text is not Java's fmod and no caller goes: an infinite divisor (Java
            // gives a, the library's quotient and product give NaN) and a dividend of -0.0 (the library tests a < 0.0)
            if (Float.isInfinite(b) || a == 0.0f) continue;
            // the library forms da - q * db in binary64, which is exact only while q * db fits 53 bits: a quotient
            // below 2^28 for any divisor, or below 2^44 for a divisor with at most 8 significant bits (13.0 has 4)
            double quotient = Math.abs((double) a) / b;
            boolean shortDivisor = (Float.floatToRawIntBits(b) & 0xffff) == 0;
            if (!(quotient < 0x1p28 || (shortDivisor && quotient < 0x1p44))) continue;
            assertSameFloat(a % b, actual, Long.toHexString(c[0]) + "%" + Long.toHexString(c[1]));
            compared++;
        }
        assertTrue(compared > 10_000, "compared " + compared);
        assertEquals(0, Float.floatToRawIntBits(lib.jremf(-0.0f, 13.0f)));
        assertTrue(Float.isNaN(lib.jremf(5.0f, Float.POSITIVE_INFINITY)));
        // beyond that the library is not an exact fmod: the End islands never get there (a sum below 2^34 over 13.0),
        // and the campaign checks the device against the library's behaviour, not Java's
        assertNotEquals(3.0e38f % 0.001f, lib.jremf(3.0e38f, 0.001f));
        assertNotEquals(-2.5e11f % 75.85f, lib.jremf(-2.5e11f, 75.85f));
    }

    @Test
    void integerHelpersAreJavas() {
        for (long[] c : cases("jfloorDiv", SAMPLES)) {
            int a = (int) c[0], b = (int) c[1];
            assertEquals(Math.floorDiv(a, b), FusedLibReference.jfloorDiv(a, b), a + "," + b);
            assertEquals(Math.floorMod(a, b), FusedLibReference.jfloorMod(a, b), a + "," + b);
            assertEquals(a % b, FusedLibReference.jrem(a, b), a + "," + b);
        }
    }

    @Test
    void legacyRandomIsJavaUtilRandom() {
        var random = new SplittableRandom(99);
        for (int i = 0; i < 20_000; i++) {
            long seed = random.nextLong();
            long scrambled = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
            var jdk = new Random(seed);
            var mine = new FusedLibReference.Rng();
            mine.isLegacy = true;
            mine.legacy = scrambled;
            assertSameFloat(jdk.nextFloat(), FusedLibReference.rngNextFloat(mine), "nextFloat " + seed);
            int bound = random.nextBoolean() ? 1 << random.nextInt(1, 30) : random.nextInt(1, 1_000_000);
            assertEquals(jdk.nextInt(bound), FusedLibReference.rngNextInt(mine, bound), "nextInt " + seed + " bound " + bound);
        }
    }

    @Test
    void xoroshiroNextIsTheJdkGenerator() {
        var random = new SplittableRandom(5);
        for (int i = 0; i < 2_000; i++) {
            long lo = random.nextLong(), hi = random.nextLong();
            byte[] seed = new byte[16];
            java.nio.ByteBuffer.wrap(seed).putLong(lo).putLong(hi);
            var jdk = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(seed);
            var mine = new FusedLibReference.Rng();
            mine.lo = lo;
            mine.hi = hi;
            for (int step = 0; step < 4; step++) assertEquals(jdk.nextLong(), FusedLibReference.xoroNext(mine), "step " + step);
        }
    }

    // ---- splines against a recursive CubicSpline.Multipoint.apply ------------------------------------------

    private static int binarySearchStart(float c, int[] perm, int record) {
        int n = perm[record];
        int lo = 0, remaining = n;
        while (remaining > 0) {
            int step = remaining / 2, k = lo + step;
            if (c < Float.intBitsToFloat(perm[record + 2 + k * 4])) remaining = step;
            else { lo = k + 1; remaining -= step + 1; }
        }
        return lo - 1;
    }

    private static float point(int[] perm, int record, int i, float[] cs) {
        return perm[record + 4 + i * 4] == 0 ? Float.intBitsToFloat(perm[record + 5 + i * 4]) : recursive(perm, perm[record + 5 + i * 4], cs);
    }

    private static float recursive(int[] perm, int record, float[] cs) {
        float c = cs[perm[record + 1]];
        int n = perm[record];
        int i = binarySearchStart(c, perm, record), last = n - 1;
        if (i < 0) return extend(c, perm, record, 0, point(perm, record, 0, cs));
        if (i == last) return extend(c, perm, record, last, point(perm, record, last, cs));
        float g = Float.intBitsToFloat(perm[record + 2 + i * 4]), h = Float.intBitsToFloat(perm[record + 2 + (i + 1) * 4]);
        float k = (c - g) / (h - g);
        float p = point(perm, record, i, cs), q = point(perm, record, i + 1, cs);
        float l = Float.intBitsToFloat(perm[record + 3 + i * 4]), m = Float.intBitsToFloat(perm[record + 3 + (i + 1) * 4]);
        float a = l * (h - g) - (q - p);
        float b = -m * (h - g) + (q - p);
        return lerp(k, p, q) + k * (1.0F - k) * lerp(k, a, b);
    }

    private static float extend(float c, int[] perm, int record, int i, float value) {
        float derivative = Float.intBitsToFloat(perm[record + 3 + i * 4]);
        return derivative == 0.0F ? value : value + derivative * (c - Float.intBitsToFloat(perm[record + 2 + i * 4]));
    }

    private static float lerp(float t, float a, float b) { return a + t * (b - a); }

    @Test
    void splineEvaluationEqualsARecursiveEvaluationWhenItDoesNotBail() {
        var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7)); // the tables the cases were generated for
        var lib = new FusedLibReference(tables.perm, tables.dtab);
        int compared = 0, bailed = 0;
        for (long[] c : cases("splineEval", 40_000)) {
            float[] cs = {f(c[1]), f(c[2]), f(c[3])};
            lib.clearBails();
            float actual = lib.splineEval((int) c[0], cs);
            if (lib.bailCode != 0) {
                // too deep for the stack, or a division by a zero-width interval (unsorted or repeated locations)
                assertEquals(0, lib.bailCode & ~(FusedLibReference.BAIL_RANGE | FusedLibReference.BAIL_FLOAT));
                bailed++;
                continue;
            }
            assertSameFloat(recursive(tables.perm, (int) c[0], cs), actual, "root " + c[0] + " at " + cs[0] + "," + cs[1] + "," + cs[2]);
            compared++;
        }
        assertTrue(compared > 10_000 && bailed > 100, "compared " + compared + " bailed " + bailed);
    }
}
