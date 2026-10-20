// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole campaign against a stand-in device that follows the shader's ABI (operand words, output words,
 * extra buffers) but computes with the reference. That proves the host plumbing a device run depends on: the
 * packing of operands, the three extra buffers, the unpacking of values and bail words, the chunking, the report,
 * and that a wrong value, a wrong bail word or a fault in one operation is reported and named instead of passing.
 */
class FusedLibCampaignRunTest {
    private static final double SCALE = 0.01;

    /** A device that answers every case with the reference, then lets {@code tamper} change the output of one dispatch. */
    private static FusedLibCampaign.Dispatcher device(int tamperedOperation, IntConsumerArray tamper) {
        return (spirv, operation, input, count, outputWords, extra) -> {
            assertTrue(spirv.length > 1000 && spirv.length % 4 == 0);
            assertEquals(FusedLibCampaign.OUTPUT_WORDS, outputWords);
            assertEquals(count * FusedLibCampaign.INPUT_WORDS, input.length);
            assertEquals(3, extra.length, "flags, perm, dtab");
            assertEquals(count, extra[0].length, "one flags word per case");
            double[] dtab = new double[extra[2].length / 2];
            for (int i = 0; i < dtab.length; i++) {
                dtab[i] = Double.longBitsToDouble((extra[2][i * 2] & 0xffffffffL) | ((long) extra[2][i * 2 + 1] << 32));
            }
            var lib = new FusedLibReference(extra[1], dtab);
            lib.correctlyRoundedZero = true;
            var op = FusedLibCampaign.operations().get(operation);
            int[] output = new int[count * outputWords];
            for (int i = 0; i < count; i++) {
                long[] c = new long[10];
                for (int slot = 0; slot < 10; slot++) {
                    c[slot] = (input[i * FusedLibCampaign.INPUT_WORDS + slot * 2] & 0xffffffffL)
                            | ((long) input[i * FusedLibCampaign.INPUT_WORDS + slot * 2 + 1] << 32);
                }
                lib.clearBails();
                long value = op.eval().eval(lib, c);
                output[i * outputWords] = (int) value;
                output[i * outputWords + 1] = switch (op.kind()) {
                    case BITS64, EXACT64 -> (int) (value >>> 32);
                    default -> 0;
                };
                output[i * outputWords + 2] = lib.bailCode;
            }
            if (operation == tamperedOperation) tamper.accept(output, outputWords);
            return output;
        };
    }

    /** The low bit of every value word: a NaN stays a NaN (the verdict accepts any), every other value is wrong. */
    private static void flipEveryValue(int[] output, int outputWords) {
        for (int i = 0; i < output.length / outputWords; i++) output[i * outputWords] ^= 1;
    }

    private interface IntConsumerArray { void accept(int[] output, int outputWords); }

    private static int index(String name) {
        for (int i = 0; i < FusedLibCampaign.operations().size(); i++) if (FusedLibCampaign.operations().get(i).name().equals(name)) return i;
        throw new AssertionError(name);
    }

    @Test
    void aFaithfulDevicePassesEveryFunctionAndTheReportSaysSo(@TempDir Path directory) throws IOException {
        Path report = directory.resolve("nested/fused-lib.json");
        var result = FusedLibCampaign.execute(device(-1, (o, w) -> { }), "stand-in", report.toString(), SCALE);
        String json = Files.readString(report);
        assertEquals(result.json(), json);
        assertTrue(json.contains("\"campaign\": \"fused-lib\"") && json.contains("\"status\": \"PASS\"") && json.contains("\"mismatches\": 0"));
        for (var op : FusedLibCampaign.operations()) assertTrue(json.contains("\"" + op.name() + "\": {\"comparisons\": "), op.name());
        assertFalse(json.contains("\"comparisons\": 0,") || json.contains("{\"comparisons\": 0,"), "every function compared something");
        assertTrue(result.summary().contains("DEVICE_PASS") && result.summary().contains("functions=" + FusedLibCampaign.operations().size()));
        assertTrue(json.contains("\"zeroSignDeviationsFromNative\": "));
        assertTrue(json.contains("\"sourceSha256\": \"") && json.contains("\"spirvSha256\": \""));
    }

    @Test
    void aWrongValueIsReportedAgainstTheFunctionThatProducedIt() {
        for (String name : new String[] {"jdiv", "jsqrtf", "splineEval", "endIsland", "safeMagnitude", "rngNextInt"}) {
            var failure = assertThrows(IllegalStateException.class, () ->
                    FusedLibCampaign.execute(device(index(name), FusedLibCampaignRunTest::flipEveryValue), "stand-in", null, SCALE), name);
            assertTrue(failure.getMessage().contains(name + ": "), failure.getMessage());
            assertTrue(failure.getMessage().contains("1 of " + FusedLibCampaign.operations().size() + " functions"), failure.getMessage());
        }
    }

    @Test
    void aWrongBailWordIsAMismatchEvenWhenTheValueIsRight() {
        var failure = assertThrows(IllegalStateException.class, () ->
                FusedLibCampaign.execute(device(index("jdivf"), (o, w) -> o[2] ^= 32), "stand-in", null, SCALE));
        assertTrue(failure.getMessage().contains("jdivf: "), failure.getMessage());
    }

    @Test
    void aWrongSignOfZeroIsAMismatchButADifferentNaNPayloadIsNot() throws IOException {
        // jmin(-0.0, 0.0) must be -0.0: a device that returns +0.0 fails
        var failure = assertThrows(IllegalStateException.class, () ->
                FusedLibCampaign.execute(device(index("jmin"), (o, w) -> {
                    for (int i = 0; i < o.length / w; i++) if (o[i * w] == 0 && o[i * w + 1] == Integer.MIN_VALUE) o[i * w + 1] = 0;
                }), "stand-in", null, SCALE));
        assertTrue(failure.getMessage().contains("jmin: "), failure.getMessage());
        // NaN results keep passing when the sign and payload differ
        FusedLibCampaign.execute(device(index("jmin"), (o, w) -> {
            for (int i = 0; i < o.length / w; i++) {
                if (((o[i * w + 1] >>> 20) & 0x7ff) == 0x7ff && ((o[i * w + 1] & 0xfffff) != 0 || o[i * w] != 0)) {
                    o[i * w + 1] ^= Integer.MIN_VALUE;
                    o[i * w] ^= 0x55;
                }
            }
        }), "stand-in", null, SCALE);
    }

    @Test
    void everyFailingFunctionIsReportedInOneRunNotJustTheFirst() {
        var failure = assertThrows(IllegalStateException.class, () -> FusedLibCampaign.execute((spirv, operation, input, count, outputWords, extra) -> {
            int[] output = device(-1, (o, w) -> { }).dispatch(spirv, operation, input, count, outputWords, extra);
            if (operation == index("jdiv") || operation == index("wrapd")) flipEveryValue(output, outputWords);
            return output;
        }, "stand-in", null, SCALE));
        assertTrue(failure.getMessage().contains("jdiv: ") && failure.getMessage().contains("wrapd: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("2 of " + FusedLibCampaign.operations().size() + " functions"), failure.getMessage());
    }

    @Test
    void theRunnerRefusesToCombineTheFusedLibraryCampaignWithAnotherMode() {
        // refused before any Vulkan call, so this needs no device
        for (String other : new String[] {"--fused-ieee", "--release", "--arithmetic-only", "--noise-only", "--host-nan-sign-agnostic"}) {
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    dev.tellurium.runtime.vulkan.NativeIntegerIeeeRunner.main(new String[] {"--fused-lib", other}), other);
            assertTrue(failure.getMessage().contains("--fused-"), failure.getMessage());
        }
    }
}
