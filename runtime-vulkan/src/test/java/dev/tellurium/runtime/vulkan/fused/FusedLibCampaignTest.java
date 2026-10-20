// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The campaign is only as good as its inputs and its verdicts, and neither runs on a device in the ordinary test
 * task, so what can be checked on the host is checked here: every function is covered, the inputs reach the bail
 * and edge paths they are meant to reach, the reference never fails on them, the tables are well formed, and the
 * comparison rule is the one the report claims.
 */
class FusedLibCampaignTest {
    static FusedLibCampaign.Op operation(String name) {
        return FusedLibCampaign.operations().stream().filter(op -> op.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no operation " + name));
    }

    private static long[][] sample(FusedLibCampaign.Op op, int randomCount, long seed) {
        var random = new SplittableRandom(seed);
        var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
        long[][] result = new long[op.fixed().length + randomCount][];
        System.arraycopy(op.fixed(), 0, result, 0, op.fixed().length);
        for (int i = op.fixed().length; i < result.length; i++) result[i] = op.gen().next(random, tables);
        return result;
    }

    @Test
    void everyLibraryFunctionThatCanBeRunAloneHasAnOperation() {
        var names = new ArrayList<String>();
        for (var op : FusedLibCampaign.operations()) names.add(op.name());
        assertEquals(names.size(), new HashSet<>(names).size(), "operation names are unique");
        // the library's functions: the ones with no operation are plain accessors reached through another operation
        Set<String> expected = Set.of("jd2i", "mfloor", "jmin", "jmax", "jminf", "jmaxf", "nextUpPos", "nextDownPos", "safeMagnitude",
                "jdiv", "jsqrt", "jdivf", "jsqrtf", "jremf", "lerpd", "lerpf", "clampedLerp", "inverseLerp", "clampedMap", "mapd", "mclamp",
                "smoothstepd", "wrapd", "gradDot", "simplexCorner", "bail", "jrem", "jfloorDiv", "jfloorMod", "positionSeed", "mixStafford",
                "xoroNext", "rngNextInt", "rngNextFloat", "improvedNoise", "perlinValue", "normalNoise", "legacyLevel", "blendedNoise",
                "simplex2D", "endIsland", "splineEval");
        assertEquals(expected, new HashSet<>(names));
    }

    @Test
    void casesNeverNeedMoreWordsThanTheInputRecordHolds() {
        for (var op : FusedLibCampaign.operations()) {
            for (long[] c : sample(op, 200, 3)) {
                assertTrue(c.length * 2 <= FusedLibCampaign.INPUT_WORDS, op.name() + " has " + c.length + " operands");
                assertTrue(c.length >= 1, op.name());
            }
            assertTrue(op.chunk() > 0 && op.count() > 0, op.name());
        }
    }

    @Test
    void theReferenceAnswersEveryGeneratedCaseWithAValueOfTheDeclaredShape() {
        for (var op : FusedLibCampaign.operations()) {
            var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
            var lib = new FusedLibReference(tables.perm, tables.dtab);
            lib.correctlyRoundedZero = true;
            int count = op.chunk() <= 64 ? 6 : 400;
            for (long[] c : sample(op, count, 4)) {
                lib.clearBails();
                long value = op.eval().eval(lib, c);
                switch (op.kind()) {
                    case BITS32 -> assertEquals(value & 0xffffffffL, value, op.name() + " binary32 result has bits above 32");
                    case BOOL -> assertTrue(value == 0L || value == 1L, op.name());
                    default -> { }
                }
                assertEquals(0, lib.bailCode & ~63, op.name());
            }
        }
    }

    @Test
    void theInputsReachBothSidesOfEveryBailGuard() {
        // (operation, expected bail bit) pairs: each must bail on some inputs and run clean on others
        Object[][] guarded = {{"jdiv", 1}, {"jsqrt", 2}, {"jdivf", 32}, {"jremf", 32}, {"inverseLerp", 1}, {"clampedMap", 1}, {"mapd", 1},
                {"splineEval", 16}, {"splineEval", 32}};
        for (Object[] entry : guarded) {
            var op = operation((String) entry[0]);
            int bit = (Integer) entry[1];
            var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
            var lib = new FusedLibReference(tables.perm, tables.dtab);
            lib.correctlyRoundedZero = true;
            int with = 0, without = 0;
            for (long[] c : sample(op, 20_000, 5)) {
                lib.clearBails();
                op.eval().eval(lib, c);
                if ((lib.bailCode & bit) != 0) with++;
                else without++;
            }
            assertTrue(with >= 20, op.name() + " bail " + bit + ": " + with);
            assertTrue(without >= 20, op.name() + " clean for " + bit + ": " + without);
        }
    }

    @Test
    void theDivisionInputsReachTheUnderflowToANegativeZero() {
        var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
        var lib = new FusedLibReference(tables.perm, tables.dtab);
        lib.correctlyRoundedZero = true;
        for (long[] c : sample(operation("jdiv"), 5000, 6)) lib.jdiv(Double.longBitsToDouble(c[0]), Double.longBitsToDouble(c[1]));
        assertTrue(lib.zeroSignDeviations > 20, "negative-zero quotients: " + lib.zeroSignDeviations);
    }

    @Test
    void theSafeMagnitudeInputsStraddleTheBoundary() {
        int safe = 0, unsafe = 0, exactlyAtTheEdge = 0;
        for (long[] c : sample(operation("safeMagnitude"), 20_000, 8)) {
            double v = Double.longBitsToDouble(c[0]);
            if (FusedLibReference.safeMagnitude(v)) safe++; else unsafe++;
            long exponent = (c[0] >>> 52) & 0x7ff;
            if (exponent == 1023 + 898 || exponent == 1023 + 899 || exponent == 1023 - 900 || exponent == 1023 - 901) exactlyAtTheEdge++;
        }
        assertTrue(safe > 1000 && unsafe > 1000 && exactlyAtTheEdge > 500, safe + " " + unsafe + " " + exactlyAtTheEdge);
        // the boundary itself: frexp exponent 899 is safe, 900 is not, and the same below
        assertTrue(FusedLibReference.safeMagnitude(Math.scalb(1.0, 898)));
        assertFalse(FusedLibReference.safeMagnitude(Math.scalb(1.0, 899)));
        assertTrue(FusedLibReference.safeMagnitude(Math.scalb(1.0, -900)));
        assertFalse(FusedLibReference.safeMagnitude(Math.scalb(1.0, -901)));
    }

    @Test
    void tablesAreWellFormedAndTheSplineChainsStraddleTheStackLimit() {
        var tables = FusedLibCampaign.Tables.build(new SplittableRandom(7));
        for (int copy = 0; copy < 2; copy++) {
            boolean[] seen = new boolean[256];
            for (int i = 0; i < 256; i++) seen[tables.perm[copy * 256 + i]] = true;
            for (boolean value : seen) assertTrue(value, "permutation " + copy);
        }
        for (int index : tables.perlin) {
            int levels = (int) tables.dtab[index];
            assertTrue(levels >= 1 && index + 1 + levels * FusedLibReference.PERLIN_STRIDE <= tables.dtab.length);
        }
        for (int index : tables.blended) {
            for (int slot = 5; slot <= 7; slot++) {
                int referenced = (int) tables.dtab[index + slot];
                int levels = (int) tables.dtab[referenced];
                assertTrue(levels >= 1 && referenced + 1 + levels * FusedLibReference.PERLIN_STRIDE <= index, "a Perlin record before the blended record");
            }
        }
        var lib = new FusedLibReference(tables.perm, tables.dtab);
        int roots = tables.splines.length;
        float[] cs = {0.25f, 0.25f, 0.25f};
        lib.splineEval(tables.splines[roots - 2], cs);
        assertEquals(0, lib.bailCode, "a chain of 15 records fits the stack");
        lib.splineEval(tables.splines[roots - 1], cs);
        assertEquals(FusedLibReference.BAIL_RANGE, lib.bailCode, "a chain of 17 records does not");
    }

    @Test
    void casesAndTablesAreDeterministic() {
        var one = FusedLibCampaign.Tables.build(new SplittableRandom(1));
        var two = FusedLibCampaign.Tables.build(new SplittableRandom(1));
        assertTrue(java.util.Arrays.equals(one.perm, two.perm) && java.util.Arrays.equals(one.dtab, two.dtab));
        assertFalse(java.util.Arrays.equals(one.perm, FusedLibCampaign.Tables.build(new SplittableRandom(2)).perm));
        var op = operation("improvedNoise");
        assertTrue(java.util.Arrays.deepEquals(sample(op, 50, 9), sample(op, 50, 9)));
        assertFalse(java.util.Arrays.deepEquals(sample(op, 50, 9), sample(op, 50, 10)));
    }

    @Test
    void theVerdictIsBitExactExceptThatAnyNaNMatchesANaN() {
        long nan = 0x7ff8000000000000L;
        assertTrue(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, nan, 0xfff0000000000001L));
        assertTrue(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, 0x3ff0000000000000L, 0x3ff0000000000000L));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, 0x3ff0000000000000L, 0x3ff0000000000001L));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, 0L, Long.MIN_VALUE), "signed zeros differ");
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, nan, 0x7ff0000000000000L), "a NaN is not an infinity");
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS64, 0x7ff0000000000000L, nan));
        assertTrue(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS32, 0x7fc00000L, 0xffc00001L));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS32, 0x3f800000L, 0x3f800001L));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BITS32, 0x3f800000L, 0x13f800000L), "the high word must be empty");
        assertTrue(FusedLibCampaign.matches(FusedLibCampaign.Kind.INT32, -5L, 0xfffffffbL));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.INT32, -5L, 0x1fffffffbL));
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.EXACT64, nan, 0xfff8000000000000L), "integer results are never NaN-tolerant");
        assertFalse(FusedLibCampaign.matches(FusedLibCampaign.Kind.BOOL, 1L, 0L));
    }

    @Test
    void theShaderSwitchesOnEveryOperationAndPassesTheTextContract() {
        String source = FusedLibCampaign.shaderSource();
        for (int id = 0; id < FusedLibCampaign.operations().size(); id++) {
            assertTrue(source.contains("case " + id + "u: "), "case " + id);
        }
        var inspection = new SpirvNumericContract().inspect(source, NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
        assertEquals(source, FusedLibCampaign.shaderSource());
        assertFalse(source.contains("\r"));
        assertFalse(source.contains("fma(") || source.contains("sqrt(") && !source.contains("wg_fp64_sqrt("));
        assertNotEquals(List.of(), FusedLibCampaign.operations());
    }
}
