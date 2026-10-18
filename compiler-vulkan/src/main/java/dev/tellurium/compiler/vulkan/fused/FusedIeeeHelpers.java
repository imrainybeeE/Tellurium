// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.util.Set;

/**
 * Conversions and rounding that the fused kernels use and that
 * {@code IntegerIeeeEmitter} does not provide. They live here, not in
 * {@code IntegerIeeeEmitter.source()}, because that text is embedded whole in other hashed shaders and
 * appending to it would change their identity.
 *
 * <p>Everything is written with the existing {@code wg_} primitives and 32-bit integer operations on the
 * same carriers: {@code uvec2(lo, hi)} for binary64 and int64, {@code uint} for binary32. The text declares
 * no floating type. The helpers follow the Java semantics of the operation they stand for, not the
 * implementation-defined GLSL conversion.</p>
 *
 * <p>A shader that includes {@link #source()} must declare the {@code wg_} helpers in {@link #HELPERS}
 * first; {@code IntegerIeeeEmitter.helperSource(HELPERS)} emits that closure.</p>
 */
public final class FusedIeeeHelpers {
    /** The {@code wg_} helpers {@link #source()} calls directly. */
    public static final Set<String> HELPERS = Set.of(
            "wg_fp64_nan", "wg_fp64_exp", "wg_fp64_sign", "wg_fp64_frac", "wg_fp64_negate", "wg_fp64_floor",
            "wg_fp64_pack", "wg_fp32_pack", "wg_i64_negate", "wg_u64_nonzero", "wg_u64_shl", "wg_u64_shr",
            "wg_u64_shr_sticky");

    private FusedIeeeHelpers() {}

    public static String source() {
        return """
                // Fused-kernel conversions with Java semantics (binary64 and int64 are uvec2(lo, hi)).

                // (int) of a binary64 value: NaN gives 0, the result saturates, the fraction is truncated.
                int wgf_fp64_to_i32_java(uvec2 value) {
                    if (wg_fp64_nan(value)) return 0;
                    uint exponent = wg_fp64_exp(value);
                    if (exponent < 1023u) return 0;
                    uint unbiased = exponent - 1023u;
                    if (unbiased >= 31u) return wg_fp64_sign(value) != 0u ? -2147483647 - 1 : 2147483647;
                    uvec2 significand = wg_fp64_frac(value);
                    significand.y |= 0x00100000u;
                    uint magnitude = wg_u64_shr(significand, 52u - unbiased).x;
                    return wg_fp64_sign(value) != 0u ? -int(magnitude) : int(magnitude);
                }

                // (binary64) of an int64: one rounding to nearest even. The magnitude is aligned so its
                // leading bit is bit 55 (three rounding bits below the 53-bit significand) and packed.
                uvec2 wgf_fp64_from_i64(uvec2 value) {
                    if (!wg_u64_nonzero(value)) return uvec2(0u);
                    uint sign = value.y >> 31;
                    uvec2 magnitude = sign != 0u ? wg_i64_negate(value) : value;
                    int leading = magnitude.y != 0u ? 32 + findMSB(magnitude.y) : findMSB(magnitude.x);
                    uvec2 extended = leading <= 55
                            ? wg_u64_shl(magnitude, uint(55 - leading))
                            : wg_u64_shr_sticky(magnitude, uint(leading - 55));
                    return wg_fp64_pack(sign, 1023 + leading, extended);
                }

                // (binary32) of an int64: one rounding to nearest even, taken straight from the integer.
                // Narrowing the binary64 conversion would round twice.
                uint wgf_fp32_from_i64(uvec2 value) {
                    if (!wg_u64_nonzero(value)) return 0u;
                    uint sign = value.y >> 31;
                    uvec2 magnitude = sign != 0u ? wg_i64_negate(value) : value;
                    int leading = magnitude.y != 0u ? 32 + findMSB(magnitude.y) : findMSB(magnitude.x);
                    uvec2 extended = leading <= 26
                            ? wg_u64_shl(magnitude, uint(26 - leading))
                            : wg_u64_shr_sticky(magnitude, uint(leading - 26));
                    return wg_fp32_pack(sign, 127 + leading, extended.x);
                }

                // Math.ceil: the floor of the negation, negated. Signed zero and NaN bits follow wg_fp64_floor.
                uvec2 wgf_fp64_ceil(uvec2 value) {
                    return wg_fp64_negate(wg_fp64_floor(wg_fp64_negate(value)));
                }
                """;
    }
}
