// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Operands for the fast integer-carrier helpers, as raw bits in the low bits of a {@code long}: a list of edge
 * values that every operation is run on pairwise, and seeded generators that weight what the fast paths can get
 * wrong (alignment distances, cancellation, rounding ties, results at the edge of the normal range, special
 * operands) next to raw random bits.
 */
final class FastIeeeVectors {
    /** A binary interchange format: {@code fraction} bits below the hidden one; exponent field {@code expMax} is inf/NaN. */
    record Format(int fraction, int expMax, int bias) {
        static final Format BINARY64 = new Format(52, 2047, 1023);
        static final Format BINARY32 = new Format(23, 255, 127);

        int precision() { return fraction + 1; }
        int width() { return fraction == 52 ? 64 : 32; }
        long fractionMask() { return (1L << fraction) - 1L; }
        long mask() { return width() == 64 ? -1L : 0xffffffffL; }
        long make(long sign, long exponent, long fractionBits) {
            return (sign << (width() - 1)) | (exponent << fraction) | (fractionBits & fractionMask());
        }
        long negate(long bits) { return bits ^ (1L << (width() - 1)); }
    }

    private FastIeeeVectors() {}

    /** Zeros, subnormals, the subnormal/normal boundary, ones and halves, powers of two, extremes, inf and NaNs, both signs. */
    static List<Long> listed(Format f) {
        var list = new ArrayList<Long>();
        long maxFraction = f.fractionMask();
        long[] exponents = {1, 2, 3, f.bias - f.fraction - 2, f.bias - f.fraction, f.bias - 2, f.bias - 1, f.bias, f.bias + 1,
                f.bias + 2, f.bias + f.fraction - 1, f.bias + f.fraction, f.bias + f.fraction + 1, f.expMax - 3, f.expMax - 2,
                f.expMax - 1};
        long[] fractions = {0, 1, 2, 3, maxFraction, maxFraction - 1, 1L << (f.fraction - 1), (1L << (f.fraction - 1)) - 1,
                (1L << (f.fraction - 1)) + 1, maxFraction >>> 1, 0x5555555555555555L & maxFraction, 0x2aaaaaaaaaaaaaaaL & maxFraction};
        for (int sign = 0; sign <= 1; sign++) {
            list.add(f.make(sign, 0, 0));
            list.add(f.make(sign, 0, 1));
            list.add(f.make(sign, 0, 2));
            list.add(f.make(sign, 0, maxFraction));
            list.add(f.make(sign, 0, maxFraction - 1));
            list.add(f.make(sign, 0, 1L << (f.fraction - 1)));
            list.add(f.make(sign, f.expMax, 0));
            list.add(f.make(sign, f.expMax, 1L << (f.fraction - 1)));
            list.add(f.make(sign, f.expMax, 1));
            list.add(f.make(sign, f.expMax, maxFraction));
            for (long exponent : exponents) for (long fraction : fractions) list.add(f.make(sign, exponent, fraction));
        }
        return list;
    }

    /** Ties for addition: an operand at 1 (either lowest bit) and a second at, just below or just above half its spacing. */
    static List<long[]> addTies(Format f) {
        var list = new ArrayList<long[]>();
        for (int sign = 0; sign <= 1; sign++) for (int otherSign = 0; otherSign <= 1; otherSign++) {
            for (int exponentShift = -1; exponentShift <= 2; exponentShift++) for (long lsb = 0; lsb <= 1; lsb++) {
                for (int tail = 0; tail <= 4; tail++) {
                    long a = f.make(sign, f.bias, lsb);
                    long b = f.make(otherSign, f.bias - f.precision() + exponentShift, tail == 0 ? 0 : tail == 1 ? 1 : tail == 2 ? 2 : tail == 3 ? f.fractionMask() : f.fractionMask() - 1);
                    list.add(new long[] {a, b});
                    list.add(new long[] {b, a});
                }
            }
        }
        return list;
    }

    /** A random normal significand fraction. */
    private static long fraction(Format f, SplittableRandom r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> f.fractionMask();
            case 2 -> r.nextLong() & f.fractionMask() & (f.fractionMask() << r.nextInt(f.fraction));
            case 3 -> (r.nextLong() & f.fractionMask()) >>> r.nextInt(f.fraction);
            case 4 -> (r.nextBoolean() ? 1L : f.fractionMask()) ^ (1L << r.nextInt(f.fraction));
            default -> r.nextLong() & f.fractionMask();
        };
    }

    /** One operand with weight on the special cases. */
    static long edge(Format f, SplittableRandom r) {
        long sign = r.nextBoolean() ? 1 : 0;
        return switch (r.nextInt(10)) {
            case 0 -> r.nextLong() & f.mask();
            case 1 -> f.make(sign, 0, fraction(f, r));                          // zero or subnormal
            case 2 -> f.make(sign, f.expMax, r.nextInt(3) == 0 ? 0 : fraction(f, r)); // inf, NaN
            case 3 -> f.make(sign, 1 + r.nextInt(3), fraction(f, r));          // just above subnormal
            case 4 -> f.make(sign, f.expMax - 1 - r.nextInt(3), fraction(f, r)); // just below infinity
            case 5 -> f.make(sign, f.bias + r.nextInt(7) - 3, fraction(f, r));
            default -> f.make(sign, 1 + r.nextInt(f.expMax - 1), fraction(f, r));
        };
    }

    /** A pair for addition or subtraction. */
    static long[] addPair(Format f, SplittableRandom r) {
        long a = edge(f, r);
        long exponent = (a >>> f.fraction) & f.expMax;
        long sign = r.nextBoolean() ? 1 : 0;
        switch (r.nextInt(9)) {
            case 0: return new long[] {a, edge(f, r)};
            case 1: return new long[] {a, r.nextLong() & f.mask()};
            case 2: { // same or neighbouring exponent: cancellation
                long e = Math.max(1, Math.min(f.expMax - 1, exponent + r.nextInt(5) - 2));
                return new long[] {a, f.make(sign, e, fraction(f, r))};
            }
            case 3: { // the negation, nudged by a few units
                return new long[] {a, f.negate(a) + r.nextInt(7) - 3};
            }
            case 4: { // exponent distance around the significand width: the sticky boundary
                long e = exponent - (f.precision() - 3 + r.nextInt(8));
                if (e < 1) e = 1 + r.nextInt(3);
                return new long[] {a, f.make(sign, e, fraction(f, r))};
            }
            case 5: { // a tie or one step off: the second sits near half a unit in the last place of the first
                long e = Math.max(1, exponent - f.precision() + r.nextInt(3));
                long b = f.make(sign, e, r.nextInt(3) == 0 ? fraction(f, r) : (r.nextBoolean() ? 0 : 1));
                return new long[] {a, b};
            }
            case 6: { // result near the bottom of the normal range
                long e = 1 + r.nextInt(3);
                return new long[] {f.make(sign, e, fraction(f, r)), f.make(sign ^ 1, e + r.nextInt(2), fraction(f, r))};
            }
            case 7: { // result near overflow
                long e = f.expMax - 1 - r.nextInt(2);
                return new long[] {f.make(sign, e, fraction(f, r)), f.make(sign, e - r.nextInt(3), fraction(f, r))};
            }
            default: { // far apart
                long e = Math.max(1, Math.min(f.expMax - 1, exponent - r.nextInt(2 * f.precision() + 8)));
                return new long[] {a, f.make(sign, e, fraction(f, r))};
            }
        }
    }

    /** A pair for multiplication. */
    static long[] mulPair(Format f, SplittableRandom r) {
        long a = edge(f, r);
        long sign = r.nextBoolean() ? 1 : 0;
        switch (r.nextInt(7)) {
            case 0: return new long[] {a, edge(f, r)};
            case 1: return new long[] {a, r.nextLong() & f.mask()};
            case 2: { // result exponent at the edge of the normal range
                long target = new long[] {-2, -1, 0, 1, 2, f.expMax - 3, f.expMax - 2, f.expMax - 1, f.expMax, f.expMax + 1}[r.nextInt(10)];
                long e1 = 1 + r.nextInt(f.expMax - 1);
                long e2 = target - e1 + f.bias;
                if (e2 < 1 || e2 > f.expMax - 1) return new long[] {a, edge(f, r)};
                return new long[] {f.make(sign, e1, fraction(f, r)), f.make(r.nextBoolean() ? 1 : 0, e2, fraction(f, r))};
            }
            case 3: { // exact ties: significands with a controlled number of trailing zeros
                int total = f.fraction - 2 + r.nextInt(5); // trailing zeros of the two significands sum to about the fraction width
                int t1 = Math.max(0, Math.min(f.fraction, r.nextInt(total + 1)));
                int t2 = Math.max(0, Math.min(f.fraction, total - t1));
                long m1 = ((r.nextLong() & f.fractionMask()) | 1L) << t1;
                long m2 = ((r.nextLong() & f.fractionMask()) | 1L) << t2;
                return new long[] {f.make(sign, f.bias + r.nextInt(7) - 3, m1 >>> 0), f.make(r.nextBoolean() ? 1 : 0, f.bias + r.nextInt(7) - 3, m2)};
            }
            case 4: { // powers of two and neighbours
                long fa = r.nextBoolean() ? 0 : (r.nextBoolean() ? 1 : f.fractionMask());
                long fb = r.nextBoolean() ? 0 : (r.nextBoolean() ? 1 : f.fractionMask());
                return new long[] {f.make(sign, 1 + r.nextInt(f.expMax - 1), fa), f.make(r.nextBoolean() ? 1 : 0, 1 + r.nextInt(f.expMax - 1), fb)};
            }
            case 5: { // exponents near the bias: products stay normal
                return new long[] {f.make(sign, f.bias + r.nextInt(21) - 10, fraction(f, r)), f.make(r.nextBoolean() ? 1 : 0, f.bias + r.nextInt(21) - 10, fraction(f, r))};
            }
            default: return new long[] {a, a};
        }
    }
}
