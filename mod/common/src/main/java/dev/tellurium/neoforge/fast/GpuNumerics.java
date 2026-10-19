// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.tellurium.semantic.program.NumericProfile;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * What the engine decides from a GPU's numeric profile, apart from the game: which kernel dialect to compile,
 * how a tested list is read, and how the arithmetic is named for the log and the status report.
 */
final class GpuNumerics {
    private GpuNumerics() {}

    /** The dialect whose kernels run with a numeric profile. */
    static FusedNoiseCompiler.Dialect dialectFor(NumericProfile profile) {
        for (FusedNoiseCompiler.Dialect dialect : FusedNoiseCompiler.Dialect.values()) {
            if (dialect.profile() == profile) return dialect;
        }
        throw new IllegalStateException("No kernel dialect for the numeric profile " + profile);
    }

    /**
     * Reads one tested list.  A list holds for the Minecraft version it was produced on and no other: the original
     * generator's code differs between versions in ways the kernel fingerprints do not show.  A list that names a
     * profile ("profile=GPU_IEEE_BITS") holds for that profile only; the emulated profile's list must name it, so
     * a list made for the GPU's own fp64 can never admit the integer kernels, nor the other way round.
     */
    static Map<String, String> parseQualified(InputStream in, String minecraft, NumericProfile profile) throws IOException {
        var properties = new Properties();
        properties.load(in);
        if (!minecraft.equals(properties.remove("minecraft"))) return Map.of();
        Object declared = properties.remove("profile");
        if (declared == null ? profile != NumericProfile.NATIVE_QUALIFIED : !profile.name().equals(declared)) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String key : properties.stringPropertyNames()) out.put(key, properties.getProperty(key));
        return Map.copyOf(out);
    }

    /**
     * How the GPU does arithmetic, in words for the status report.
     *
     * @param numeric the value of -Dtellurium.fast.numeric (null when unset); "ieee" is the one setting that makes
     *                a GPU with fp64 run the emulated kernels, so the explanation differs
     */
    static String describeArithmetic(NumericProfile profile, String numeric) {
        return switch (profile) {
            case NATIVE_QUALIFIED -> "native fp64";
            case GPU_IEEE_BITS -> numeric != null && numeric.trim().equalsIgnoreCase("ieee")
                    ? "emulated in integers, as set by tellurium.fast.numeric=ieee"
                    : "emulated in integers, no fp64 on this GPU";
            default -> profile.name();
        };
    }

    /**
     * Why a generator the compiler refused stays on vanilla code, for the log and the status report.  The emulated
     * arithmetic has its own limits (it may not cover everything the native kernels do), so it is named.
     */
    static String refusalReason(NumericProfile profile, String compilerReason) {
        return profile == NumericProfile.NATIVE_QUALIFIED ? compilerReason
                : "the kernels with arithmetic emulated in integers do not support it: " + compilerReason;
    }
}
