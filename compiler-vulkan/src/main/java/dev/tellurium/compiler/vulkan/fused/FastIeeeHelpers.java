// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.List;
import java.util.Set;

/**
 * Fast integer-carrier addition, subtraction and multiplication: {@code wgx_fp64_*} and {@code wgx_fp32_*} give
 * exactly the bits of the matching {@code wg_} helper of {@code IntegerIeeeEmitter} (IEEE 754 binary64 and
 * binary32, round to nearest even, subnormals kept) at a fraction of the cost, with the signatures and carriers
 * of those helpers ({@code uvec2(lo, hi)} for binary64, {@code uint} for binary32).
 *
 * <p>Each helper has a branch-light path for operands that are both normal and finite and a result that is
 * normal and finite. Everything else is handed to the {@code wg_} helper, which gives its own result: the only
 * cases answered here are the zeros that need no work (a zero with a finite operand), whose result the
 * {@code wg_} helper returns unchanged. The work is done in 32-bit words ({@code umulExtended},
 * {@code uaddCarry}, {@code bitfieldExtract}, {@code findMSB}) where that is cheaper, and in native 64-bit
 * integers for the binary64 alignment shift and sum. No floating type or operation appears.</p>
 *
 * <p>A shader that includes {@link #source()} must enable {@code GL_ARB_gpu_shader_int64} (the fused kernels
 * already do) and must declare the {@code wg_} helpers of {@link #HELPERS} first;
 * {@code IntegerIeeeEmitter.helperSource(HELPERS)} emits that closure.</p>
 */
public final class FastIeeeHelpers {
    /** The {@code wg_} helpers {@link #source()} calls as fallbacks. */
    public static final Set<String> HELPERS = Set.of("wg_fp64_add", "wg_fp64_mul", "wg_fp32_add", "wg_fp32_mul");

    /** The helpers, each with the signature of its {@code wg_} counterpart, in declaration order. */
    public static final List<String> NAMES = List.of(
            "wgx_fp64_add", "wgx_fp64_sub", "wgx_fp64_mul", "wgx_fp32_add", "wgx_fp32_sub", "wgx_fp32_mul");

    private FastIeeeHelpers() {}

    public static String source() {
        return """
                // Fast integer-carrier arithmetic (binary64 is uvec2(lo, hi), binary32 is uint). Each function returns
                // the bits of its wg_ counterpart and calls it for subnormal, infinite and NaN operands and for results
                // that are subnormal or overflow.

                // Both operands are normal and finite; the larger magnitude is the base. The other is shifted right by
                // the difference of the exponents (at most 63) with the bits shifted out folded into the lowest bit, which
                // sits below three extra bits, so no tie is invented or lost. The two are added or subtracted and the
                // leading bit is moved to bit 55: a shift left is exact, because cancelling more than one bit needs an
                // exponent difference of at most one, where nothing was shifted out. One addition of 3 + the lowest kept
                // bit then rounds to nearest even, a carry out of the significand raises the exponent through the carry
                // into the exponent field, and a sum whose leading bit is 56 is rounded one place higher.
                uvec2 wgx_fp64_add(uvec2 left, uvec2 right) {
                    uint exponentA = bitfieldExtract(left.y, 20, 11), exponentB = bitfieldExtract(right.y, 20, 11);
                    if (max(exponentA - 1u, exponentB - 1u) >= 2046u) {
                        bool zeroA = (left.x | (left.y & 0x7fffffffu)) == 0u, zeroB = (right.x | (right.y & 0x7fffffffu)) == 0u;
                        if (zeroA && zeroB) return left & right;
                        if (zeroA && exponentB != 2047u) return right;
                        if (zeroB && exponentA != 2047u) return left;
                        return wg_fp64_add(left, right);
                    }
                    uint magnitudeA = left.y & 0x7fffffffu, magnitudeB = right.y & 0x7fffffffu;
                    bool swap = magnitudeA < magnitudeB || (magnitudeA == magnitudeB && left.x < right.x);
                    uvec2 big = swap ? right : left, small = swap ? left : right;
                    uint exponent = max(exponentA, exponentB);
                    uint shift = min(exponent - min(exponentA, exponentB), 63u);
                    uint smallHigh = bitfieldInsert(small.y, 1u, 20, 12);
                    uint64_t bigSignificand = packUint2x32(uvec2(big.x << 3, (bitfieldInsert(big.y, 1u, 20, 12) << 3) | (big.x >> 29)));
                    uint64_t aligned = packUint2x32(uvec2(small.x << 3, (smallHigh << 3) | (small.x >> 29))) >> shift;
                    int lowest = (small.x != 0u ? findLSB(small.x) : 32 + findLSB(smallHigh)) + 3;
                    aligned |= int(shift) > lowest ? 1UL : 0UL;
                    uint64_t sum = int(left.y ^ right.y) < 0 ? bigSignificand - aligned : bigSignificand + aligned;
                    uvec2 words = unpackUint2x32(sum);
                    if ((words.x | words.y) == 0u) return uvec2(0u);
                    int lead = words.y != 0u ? 32 + findMSB(words.y) : findMSB(words.x);
                    int biased = int(exponent) + lead - 55;
                    if (uint(biased - 1) >= 2046u) return wg_fp64_add(left, right);
                    sum <<= uint(max(55 - lead, 0));
                    uint extra = lead > 55 ? 1u : 0u;
                    sum += uint64_t((extra != 0u ? 7u : 3u) + ((unpackUint2x32(sum).x >> (3u + extra)) & 1u));
                    uvec2 result = unpackUint2x32(sum >> (3u + extra));
                    result.y += uint(biased - 1) << 20;
                    result.y |= big.y & 0x80000000u;
                    return result;
                }
                uvec2 wgx_fp64_sub(uvec2 left, uvec2 right) { return wgx_fp64_add(left, uvec2(right.x, right.y ^ 0x80000000u)); }

                // The 106-bit product of the significands comes from four 32-bit products and their carries. Its leading
                // bit is bit 104 or 105, so the significand is the product shifted right by 52 or 53 and the discarded
                // bits decide the rounding: it rounds up when they exceed the midpoint, or equal it with an odd kept part.
                uvec2 wgx_fp64_mul(uvec2 left, uvec2 right) {
                    uint exponentA = bitfieldExtract(left.y, 20, 11), exponentB = bitfieldExtract(right.y, 20, 11);
                    uint sign = (left.y ^ right.y) & 0x80000000u;
                    if (max(exponentA - 1u, exponentB - 1u) >= 2046u) {
                        bool zeroA = (left.x | (left.y & 0x7fffffffu)) == 0u, zeroB = (right.x | (right.y & 0x7fffffffu)) == 0u;
                        if ((zeroA && exponentB != 2047u) || (zeroB && exponentA != 2047u)) return uvec2(0u, sign);
                        return wg_fp64_mul(left, right);
                    }
                    uint highA = bitfieldInsert(left.y, 1u, 20, 12), highB = bitfieldInsert(right.y, 1u, 20, 12);
                    uint h00, l00, h01, l01, h10, l10, h11, l11;
                    umulExtended(left.x, right.x, h00, l00);
                    umulExtended(left.x, highB, h01, l01);
                    umulExtended(highA, right.x, h10, l10);
                    umulExtended(highA, highB, h11, l11);
                    uint carryA, carryB, carryC, carryD;
                    uint w1 = uaddCarry(uaddCarry(h00, l01, carryA), l10, carryB);
                    uint w2 = uaddCarry(uaddCarry(h01 + h10, l11, carryC), carryA + carryB, carryD);
                    uint w3 = h11 + carryC + carryD;
                    uint top = w3 >> 9;
                    int biased = int(exponentA + exponentB + top) - 1023;
                    if (uint(biased - 1) >= 2046u) return wg_fp64_mul(left, right);
                    uint lowShift = 20u + top, highShift = 12u - top;
                    uint keptLow = (w1 >> lowShift) | (w2 << highShift);
                    uint keptHigh = (w2 >> lowShift) | (w3 << highShift);
                    uint discardedHigh = w1 & ((1u << lowShift) - 1u), middle = 1u << (lowShift - 1u);
                    uint carryE;
                    keptLow = uaddCarry(keptLow, (discardedHigh + uint((l00 | (keptLow & 1u)) != 0u)) > middle ? 1u : 0u, carryE);
                    keptHigh += carryE + (uint(biased - 1) << 20);
                    return uvec2(keptLow, keptHigh | sign);
                }

                // As the binary64 addition, in one 32-bit word.
                uint wgx_fp32_add(uint left, uint right) {
                    uint exponentA = bitfieldExtract(left, 23, 8), exponentB = bitfieldExtract(right, 23, 8);
                    if (max(exponentA - 1u, exponentB - 1u) >= 254u) {
                        bool zeroA = (left & 0x7fffffffu) == 0u, zeroB = (right & 0x7fffffffu) == 0u;
                        if (zeroA && zeroB) return left & right;
                        if (zeroA && exponentB != 255u) return right;
                        if (zeroB && exponentA != 255u) return left;
                        return wg_fp32_add(left, right);
                    }
                    bool swap = (left & 0x7fffffffu) < (right & 0x7fffffffu);
                    uint big = swap ? right : left, small = swap ? left : right;
                    uint exponent = max(exponentA, exponentB);
                    uint shift = min(exponent - min(exponentA, exponentB), 31u);
                    uint bigSignificand = bitfieldInsert(big, 1u, 23, 9) << 3;
                    uint smallSignificand = bitfieldInsert(small, 1u, 23, 9) << 3;
                    uint aligned = (smallSignificand >> shift) | (int(shift) > findLSB(smallSignificand) ? 1u : 0u);
                    uint sum = int(left ^ right) < 0 ? bigSignificand - aligned : bigSignificand + aligned;
                    if (sum == 0u) return 0u;
                    int lead = findMSB(sum);
                    int biased = int(exponent) + lead - 26;
                    if (uint(biased - 1) >= 254u) return wg_fp32_add(left, right);
                    sum <<= uint(max(26 - lead, 0));
                    uint extra = lead > 26 ? 1u : 0u;
                    sum += (extra != 0u ? 7u : 3u) + ((sum >> (3u + extra)) & 1u);
                    return (big & 0x80000000u) | ((uint(biased - 1) << 23) + (sum >> (3u + extra)));
                }
                uint wgx_fp32_sub(uint left, uint right) { return wgx_fp32_add(left, right ^ 0x80000000u); }

                // As the binary64 product, in two 32-bit words.
                uint wgx_fp32_mul(uint left, uint right) {
                    uint exponentA = bitfieldExtract(left, 23, 8), exponentB = bitfieldExtract(right, 23, 8);
                    uint sign = (left ^ right) & 0x80000000u;
                    if (max(exponentA - 1u, exponentB - 1u) >= 254u) {
                        bool zeroA = (left & 0x7fffffffu) == 0u, zeroB = (right & 0x7fffffffu) == 0u;
                        if ((zeroA && exponentB != 255u) || (zeroB && exponentA != 255u)) return sign;
                        return wg_fp32_mul(left, right);
                    }
                    uint high, low;
                    umulExtended(bitfieldInsert(left, 1u, 23, 9), bitfieldInsert(right, 1u, 23, 9), high, low);
                    uint top = high >> 15;
                    uint shift = 23u + top;
                    int biased = int(exponentA + exponentB + top) - 127;
                    if (uint(biased - 1) >= 254u) return wg_fp32_mul(left, right);
                    uint kept = (high << (32u - shift)) | (low >> shift);
                    uint discarded = low & ((1u << shift) - 1u);
                    kept += (discarded + ((1u << (shift - 1u)) - 1u) + (kept & 1u)) >> shift;
                    return sign | ((uint(biased - 1) << 23) + kept);
                }
                """;
    }
}
