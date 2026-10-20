// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

/**
 * {@code FastIeeeHelpers.source()}, statement for statement, in Java. A GLSL {@code uint} is an {@code int} holding
 * the bits and a {@code uint64_t} is a {@code long}; every unsigned comparison, minimum and maximum and every
 * shift goes through the helpers below, and a shift by the type's width or more, which GLSL leaves undefined,
 * throws. The {@code wg_} helpers the fast paths fall back to are replaced by Java's own arithmetic, which they
 * match bit for bit (the device campaigns prove that); a NaN result is Java's NaN. A {@link Mutation} breaks
 * exactly one rounding step, so a test can show that the vectors would notice.
 */
final class FastIeeeModel {
    enum Mutation {
        NONE,
        /** binary64 add: the bits shifted out are dropped instead of folded into the lowest bit. */
        ADD64_NO_STICKY,
        /** binary64 add: rounding adds 4 (half up) instead of 3 + the lowest kept bit (half even). */
        ADD64_HALF_UP,
        /** binary64 mul: the low product word is not part of the tie decision. */
        MUL64_NO_LOW_WORD,
        /** binary64 mul: ties round up. */
        MUL64_TIE_UP,
        /** binary32 add: the bits shifted out are dropped. */
        ADD32_NO_STICKY,
        /** binary32 add: rounding adds 4. */
        ADD32_HALF_UP,
        /** binary32 mul: ties round up. */
        MUL32_TIE_UP
    }

    static final FastIeeeModel EXACT = new FastIeeeModel(Mutation.NONE);

    final Mutation mutation;
    long fast, fallback;
    private int carry;

    FastIeeeModel(Mutation mutation) { this.mutation = mutation; }

    // ---- GLSL semantics ---------------------------------------------------------------------------------

    static boolean uless(int a, int b) { return Integer.compareUnsigned(a, b) < 0; }
    static boolean ugeq(int a, int b) { return Integer.compareUnsigned(a, b) >= 0; }
    static boolean ugreater(int a, int b) { return Integer.compareUnsigned(a, b) > 0; }
    static int umax(int a, int b) { return uless(a, b) ? b : a; }
    static int umin(int a, int b) { return uless(a, b) ? a : b; }

    static int shl(int value, int amount) {
        if (amount < 0 || amount > 31) throw new IllegalStateException("32-bit shift out of GLSL range: " + amount);
        return value << amount;
    }

    static int shr(int value, int amount) {
        if (amount < 0 || amount > 31) throw new IllegalStateException("32-bit shift out of GLSL range: " + amount);
        return value >>> amount;
    }

    static long shl(long value, int amount) {
        if (amount < 0 || amount > 63) throw new IllegalStateException("64-bit shift out of GLSL range: " + amount);
        return value << amount;
    }

    static long shr(long value, int amount) {
        if (amount < 0 || amount > 63) throw new IllegalStateException("64-bit shift out of GLSL range: " + amount);
        return value >>> amount;
    }

    static int bitfieldExtract(int value, int offset, int bits) { return (value >>> offset) & ((1 << bits) - 1); }

    /** bitfieldInsert(base, insert, offset, bits). */
    static int bitfieldInsert(int base, int insert, int offset, int bits) {
        int mask = (bits == 32 ? -1 : ((1 << bits) - 1)) << offset;
        return (base & ~mask) | ((insert << offset) & mask);
    }

    static int findMsb(int value) { return value == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(value); }
    static int findLsb(int value) { return value == 0 ? -1 : Integer.numberOfTrailingZeros(value); }
    static long pack(int low, int high) { return (low & 0xffffffffL) | ((long) high << 32); }
    static int lo(long value) { return (int) value; }
    static int hi(long value) { return (int) (value >>> 32); }

    private int umulHigh, umulLow;

    private void umulExtended(int x, int y) {
        long product = (x & 0xffffffffL) * (y & 0xffffffffL);
        umulHigh = (int) (product >>> 32);
        umulLow = (int) product;
    }

    /** uaddCarry: the sum, and the carry in {@link #carry}. */
    private int uaddCarry(int x, int y) {
        int sum = x + y;
        carry = uless(sum, x) ? 1 : 0;
        return sum;
    }

    private long fallback64(long left, long right, boolean multiply) {
        fallback++;
        double a = Double.longBitsToDouble(left), b = Double.longBitsToDouble(right);
        return Double.doubleToRawLongBits(multiply ? a * b : a + b);
    }

    private int fallback32(int left, int right, boolean multiply) {
        fallback++;
        float a = Float.intBitsToFloat(left), b = Float.intBitsToFloat(right);
        return Float.floatToRawIntBits(multiply ? a * b : a + b);
    }

    // ---- binary64 ---------------------------------------------------------------------------------------

    long fp64Add(long left, long right) {
        int leftX = lo(left), leftY = hi(left), rightX = lo(right), rightY = hi(right);
        int exponentA = bitfieldExtract(leftY, 20, 11), exponentB = bitfieldExtract(rightY, 20, 11);
        if (ugeq(umax(exponentA - 1, exponentB - 1), 2046)) {
            boolean zeroA = (leftX | (leftY & 0x7fffffff)) == 0, zeroB = (rightX | (rightY & 0x7fffffff)) == 0;
            if (zeroA && zeroB) { fast++; return left & right; }
            if (zeroA && exponentB != 2047) { fast++; return right; }
            if (zeroB && exponentA != 2047) { fast++; return left; }
            return fallback64(left, right, false);
        }
        int magnitudeA = leftY & 0x7fffffff, magnitudeB = rightY & 0x7fffffff;
        boolean swap = uless(magnitudeA, magnitudeB) || (magnitudeA == magnitudeB && uless(leftX, rightX));
        long big = swap ? right : left, small = swap ? left : right;
        int bigX = lo(big), bigY = hi(big), smallX = lo(small), smallY = hi(small);
        int exponent = umax(exponentA, exponentB);
        int shift = umin(exponent - umin(exponentA, exponentB), 63);
        int smallHigh = bitfieldInsert(smallY, 1, 20, 12);
        long bigSignificand = pack(shl(bigX, 3), shl(bitfieldInsert(bigY, 1, 20, 12), 3) | shr(bigX, 29));
        long aligned = shr(pack(shl(smallX, 3), shl(smallHigh, 3) | shr(smallX, 29)), shift);
        int lowest = (smallX != 0 ? findLsb(smallX) : 32 + findLsb(smallHigh)) + 3;
        if (mutation != Mutation.ADD64_NO_STICKY) aligned |= shift > lowest ? 1L : 0L;
        long sum = (leftY ^ rightY) < 0 ? bigSignificand - aligned : bigSignificand + aligned;
        int wordsX = lo(sum), wordsY = hi(sum);
        if ((wordsX | wordsY) == 0) { fast++; return 0L; }
        int lead = wordsY != 0 ? 32 + findMsb(wordsY) : findMsb(wordsX);
        int biased = exponent + lead - 55;
        if (ugeq(biased - 1, 2046)) return fallback64(left, right, false);
        sum = shl(sum, Math.max(55 - lead, 0));
        int extra = lead > 55 ? 1 : 0;
        int increment = mutation == Mutation.ADD64_HALF_UP ? (extra != 0 ? 8 : 4)
                : (extra != 0 ? 7 : 3) + (shr(lo(sum), 3 + extra) & 1);
        sum += increment & 0xffffffffL;
        long result = shr(sum, 3 + extra);
        int resultY = hi(result) + shl(biased - 1, 20);
        resultY |= bigY & 0x80000000;
        fast++;
        return pack(lo(result), resultY);
    }

    long fp64Sub(long left, long right) { return fp64Add(left, right ^ 0x8000000000000000L); }

    long fp64Mul(long left, long right) {
        int leftX = lo(left), leftY = hi(left), rightX = lo(right), rightY = hi(right);
        int exponentA = bitfieldExtract(leftY, 20, 11), exponentB = bitfieldExtract(rightY, 20, 11);
        int sign = (leftY ^ rightY) & 0x80000000;
        if (ugeq(umax(exponentA - 1, exponentB - 1), 2046)) {
            boolean zeroA = (leftX | (leftY & 0x7fffffff)) == 0, zeroB = (rightX | (rightY & 0x7fffffff)) == 0;
            if ((zeroA && exponentB != 2047) || (zeroB && exponentA != 2047)) { fast++; return pack(0, sign); }
            return fallback64(left, right, true);
        }
        int highA = bitfieldInsert(leftY, 1, 20, 12), highB = bitfieldInsert(rightY, 1, 20, 12);
        umulExtended(leftX, rightX);
        int h00 = umulHigh, l00 = umulLow;
        umulExtended(leftX, highB);
        int h01 = umulHigh, l01 = umulLow;
        umulExtended(highA, rightX);
        int h10 = umulHigh, l10 = umulLow;
        umulExtended(highA, highB);
        int h11 = umulHigh, l11 = umulLow;
        // uaddCarry(uaddCarry(h00, l01, carryA), l10, carryB): the two carries, each from its own addition.
        int w1, carryA, carryB;
        {
            int inner = h00 + l01;
            carryA = uless(inner, h00) ? 1 : 0;
            int outer = inner + l10;
            carryB = uless(outer, inner) ? 1 : 0;
            w1 = outer;
        }
        int carryC, carryD, w2;
        {
            int inner = (h01 + h10) + l11;
            carryC = uless(inner, h01 + h10) ? 1 : 0;
            w2 = inner + (carryA + carryB);
            carryD = uless(w2, inner) ? 1 : 0;
        }
        int w3 = h11 + carryC + carryD;
        int top = shr(w3, 9);
        int biased = exponentA + exponentB + top - 1023;
        if (ugeq(biased - 1, 2046)) return fallback64(left, right, true);
        int lowShift = 20 + top, highShift = 12 - top;
        int keptLow = shr(w1, lowShift) | shl(w2, highShift);
        int keptHigh = shr(w2, lowShift) | shl(w3, highShift);
        int discardedHigh = w1 & (shl(1, lowShift) - 1), middle = shl(1, lowShift - 1);
        int tieFlag = mutation == Mutation.MUL64_NO_LOW_WORD ? (keptLow & 1) : (l00 | (keptLow & 1));
        int roundInput = discardedHigh + (tieFlag != 0 ? 1 : 0);
        boolean up = mutation == Mutation.MUL64_TIE_UP ? ugeq(discardedHigh, middle) : ugreater(roundInput, middle);
        int sumLow = uaddCarry(keptLow, up ? 1 : 0);
        int carryE = carry;
        keptHigh += carryE + shl(biased - 1, 20);
        fast++;
        return pack(sumLow, keptHigh | sign);
    }

    // ---- binary32 ---------------------------------------------------------------------------------------

    int fp32Add(int left, int right) {
        int exponentA = bitfieldExtract(left, 23, 8), exponentB = bitfieldExtract(right, 23, 8);
        if (ugeq(umax(exponentA - 1, exponentB - 1), 254)) {
            boolean zeroA = (left & 0x7fffffff) == 0, zeroB = (right & 0x7fffffff) == 0;
            if (zeroA && zeroB) { fast++; return left & right; }
            if (zeroA && exponentB != 255) { fast++; return right; }
            if (zeroB && exponentA != 255) { fast++; return left; }
            return fallback32(left, right, false);
        }
        boolean swap = uless(left & 0x7fffffff, right & 0x7fffffff);
        int big = swap ? right : left, small = swap ? left : right;
        int exponent = umax(exponentA, exponentB);
        int shift = umin(exponent - umin(exponentA, exponentB), 31);
        int bigSignificand = shl(bitfieldInsert(big, 1, 23, 9), 3);
        int smallSignificand = shl(bitfieldInsert(small, 1, 23, 9), 3);
        int aligned = shr(smallSignificand, shift);
        if (mutation != Mutation.ADD32_NO_STICKY) aligned |= shift > findLsb(smallSignificand) ? 1 : 0;
        int sum = (left ^ right) < 0 ? bigSignificand - aligned : bigSignificand + aligned;
        if (sum == 0) { fast++; return 0; }
        int lead = findMsb(sum);
        int biased = exponent + lead - 26;
        if (ugeq(biased - 1, 254)) return fallback32(left, right, false);
        sum = shl(sum, Math.max(26 - lead, 0));
        int extra = lead > 26 ? 1 : 0;
        sum += mutation == Mutation.ADD32_HALF_UP ? (extra != 0 ? 8 : 4) : (extra != 0 ? 7 : 3) + (shr(sum, 3 + extra) & 1);
        fast++;
        return (big & 0x80000000) | (shl(biased - 1, 23) + shr(sum, 3 + extra));
    }

    int fp32Sub(int left, int right) { return fp32Add(left, right ^ 0x80000000); }

    int fp32Mul(int left, int right) {
        int exponentA = bitfieldExtract(left, 23, 8), exponentB = bitfieldExtract(right, 23, 8);
        int sign = (left ^ right) & 0x80000000;
        if (ugeq(umax(exponentA - 1, exponentB - 1), 254)) {
            boolean zeroA = (left & 0x7fffffff) == 0, zeroB = (right & 0x7fffffff) == 0;
            if ((zeroA && exponentB != 255) || (zeroB && exponentA != 255)) { fast++; return sign; }
            return fallback32(left, right, true);
        }
        umulExtended(bitfieldInsert(left, 1, 23, 9), bitfieldInsert(right, 1, 23, 9));
        int high = umulHigh, low = umulLow;
        int top = shr(high, 15);
        int shift = 23 + top;
        int biased = exponentA + exponentB + top - 127;
        if (ugeq(biased - 1, 254)) return fallback32(left, right, true);
        int kept = shl(high, 32 - shift) | shr(low, shift);
        int discarded = low & (shl(1, shift) - 1);
        int roundBias = mutation == Mutation.MUL32_TIE_UP ? shl(1, shift - 1) : shl(1, shift - 1) - 1;
        kept += shr(discarded + roundBias + (mutation == Mutation.MUL32_TIE_UP ? 0 : (kept & 1)), shift);
        fast++;
        return sign | (shl(biased - 1, 23) + kept);
    }
}
