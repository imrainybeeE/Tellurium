// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Dialect;
import dev.tellurium.runtime.vulkan.fused.FusedNoiseDevice.Candidate;
import dev.tellurium.runtime.vulkan.fused.FusedNoiseDevice.NumericMode;
import dev.tellurium.runtime.vulkan.fused.FusedNoiseDevice.Selection;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Device selection and the numeric profile are pure decisions; none of this needs a GPU. */
class FusedNoiseDeviceProfileTest {
    private static final int DISCRETE = 3, INTEGRATED = 2, CPU = 0;

    private static Candidate gpu(int score, boolean fp64) { return new Candidate(true, fp64, true, score); }

    /** The selection before the emulated dialect existed: shaderFloat64 and shaderInt64 required, first of the best score. */
    private static int legacyChoice(List<Candidate> candidates) {
        int best = -1, bestScore = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (!c.shaderFloat64() || !c.shaderInt64() || !c.hasComputeQueue()) continue;
            if (c.score() > bestScore) { best = i; bestScore = c.score(); }
        }
        return bestScore <= 0 ? -1 : best;
    }

    @Test void fp64GpuGetsTheNativeProfileInAutoMode() {
        assertEquals(new Selection(0, NumericProfile.NATIVE_QUALIFIED), FusedNoiseDevice.select(List.of(gpu(DISCRETE, true)), NumericMode.AUTO));
    }

    @Test void gpuWithoutFp64GetsTheEmulatedProfileInAutoMode() {
        assertEquals(new Selection(0, NumericProfile.GPU_IEEE_BITS), FusedNoiseDevice.select(List.of(gpu(INTEGRATED, false)), NumericMode.AUTO));
    }

    @Test void ieeeModeForcesTheEmulatedProfileOnAnFp64Gpu() {
        assertEquals(new Selection(0, NumericProfile.GPU_IEEE_BITS), FusedNoiseDevice.select(List.of(gpu(DISCRETE, true)), NumericMode.IEEE));
    }

    @Test void nativeModeNeedsFp64EvenWhenAFasterGpuLacksIt() {
        var candidates = List.of(gpu(DISCRETE, false), gpu(INTEGRATED, true));
        assertEquals(new Selection(1, NumericProfile.NATIVE_QUALIFIED), FusedNoiseDevice.select(candidates, NumericMode.NATIVE));
        var error = assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.select(List.of(gpu(DISCRETE, false)), NumericMode.NATIVE));
        assertEquals("No Vulkan GPU with shaderFloat64 and shaderInt64", error.getMessage());
    }

    @Test void rankingFollowsTheDeviceTypeBeforeFp64() {
        // A discrete GPU without fp64 beats an integrated one with it; the profile then follows the chosen device.
        assertEquals(new Selection(0, NumericProfile.GPU_IEEE_BITS),
                FusedNoiseDevice.select(List.of(gpu(DISCRETE, false), gpu(INTEGRATED, true)), NumericMode.AUTO));
    }

    @Test void aTieGoesToTheDeviceWithFp64ElseTheFirst() {
        assertEquals(1, FusedNoiseDevice.select(List.of(gpu(DISCRETE, false), gpu(DISCRETE, true)), NumericMode.AUTO).index());
        assertEquals(0, FusedNoiseDevice.select(List.of(gpu(DISCRETE, true), gpu(DISCRETE, true)), NumericMode.AUTO).index());
        assertEquals(0, FusedNoiseDevice.select(List.of(gpu(INTEGRATED, false), gpu(INTEGRATED, false)), NumericMode.AUTO).index());
        assertEquals(1, FusedNoiseDevice.select(List.of(gpu(INTEGRATED, false), gpu(DISCRETE, false)), NumericMode.AUTO).index());
    }

    @Test void everyRequirementFailsClosedWithTheShaderInt64Message() {
        var noInt64 = new Candidate(false, true, true, DISCRETE);
        var noCompute = new Candidate(true, true, false, DISCRETE);
        var cpuDriver = gpu(CPU, true);
        for (NumericMode mode : new NumericMode[]{NumericMode.AUTO, NumericMode.IEEE}) {
            for (Candidate candidate : List.of(noInt64, noCompute, cpuDriver)) {
                var error = assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.select(List.of(candidate), mode), mode + " " + candidate);
                assertEquals("No Vulkan GPU with shaderInt64", error.getMessage());
            }
            assertEquals("No Vulkan GPU with shaderInt64",
                    assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.select(List.of(), mode)).getMessage());
        }
        // A rejected device does not hide a usable one behind it.
        assertEquals(1, FusedNoiseDevice.select(List.of(noInt64, gpu(INTEGRATED, false)), NumericMode.AUTO).index());
    }

    @Test void nativeAndAutoSelectionOnFp64DevicesIsWhatItWasBeforeTheEmulatedDialect() {
        Random random = new Random(0x7e11);
        for (int round = 0; round < 20_000; round++) {
            int n = random.nextInt(4);
            List<Candidate> candidates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                candidates.add(new Candidate(random.nextInt(8) != 0, true, random.nextInt(8) != 0, new int[]{CPU, INTEGRATED, DISCRETE}[random.nextInt(3)]));
            }
            int expected = legacyChoice(candidates);
            for (NumericMode mode : new NumericMode[]{NumericMode.NATIVE, NumericMode.AUTO}) {
                if (expected < 0) {
                    assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.select(candidates, mode));
                } else {
                    // Every device here has fp64: the choice and the (native) profile are unchanged.
                    assertEquals(new Selection(expected, NumericProfile.NATIVE_QUALIFIED), FusedNoiseDevice.select(candidates, mode), candidates.toString());
                }
            }
        }
    }

    @Test void nativeModeIgnoresDevicesWithoutFp64LikeTheOldSelection() {
        Random random = new Random(0x51ce);
        for (int round = 0; round < 20_000; round++) {
            List<Candidate> candidates = new ArrayList<>();
            for (int i = random.nextInt(5); i > 0; i--) {
                candidates.add(new Candidate(random.nextInt(8) != 0, random.nextBoolean(), random.nextInt(8) != 0, new int[]{CPU, INTEGRATED, DISCRETE}[random.nextInt(3)]));
            }
            int expected = legacyChoice(candidates);
            if (expected < 0) assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.select(candidates, NumericMode.NATIVE));
            else assertEquals(new Selection(expected, NumericProfile.NATIVE_QUALIFIED), FusedNoiseDevice.select(candidates, NumericMode.NATIVE));
        }
    }

    @Test void theNumericPropertyHasThreeValuesAndRejectsGuesses() {
        assertEquals(NumericMode.AUTO, NumericMode.parse(null));
        assertEquals(NumericMode.AUTO, NumericMode.parse("  "));
        assertEquals(NumericMode.AUTO, NumericMode.parse("auto"));
        assertEquals(NumericMode.NATIVE, NumericMode.parse(" Native "));
        assertEquals(NumericMode.IEEE, NumericMode.parse("IEEE"));
        var error = assertThrows(IllegalStateException.class, () -> NumericMode.parse("fp64"));
        assertTrue(error.getMessage().contains("tellurium.fast.numeric") && error.getMessage().contains("'fp64'"), error.getMessage());
    }

    @Test void aProgramOfTheOtherDialectIsRefused() {
        FusedNoiseDevice.requireDialect(NumericProfile.NATIVE_QUALIFIED, Dialect.NATIVE_FP64);
        FusedNoiseDevice.requireDialect(NumericProfile.GPU_IEEE_BITS, Dialect.IEEE_BITS);
        var onIeee = assertThrows(IllegalArgumentException.class, () -> FusedNoiseDevice.requireDialect(NumericProfile.GPU_IEEE_BITS, Dialect.NATIVE_FP64));
        assertTrue(onIeee.getMessage().contains("NATIVE_FP64") && onIeee.getMessage().contains("GPU_IEEE_BITS"), onIeee.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FusedNoiseDevice.requireDialect(NumericProfile.NATIVE_QUALIFIED, Dialect.IEEE_BITS));
    }

    @Test void pipelineCachesOfTheTwoProfilesNeverShareAFile() {
        String fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef";
        // The native name is the one earlier builds wrote: existing caches stay valid.
        assertEquals("fused-0123456789abcdef0123456789abcdef.vkcache", FusedNoiseDevice.cacheFileName(NumericProfile.NATIVE_QUALIFIED, fingerprint));
        assertEquals("fused-ieee-0123456789abcdef0123456789abcdef.vkcache", FusedNoiseDevice.cacheFileName(NumericProfile.GPU_IEEE_BITS, fingerprint));
    }

    // ---- the SPIR-V gate: a hand-made module is enough, the check reads the instruction stream only

    private static byte[] module(int... instructionWords) {
        ByteBuffer out = ByteBuffer.allocate((5 + instructionWords.length) * 4).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x07230203).putInt(0x00010500).putInt(0).putInt(8).putInt(0);
        for (int word : instructionWords) out.putInt(word);
        return out.array();
    }

    private static final int OP_TYPE_INT = (4 << 16) | 21, OP_TYPE_FLOAT = (3 << 16) | 22;

    @Test void aModuleWithoutFloatTypesPassesTheEmulatedGate() {
        FusedNoiseDevice.requireNoFloatTypes(module(OP_TYPE_INT, 1, 32, 0), "K_BLOCK");
    }

    @Test void anyFloatTypeFailsTheEmulatedGateAndNamesTheKernel() {
        for (int width : new int[]{16, 32, 64}) {
            var error = assertThrows(IllegalStateException.class,
                    () -> FusedNoiseDevice.requireNoFloatTypes(module(OP_TYPE_INT, 1, 32, 0, OP_TYPE_FLOAT, 2, width), "K_XZ"));
            assertTrue(error.getMessage().contains("K_XZ") && error.getMessage().contains("OpTypeFloat"), error.getMessage());
        }
    }

    @Test void aMalformedModuleFailsTheEmulatedGate() {
        assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.requireNoFloatTypes(new byte[0], "K_XZ"));
        assertThrows(IllegalStateException.class, () -> FusedNoiseDevice.requireNoFloatTypes(module((9 << 16) | 21, 1), "K_XZ"));
    }
}
