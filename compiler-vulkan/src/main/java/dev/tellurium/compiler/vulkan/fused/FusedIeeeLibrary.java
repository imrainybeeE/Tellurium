// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The integer-carrier port of {@code fused_lib.glsl}: the same functions with {@code uvec2} for binary64 and
 * {@code uint} for binary32, written with the {@code wg_} helpers of {@code IntegerIeeeEmitter} and the
 * {@code wgf_} helpers of {@link FusedIeeeHelpers}.
 *
 * <p>A shader that includes {@link #source()} declares, in this order, {@code IntegerIeeeEmitter.helperSource(HELPER_ROOTS)},
 * {@link FusedIeeeHelpers#source()}, then the library. It also declares what the native library expects the
 * kernel header to provide: the {@code flags} buffer, the {@code perm} buffer ({@code uint[]}), the
 * {@code dtab} buffer ({@code uvec2[]}) and {@code SPLINE_COORDS}.</p>
 */
public final class FusedIeeeLibrary {
    /**
     * The {@code wg_} helpers the library calls directly, plus {@link FusedIeeeHelpers#HELPERS}: the roots of
     * the {@code helperSource} closure the library needs. A kernel that calls further helpers adds its own.
     * {@code FusedIeeeLibraryTextTest} derives this set from the library text, so it is neither short nor long.
     */
    public static final Set<String> HELPER_ROOTS;

    static {
        var roots = new LinkedHashSet<>(Set.of(
                "wg_fp32_add", "wg_fp32_equal", "wg_fp32_from_int", "wg_fp32_inf", "wg_fp32_less",
                "wg_fp32_less_equal", "wg_fp32_mul", "wg_fp32_nan", "wg_fp32_negate", "wg_fp32_sub",
                "wg_fp64_abs", "wg_fp64_add", "wg_fp64_div", "wg_fp64_equal", "wg_fp64_exp", "wg_fp64_floor",
                "wg_fp64_from_fp32", "wg_fp64_from_int", "wg_fp64_inf", "wg_fp64_lerp", "wg_fp64_less",
                "wg_fp64_less_equal", "wg_fp64_mul", "wg_fp64_nan", "wg_fp64_negate", "wg_fp64_sqrt",
                "wg_fp64_sub", "wg_fp64_to_fp32", "wg_fp64_zero"));
        roots.addAll(FusedIeeeHelpers.HELPERS);
        HELPER_ROOTS = Set.copyOf(roots);
    }

    private FusedIeeeLibrary() {}

    public static String source() {
        try (InputStream in = FusedIeeeLibrary.class.getResourceAsStream("fused_lib_ieee.glsl")) {
            if (in == null) throw new IllegalStateException("fused_lib_ieee.glsl resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read fused_lib_ieee.glsl", failure);
        }
    }
}
