// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The four fused helpers are checked here as line-by-line Java transliterations of their GLSL (and of the
 * {@code wg_} primitives they call), operating on int words exactly as the GLSL does, against Java's own
 * {@code (int) d}, {@code (double) l}, {@code (float) l} and {@code Math.ceil}. That checks the algorithm
 * and the integer edge cases only; the GLSL itself is checked on the device by
 * {@code nativeIntegerIeee -Ptellurium.ieeeFused=true}.
 */
class FusedIeeeHelpersTest {
    private static final int RANDOM_INPUTS = 10_000_000;

    // ---- the GLSL carrier model: uvec2(lo, hi) as two raw int words --------------------------------------

    private record U2(int x, int y) {}

    /** GLSL leaves a shift by 32 or more undefined, so the model refuses to evaluate one. */
    private static int shl(int value, int shift) {
        if (shift < 0 || shift > 31) throw new IllegalStateException("shift out of GLSL range: " + shift);
        return value << shift;
    }

    private static int shr(int value, int shift) {
        if (shift < 0 || shift > 31) throw new IllegalStateException("shift out of GLSL range: " + shift);
        return value >>> shift;
    }

    private static boolean less(int left, int right) { return Integer.compareUnsigned(left, right) < 0; }

    private static int findMsb(int value) {
        if (value == 0) throw new IllegalStateException("findMSB(0) must be unreachable");
        return 31 - Integer.numberOfLeadingZeros(value);
    }

    private static final U2 QNAN = new U2(0, 0x7ff80000);

    private static int fp64Sign(U2 bits) { return bits.y >>> 31; }
    private static int fp64Exp(U2 bits) { return (bits.y >>> 20) & 0x7ff; }
    private static U2 fp64Frac(U2 bits) { return new U2(bits.x, bits.y & 0xfffff); }
    private static boolean fp64Nan(U2 bits) { return fp64Exp(bits) == 2047 && (bits.x != 0 || (bits.y & 0xfffff) != 0); }
    private static boolean fp64Inf(U2 bits) { return fp64Exp(bits) == 2047 && bits.x == 0 && (bits.y & 0xfffff) == 0; }
    private static boolean fp64Zero(U2 bits) { return (bits.x | (bits.y & 0x7fffffff)) == 0; }
    private static boolean fp64Finite(U2 bits) { return fp64Exp(bits) != 2047; }
    private static U2 fp64Negate(U2 bits) { return new U2(bits.x, bits.y ^ 0x80000000); }

    private static boolean u64Nonzero(U2 value) { return value.x != 0 || value.y != 0; }
    private static U2 u64Shl1(U2 value) { return new U2(value.x << 1, (value.y << 1) | (value.x >>> 31)); }
    private static U2 u64Shl3(U2 value) { return new U2(value.x << 3, (value.y << 3) | (value.x >>> 29)); }

    private static U2 u64Add(U2 left, U2 right) {
        int low = left.x + right.x;
        int carry = less(low, left.x) ? 1 : 0;
        int high = left.y + right.y;
        return new U2(low, high + carry);
    }

    private static U2 u64Sub(U2 left, U2 right) {
        int low = left.x - right.x;
        int borrow = less(left.x, right.x) ? 1 : 0;
        return new U2(low, left.y - right.y - borrow);
    }

    private static boolean u64Less(U2 left, U2 right) {
        return less(left.y, right.y) || (left.y == right.y && less(left.x, right.x));
    }

    private static U2 i64Negate(U2 value) { return u64Add(new U2(~value.x, ~value.y), new U2(1, 0)); }

    private static U2 u64Shl(U2 value, int shift) {
        if (shift == 0) return value;
        if (shift >= 64) return new U2(0, 0);
        if (shift < 32) return new U2(shl(value.x, shift), shl(value.y, shift) | shr(value.x, 32 - shift));
        return new U2(0, shl(value.x, shift - 32));
    }

    private static U2 u64Shr(U2 value, int shift) {
        if (shift == 0) return value;
        if (shift >= 64) return new U2(0, 0);
        if (shift < 32) return new U2(shr(value.x, shift) | shl(value.y, 32 - shift), shr(value.y, shift));
        return new U2(shr(value.y, shift - 32), 0);
    }

    private static U2 u64ShrSticky(U2 value, int shift) {
        if (shift == 0) return value;
        if (shift >= 64) return u64Nonzero(value) ? new U2(1, 0) : new U2(0, 0);
        int resultX, resultY;
        boolean discarded;
        if (shift < 32) {
            int mask = shl(1, shift) - 1;
            resultX = shr(value.x, shift) | shl(value.y, 32 - shift);
            resultY = shr(value.y, shift);
            discarded = (value.x & mask) != 0;
        } else if (shift == 32) {
            resultX = value.y;
            resultY = 0;
            discarded = value.x != 0;
        } else {
            int lowShift = shift - 32;
            int mask = shl(1, lowShift) - 1;
            resultX = shr(value.y, lowShift);
            resultY = 0;
            discarded = value.x != 0 || (value.y & mask) != 0;
        }
        if (discarded) resultX |= 1;
        return new U2(resultX, resultY);
    }

    private static int fp32ShiftSticky(int value, int shift) {
        if (shift == 0) return value;
        if (shift >= 32) return value == 0 ? 0 : 1;
        int mask = shl(1, shift) - 1;
        int result = shr(value, shift);
        return (value & mask) == 0 ? result : (result | 1);
    }

    private static int fp32Pack(int sign, int exponent, int extendedMantissa) {
        if (extendedMantissa == 0) return sign << 31;
        while (!less(extendedMantissa, 1 << 27)) {
            extendedMantissa = fp32ShiftSticky(extendedMantissa, 1);
            exponent++;
        }
        while (less(extendedMantissa, 1 << 26) && exponent > 1) {
            extendedMantissa <<= 1;
            exponent--;
        }
        if (exponent <= 0) {
            extendedMantissa = fp32ShiftSticky(extendedMantissa, 1 - exponent);
            exponent = 0;
        }
        int rounded = extendedMantissa >>> 3;
        int guard = (extendedMantissa >>> 2) & 1;
        int sticky = extendedMantissa & 3;
        if (guard != 0 && (sticky != 0 || (rounded & 1) != 0)) rounded++;
        if (!less(rounded, 1 << 24)) {
            rounded >>>= 1;
            exponent++;
        }
        if (exponent >= 255) return (sign << 31) | 0x7f800000;
        if (exponent == 0) return (sign << 31) | rounded;
        if (exponent == 1 && less(rounded, 1 << 23)) exponent = 0;
        return (sign << 31) | (exponent << 23) | (rounded & 0x7fffff);
    }

    private static U2 fp64Pack(int sign, int exponent, U2 extendedMantissa) {
        if (!u64Nonzero(extendedMantissa)) return new U2(0, sign << 31);
        while ((extendedMantissa.y & 0x01000000) != 0) {
            extendedMantissa = u64ShrSticky(extendedMantissa, 1);
            exponent++;
        }
        while ((extendedMantissa.y & 0x00800000) == 0 && exponent > 1) {
            extendedMantissa = u64Shl1(extendedMantissa);
            exponent--;
        }
        if (exponent <= 0) {
            extendedMantissa = u64ShrSticky(extendedMantissa, 1 - exponent);
            exponent = 0;
        }
        int roundedX = (extendedMantissa.x >>> 3) | (extendedMantissa.y << 29);
        int roundedY = extendedMantissa.y >>> 3;
        int guard = (extendedMantissa.x >>> 2) & 1;
        boolean sticky = (extendedMantissa.x & 3) != 0;
        if (guard != 0 && (sticky || (roundedX & 1) != 0)) {
            int before = roundedX;
            roundedX++;
            if (less(roundedX, before)) roundedY++;
        }
        U2 rounded = new U2(roundedX, roundedY);
        if ((rounded.y & 0x00200000) != 0) {
            rounded = u64ShrSticky(rounded, 1);
            exponent++;
        }
        if (exponent >= 2047) return new U2(0, (sign << 31) | 0x7ff00000);
        if (exponent == 1 && (rounded.y & 0x00100000) == 0) exponent = 0;
        if (exponent == 0 && (rounded.y & 0x00100000) != 0) exponent = 1;
        return new U2(rounded.x, (sign << 31) | (exponent << 20) | (rounded.y & 0xfffff));
    }

    private static U2 fp64Add(U2 left, U2 right) {
        int leftExponent = fp64Exp(left), rightExponent = fp64Exp(right);
        U2 leftFraction = fp64Frac(left), rightFraction = fp64Frac(right);
        if (fp64Nan(left) || fp64Nan(right)) return QNAN;
        if (fp64Inf(left) && fp64Inf(right) && fp64Sign(left) != fp64Sign(right)) return QNAN;
        if (fp64Inf(left)) return left;
        if (fp64Inf(right)) return right;
        if (fp64Zero(left) && fp64Zero(right)) return new U2(0, (fp64Sign(left) & fp64Sign(right)) << 31);
        if (fp64Zero(left)) return right;
        if (fp64Zero(right)) return left;
        int exponentLeft = leftExponent == 0 ? 1 : leftExponent;
        int exponentRight = rightExponent == 0 ? 1 : rightExponent;
        U2 mantissaLeft = u64Shl3(new U2(leftFraction.x, leftFraction.y | (leftExponent == 0 ? 0 : 0x00100000)));
        U2 mantissaRight = u64Shl3(new U2(rightFraction.x, rightFraction.y | (rightExponent == 0 ? 0 : 0x00100000)));
        int signLeft = fp64Sign(left), signRight = fp64Sign(right);
        if (exponentLeft < exponentRight || (exponentLeft == exponentRight && u64Less(mantissaLeft, mantissaRight))) {
            U2 mantissaSwap = mantissaLeft; mantissaLeft = mantissaRight; mantissaRight = mantissaSwap;
            int exponentSwap = exponentLeft; exponentLeft = exponentRight; exponentRight = exponentSwap;
            int signSwap = signLeft; signLeft = signRight; signRight = signSwap;
        }
        mantissaRight = u64ShrSticky(mantissaRight, exponentLeft - exponentRight);
        if (signLeft == signRight) return fp64Pack(signLeft, exponentLeft, u64Add(mantissaLeft, mantissaRight));
        U2 magnitude = u64Sub(mantissaLeft, mantissaRight);
        if (!u64Nonzero(magnitude)) return new U2(0, 0);
        return fp64Pack(signLeft, exponentLeft, magnitude);
    }

    private static U2 fp64Floor(U2 bits) {
        if (!fp64Finite(bits) || fp64Zero(bits)) return bits;
        int exponent = fp64Exp(bits), sign = fp64Sign(bits);
        if (exponent == 0) return sign == 0 ? new U2(0, 0) : new U2(0, 0xbff00000);
        int unbiased = exponent - 1023;
        if (unbiased >= 52) return bits;
        if (unbiased < 0) return sign == 0 ? new U2(0, 0) : new U2(0, 0xbff00000);
        int fractionalBits = 52 - unbiased;
        U2 fraction = fp64Frac(bits);
        int fractionX = fraction.x, fractionY = fraction.y;
        boolean changed;
        if (fractionalBits < 32) {
            int mask = shl(1, fractionalBits) - 1;
            changed = (fractionX & mask) != 0;
            fractionX &= ~mask;
        } else if (fractionalBits == 32) {
            changed = fractionX != 0;
            fractionX = 0;
        } else {
            int mask = shl(1, fractionalBits - 32) - 1;
            changed = fractionX != 0 || (fractionY & mask) != 0;
            fractionX = 0;
            fractionY &= ~mask;
        }
        U2 truncated = new U2(fractionX, (sign << 31) | (exponent << 20) | fractionY);
        return sign != 0 && changed ? fp64Add(truncated, new U2(0, 0xbff00000)) : truncated;
    }

    // ---- the four helpers, statement for statement as FusedIeeeHelpers.source() has them -----------------

    private static int toI32Java(U2 value) {
        if (fp64Nan(value)) return 0;
        int exponent = fp64Exp(value);
        if (exponent < 1023) return 0;
        int unbiased = exponent - 1023;
        if (unbiased >= 31) return fp64Sign(value) != 0 ? -2147483647 - 1 : 2147483647;
        U2 fraction = fp64Frac(value);
        U2 significand = new U2(fraction.x, fraction.y | 0x00100000);
        int magnitude = u64Shr(significand, 52 - unbiased).x;
        return fp64Sign(value) != 0 ? -magnitude : magnitude;
    }

    private static U2 fp64FromI64(U2 value) {
        if (!u64Nonzero(value)) return new U2(0, 0);
        int sign = value.y >>> 31;
        U2 magnitude = sign != 0 ? i64Negate(value) : value;
        int leading = magnitude.y != 0 ? 32 + findMsb(magnitude.y) : findMsb(magnitude.x);
        U2 extended = leading <= 55
                ? u64Shl(magnitude, 55 - leading)
                : u64ShrSticky(magnitude, leading - 55);
        return fp64Pack(sign, 1023 + leading, extended);
    }

    private static int fp32FromI64(U2 value) {
        if (!u64Nonzero(value)) return 0;
        int sign = value.y >>> 31;
        U2 magnitude = sign != 0 ? i64Negate(value) : value;
        int leading = magnitude.y != 0 ? 32 + findMsb(magnitude.y) : findMsb(magnitude.x);
        U2 extended = leading <= 26
                ? u64Shl(magnitude, 26 - leading)
                : u64ShrSticky(magnitude, leading - 26);
        return fp32Pack(sign, 127 + leading, extended.x);
    }

    private static U2 fp64Ceil(U2 value) {
        return fp64Negate(fp64Floor(fp64Negate(value)));
    }

    // ---- the binary32 packer at the edge of the subnormal range ---------------------------------------------

    /**
     * wg_fp32_pack once dropped the carry of a subnormal that rounds up to the smallest normal number and returned
     * zero (found on the Apple M3 through wg_fp64_to_fp32).  The packer's value is mantissa * 2^(exponent - 153),
     * exact in a double for these inputs, so Java's (float) cast of it is the reference.
     */
    @Test
    void theBinary32PackerKeepsTheCarryIntoTheSmallestNormalNumber() {
        SplittableRandom random = new SplittableRandom(0x5eed0f32L);
        for (int i = 0; i < 1_000_000; i++) {
            int exponent = i < 384 ? i / 64 - 3 : random.nextInt(-30, 4);
            int mantissa = i < 384 ? (1 << 27) - 1 - i % 64 : random.nextInt(1, 1 << 28);
            float expected = (float) Math.scalb((double) mantissa, exponent - 153);
            for (int sign = 0; sign <= 1; sign++) {
                int want = Float.floatToRawIntBits(sign == 0 ? expected : -expected);
                int got = fp32Pack(sign, exponent, mantissa);
                if (want != got) {
                    throw new AssertionError(String.format("fp32_pack(%d, %d, 0x%08x) expected 0x%08x actual 0x%08x",
                            sign, exponent, mantissa, want, got));
                }
            }
        }
    }

    // ---- reference comparison ----------------------------------------------------------------------------

    private static U2 words(long bits) { return new U2((int) bits, (int) (bits >>> 32)); }

    private static long bits(U2 value) { return (value.x & 0xffffffffL) | ((long) value.y << 32); }

    private static void checkToI32(long inputBits) {
        double input = Double.longBitsToDouble(inputBits);
        int expected = (int) input;
        int actual = toI32Java(words(inputBits));
        if (expected != actual) {
            fail("(int) 0x" + Long.toHexString(inputBits) + " expected " + expected + " actual " + actual);
        }
    }

    private static void checkFromI64(long input) {
        long expected = Double.doubleToRawLongBits((double) input);
        long actual = bits(fp64FromI64(words(input)));
        if (expected != actual) {
            fail("(double) " + input + " expected 0x" + Long.toHexString(expected) + " actual 0x" + Long.toHexString(actual));
        }
    }

    private static void checkFp32FromI64(long input) {
        int expected = Float.floatToRawIntBits((float) input);
        int actual = fp32FromI64(words(input));
        if (expected != actual) {
            fail("(float) " + input + " expected 0x" + Integer.toHexString(expected) + " actual 0x" + Integer.toHexString(actual));
        }
    }

    private static void checkCeil(long inputBits) {
        double input = Double.longBitsToDouble(inputBits);
        long expected = Double.doubleToRawLongBits(Math.ceil(input));
        long actual = bits(fp64Ceil(words(inputBits)));
        boolean ok = Double.isNaN(input) ? Double.isNaN(Double.longBitsToDouble(actual)) : expected == actual;
        if (!ok) {
            fail("ceil 0x" + Long.toHexString(inputBits) + " expected 0x" + Long.toHexString(expected)
                    + " actual 0x" + Long.toHexString(actual));
        }
    }

    // ---- vectors -----------------------------------------------------------------------------------------

    private static final double TWO31 = 2147483648.0;
    private static final double TWO53 = 9007199254740992.0;

    private static List<Double> doubleVectors() {
        double minSubnormal = Double.longBitsToDouble(1L);
        double maxSubnormal = Double.longBitsToDouble(0x000fffffffffffffL);
        double[] values = {
                0.0, -0.0, minSubnormal, -minSubnormal, maxSubnormal, -maxSubnormal, Double.MIN_NORMAL, -Double.MIN_NORMAL,
                0.5, -0.5, Math.nextDown(0.5), -Math.nextDown(0.5), Math.nextDown(1.0), -Math.nextDown(1.0), 1.0, -1.0,
                Math.nextUp(1.0), -Math.nextUp(1.0), 1.5, -1.5, 2.5, -2.5, 1234.75, -1234.75,
                2147483646.5, -2147483646.5, 2147483646.0, 2147483647.0, -2147483647.0, 2147483647.5, -2147483647.5,
                Math.nextDown(2147483647.0), Math.nextUp(2147483647.0), TWO31, -TWO31, Math.nextUp(TWO31),
                Math.nextDown(TWO31), Math.nextDown(-TWO31), Math.nextUp(-TWO31), -TWO31 - 0.5, -TWO31 - 1.0,
                -TWO31 + 0.5, TWO31 + 0.5, TWO31 + 1.0, 4294967295.0, 4294967296.0, -4294967296.0,
                TWO53, -TWO53, TWO53 - 1.0, -(TWO53 - 1.0), TWO53 + 2.0, 4503599627370495.5, -4503599627370495.5,
                4503599627370496.0, 4503599627370497.0, -4503599627370497.0, 1e300, -1e300, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        var list = new java.util.ArrayList<Double>();
        for (double value : values) list.add(value);
        for (long raw : new long[] {0x7ff8000000000000L, 0x7ff0000000000001L, 0xfff8000000000000L, 0xfff0000000000001L,
                0x7fffffffffffffffL, 0xffffffffffffffffL, 0x7ff8000000000001L}) {
            list.add(Double.longBitsToDouble(raw));
        }
        return list;
    }

    private static long[] int64Vectors() {
        long two53 = 1L << 53, two54 = 1L << 54;
        return new long[] {
                0, 1, -1, 2, -2, 3, 7, 255, 65535, 16777215, 16777216, 16777217, 16777218, 16777219, 16777221, 33554435,
                134217740, -134217740, 2147483647L, -2147483648L, 2147483648L, 4294967295L, 4294967296L,
                two53 - 1, two53, two53 + 1, two53 + 2, two53 + 3, -two53 - 1, -(two53 + 1), -two53,
                two54 + 1, two54 + 2, two54 + 3, two54 + 4, two54 + 5, two54 + 6, two54 + 7, -(two54 + 2), -(two54 + 6),
                (1L << 62) + 1, (1L << 62) + 1024, (1L << 62) + 1025, (1L << 62) + 2048, (1L << 62) + 3072,
                Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1023, Long.MAX_VALUE - 1024, Long.MAX_VALUE - 1025,
                Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1024, Long.MIN_VALUE + 1025,
                Long.MAX_VALUE - (1L << 39) + 1, Long.MAX_VALUE - (1L << 39), Long.MAX_VALUE - (1L << 39) - 1,
                0x7fffff7fffffffffL, 0x7fffff8000000000L, 0x7fffffbfffffffffL, 0x7fffffc000000000L,
                (1L << 40) + (1L << 16), (1L << 40) + (1L << 16) + 1, (1L << 40) + (1L << 16) - 1, 0x0000010000800000L,
                0x0000010000800001L, 0x00000100007fffffL, 0x00ffffffffffffffL, 0x0100000000000000L};
    }

    // ---- random inputs -----------------------------------------------------------------------------------

    private static long edgeDoubleBits(SplittableRandom random) {
        long sign = random.nextBoolean() ? Long.MIN_VALUE : 0L;
        switch (random.nextInt(9)) {
            case 0: return random.nextLong();
            case 1: { // near a power of two, every exponent
                long exponent = random.nextInt(2048);
                long[] fractions = {0, 1, 2, 3, 0xfffffffffffffL, 0xffffffffffffeL, 0xffffffffffffdL, 1L << 51, (1L << 51) - 1, (1L << 51) + 1};
                return sign | (exponent << 52) | fractions[random.nextInt(fractions.length)];
            }
            case 2: { // n + 0.5, a tie between two integers
                long magnitude = random.nextLong(1L << 52) >>> random.nextInt(52);
                return Double.doubleToRawLongBits((random.nextBoolean() ? -1.0 : 1.0) * (magnitude + 0.5));
            }
            case 3: { // around the int range boundaries
                double[] deltas = {-2.0, -1.5, -1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0, 1.5, 2.0};
                double centre = random.nextBoolean() ? TWO31 : (random.nextBoolean() ? 1.0 : 0.0);
                double value = (random.nextBoolean() ? -1.0 : 1.0) * (centre + deltas[random.nextInt(deltas.length)]);
                int steps = random.nextInt(3);
                for (int i = 0; i < steps; i++) value = random.nextBoolean() ? Math.nextUp(value) : Math.nextDown(value);
                return Double.doubleToRawLongBits(value);
            }
            case 4: { // subnormals, sometimes with few bits set
                long fraction = random.nextLong() & 0xfffffffffffffL;
                if (random.nextBoolean()) fraction >>>= random.nextInt(52);
                return sign | fraction;
            }
            case 5: { // infinities and NaNs with every payload shape
                long fraction = random.nextBoolean() ? 0L : (random.nextLong() & 0xfffffffffffffL);
                if (random.nextInt(4) == 0) fraction = 1L << random.nextInt(52);
                return sign | (2047L << 52) | fraction;
            }
            case 6: { // small values with a fraction
                double value = (random.nextInt(4001) - 2000) / (double) (1 << random.nextInt(6));
                return Double.doubleToRawLongBits(value);
            }
            case 7: { // random value of random magnitude up to 2^40
                double value = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(41));
                return Double.doubleToRawLongBits(value);
            }
            default: { // an integer plus or minus a few ulps
                double value = (double) (random.nextLong() >> random.nextInt(64));
                int steps = random.nextInt(3);
                for (int i = 0; i < steps; i++) value = random.nextBoolean() ? Math.nextUp(value) : Math.nextDown(value);
                return Double.doubleToRawLongBits(value);
            }
        }
    }

    /** {@code width} is the significand width of the target format: 53 for binary64, 24 for binary32. */
    private static long edgeInt64(SplittableRandom random, int width) {
        switch (random.nextInt(7)) {
            case 0: return random.nextLong();
            case 1: return random.nextLong() >> random.nextInt(64);
            case 2: { // a power of two and its neighbours
                long value = 1L << random.nextInt(63);
                value += random.nextInt(7) - 3;
                return random.nextBoolean() ? -value : value;
            }
            case 3: { // a rounding tie, or one step either side of it, at a random magnitude
                int leading = width + random.nextInt(63 - width);
                int dropped = leading - (width - 1);
                long significand = (1L << (width - 1)) | (random.nextLong() & ((1L << (width - 1)) - 1));
                long tail;
                switch (random.nextInt(5)) {
                    case 0: tail = 1L << (dropped - 1); break;
                    case 1: tail = ((1L << (dropped - 1)) + 1) & ((1L << dropped) - 1); break;
                    case 2: tail = (1L << (dropped - 1)) - 1; break;
                    case 3: tail = 0; break;
                    default: tail = (1L << dropped) - 1; break;
                }
                long value = (significand << dropped) | tail;
                return random.nextBoolean() ? -value : value;
            }
            case 4: { // the ends of the range
                long[] ends = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1};
                long value = ends[random.nextInt(ends.length)];
                return value + (random.nextInt(2049) - 1024);
            }
            case 5: return random.nextInt(1 << 22) - (1 << 21);
            default: { // a value that fits the target significand exactly, then perturbed by one unit
                long value = random.nextLong() >> (64 - width - random.nextInt(8));
                return value + (random.nextInt(3) - 1);
            }
        }
    }

    // ---- tests -------------------------------------------------------------------------------------------

    @Test
    void doubleToIntFollowsJavaCastOnEveryListedVector() {
        for (double value : doubleVectors()) checkToI32(Double.doubleToRawLongBits(value));
    }

    @Test
    void doubleToIntFollowsJavaCastOnRandomInputs() {
        var random = new SplittableRandom(0x5eedc0de0001L);
        for (int i = 0; i < RANDOM_INPUTS; i++) checkToI32(edgeDoubleBits(random));
    }

    @Test
    void int64ToDoubleRoundsToNearestEvenOnEveryListedVector() {
        for (long value : int64Vectors()) checkFromI64(value);
    }

    @Test
    void int64ToDoubleRoundsToNearestEvenOnRandomInputs() {
        var random = new SplittableRandom(0x5eedc0de0002L);
        for (int i = 0; i < RANDOM_INPUTS; i++) checkFromI64(edgeInt64(random, 53));
    }

    @Test
    void int64ToFloatRoundsOnceOnEveryListedVector() {
        for (long value : int64Vectors()) checkFp32FromI64(value);
    }

    @Test
    void int64ToFloatRoundsOnceOnRandomInputs() {
        var random = new SplittableRandom(0x5eedc0de0003L);
        for (int i = 0; i < RANDOM_INPUTS; i++) checkFp32FromI64(edgeInt64(random, 24));
    }

    @Test
    void int64ToFloatIsNotTheDoubleRoundingOfTheDoubleConversion() {
        // 2^57 + 2^33 + 1 is above a tie for binary32, but the binary64 rounding drops the low bit and leaves an
        // exact tie that rounds to even: narrowing the binary64 value gives the wrong binary32 value.
        long value = (1L << 57) + (1L << 33) + 1L;
        assertEquals(Float.floatToRawIntBits((float) value), fp32FromI64(words(value)));
        assertTrue((float) (double) value != (float) value, "the vector must separate one rounding from two");
        // The same family at the other end of the range.
        long near = Long.MAX_VALUE - (1L << 39) + 1L;
        assertEquals(Float.floatToRawIntBits((float) near), fp32FromI64(words(near)));
    }

    @Test
    void ceilFollowsJavaOnEveryListedVector() {
        for (double value : doubleVectors()) checkCeil(Double.doubleToRawLongBits(value));
    }

    @Test
    void ceilFollowsJavaOnRandomInputs() {
        var random = new SplittableRandom(0x5eedc0de0004L);
        for (int i = 0; i < RANDOM_INPUTS; i++) checkCeil(edgeDoubleBits(random));
    }

    @Test
    void ceilKeepsTheSignOfZeroAndTheBitsOfANan() {
        assertEquals(0x8000000000000000L, bits(fp64Ceil(words(Double.doubleToRawLongBits(-0.5)))));
        assertEquals(0x8000000000000000L, bits(fp64Ceil(words(0x8000000000000001L))));
        assertEquals(0x3ff0000000000000L, bits(fp64Ceil(words(1L))));
        assertEquals(0x3ff0000000000000L, bits(fp64Ceil(words(Double.doubleToRawLongBits(0.5)))));
        for (long nan : new long[] {0x7ff8000000000000L, 0x7ff0000000000001L, 0xfff8000000000001L}) {
            assertEquals(nan, bits(fp64Ceil(words(nan))));
        }
    }

    @Test
    void theModelRefusesAShiftTheGlslLeavesUndefined() {
        // The transliteration only means something if it never relies on Java's masked shift counts.
        assertThrows(IllegalStateException.class, () -> shl(1, 32));
        assertThrows(IllegalStateException.class, () -> shr(1, -1));
        assertThrows(IllegalStateException.class, () -> findMsb(0));
    }

    // ---- the GLSL text -----------------------------------------------------------------------------------

    private static final Pattern WG_CALL = Pattern.compile("\\b(wg_[A-Za-z0-9_]+)\\s*\\(");
    private static final Pattern COMMENT = Pattern.compile("//[^\n]*");

    private static Set<String> calledHelpers(String source) {
        var names = new LinkedHashSet<String>();
        var matcher = WG_CALL.matcher(COMMENT.matcher(source).replaceAll(""));
        while (matcher.find()) names.add(matcher.group(1));
        return names;
    }

    @Test
    void sourceNamesNoFloatingTypeAndPassesTheNumericContract() {
        String source = FusedIeeeHelpers.source();
        assertFalse(Pattern.compile("(?s).*\\b(float|double|half|[df]?vec[234]|f16vec[234]|d?mat[234](x[234])?)\\b.*")
                .matcher(source).matches(), "floating type word in the helper text");
        assertFalse(Pattern.compile("(?<![A-Za-z0-9_])\\d+\\.\\d*|\\.\\d").matcher(source).find(), "floating literal");
        String code = COMMENT.matcher(source).replaceAll("");
        for (String banned : List.of("precise", "isnan", "isinf", "floor", "ceil", "sqrt", "fma", "frexp", "abs", "min", "max",
                "BitsToInt", "BitsToUint", "BitsToFloat", "BitsToDouble", "packDouble2x32", "unpackDouble2x32")) {
            assertFalse(Pattern.compile("\\b" + banned + "\\s*\\(").matcher(code).find(), "builtin call " + banned);
        }
        var inspection = new SpirvNumericContract().inspect(source + "\nuint index = gl_GlobalInvocationID.x;\n", NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
        assertFalse(source.contains("\r"), "LF line ends only, the text is part of hashed shaders");
    }

    @Test
    void everyHelperTheSourceCallsExistsInTheEmitterAndTheListIsExactlyThatSet() {
        String source = FusedIeeeHelpers.source();
        Set<String> called = calledHelpers(source);
        assertEquals(called, FusedIeeeHelpers.HELPERS, "HELPERS must be exactly the wg_ names the source calls");
        for (String name : called) {
            // helperSource fails closed on an unknown name.
            String closure = IntegerIeeeEmitter.helperSource(Set.of(name));
            assertTrue(closure.contains(name + "("), name);
        }
    }

    @Test
    void theClosureOfTheHelpersIsSelfContainedAndNeedsNoFailureFlag() {
        String closure = IntegerIeeeEmitter.helperSource(FusedIeeeHelpers.HELPERS);
        assertFalse(closure.contains("wg_failed"), "the fused helpers must not depend on the wg_failed global");
        assertFalse(closure.contains("wg_fp64_floor_to_i32"));
        // Every function the closure calls is declared in it.
        var declared = new LinkedHashSet<String>();
        var declaration = Pattern.compile("(?m)^(?:uint|int|bool|uvec2|uvec4)\\s+(wg_[A-Za-z0-9_]+)\\(").matcher(closure);
        while (declaration.find()) declared.add(declaration.group(1));
        for (String name : calledHelpers(closure)) assertTrue(declared.contains(name), "undeclared in closure: " + name);
        for (String name : FusedIeeeHelpers.HELPERS) assertTrue(declared.contains(name), "root missing from closure: " + name);
    }

    @Test
    void eachHelperIsDeclaredOnceWithTheCarrierTypesOfTheEmitter() {
        String source = FusedIeeeHelpers.source();
        for (String declaration : List.of("int wgf_fp64_to_i32_java(uvec2 value)", "uvec2 wgf_fp64_from_i64(uvec2 value)",
                "uint wgf_fp32_from_i64(uvec2 value)", "uvec2 wgf_fp64_ceil(uvec2 value)")) {
            int first = source.indexOf(declaration);
            assertTrue(first >= 0, declaration);
            assertEquals(-1, source.indexOf(declaration, first + 1), "declared twice: " + declaration);
        }
    }
}
