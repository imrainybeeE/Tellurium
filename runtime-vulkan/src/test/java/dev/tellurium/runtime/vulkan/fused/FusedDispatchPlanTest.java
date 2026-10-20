// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Compiled;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Dialect;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Geometry;
import dev.tellurium.runtime.vulkan.fused.FusedDispatchPlan.SlotBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The dispatch plan is shared by the Vulkan and Metal backends; it must stay what FusedNoiseDevice always ran. */
class FusedDispatchPlanTest {
    private static final String[] LEGACY_KERNELS = {"K_COLUMN", "K_XZ", "K_CORNER", "K_PRELIM", "K_AQUIFER", "K_BLOCK", "K_HEIGHT",
            "K_SURF_A", "K_SURF_B"};

    private static Compiled compiled(Geometry g, boolean aquifers, int xzChannels, int flat, int interpolated) {
        String source = "#version 450\nconst bool AQUIFERS = " + aquifers + ";\n";
        return new Compiled(source, new double[5], new int[7], flat, interpolated, g, "f".repeat(64), List.of(), List.of(),
                "s", xzChannels, 0, "x", Dialect.IEEE_BITS);
    }

    private static final Geometry OVERWORLD = new Geometry(-64, 384, 384, 4, 8, 4, 48, -8, 5, 49, -3);
    private static final Geometry NETHER = new Geometry(0, 128, 128, 4, 8, 4, 16, 0, 5, 17, 0);

    /** The thread counts and the order exactly as FusedNoiseDevice.submit computed them before the plan was shared. */
    private static long[] legacyThreads(Compiled c, int count, int prelimCount, boolean surface, boolean aquifers) {
        var g = c.geometry();
        return new long[]{(long) count * g.columnCount(),
                c.xzChannels() > 0 ? (long) count * 256 : 0L, (long) count * g.cornerCount(),
                (long) prelimCount,
                aquifers ? (long) count * g.aquiferCellCount() : 0L,
                (long) count * 64 * g.storageHeight(), (long) count * 256,
                surface ? (long) count * 64 : 0L, surface ? (long) count * 64 : 0L};
    }

    private static int[] legacyOrder(boolean surface) {
        return surface ? new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 6} : new int[]{0, 1, 2, 3, 4, 5, 6};
    }

    @Test void kernelNamesAndIndicesAreTheOnesTheSourceMacrosUse() {
        assertEquals(LEGACY_KERNELS.length, FusedDispatchPlan.KERNEL_COUNT);
        for (int k = 0; k < LEGACY_KERNELS.length; k++) assertEquals(LEGACY_KERNELS[k], FusedDispatchPlan.kernelName(k));
        assertEquals(6, FusedDispatchPlan.KERNEL_HEIGHT);
        assertEquals(7, FusedDispatchPlan.KERNEL_SURF_A);
        assertEquals(8, FusedDispatchPlan.KERNEL_SURF_B);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> FusedDispatchPlan.kernelName(FusedDispatchPlan.KERNEL_COUNT));
    }

    @Test void threadCountsEqualWhatTheVulkanDeviceDispatched() {
        for (Geometry g : List.of(OVERWORLD, NETHER)) {
            for (boolean aquifers : new boolean[]{false, true}) {
                for (int xz : new int[]{0, 3}) {
                    Compiled c = compiled(g, aquifers, xz, 6, 4);
                    for (boolean surface : new boolean[]{false, true}) {
                        for (int count : new int[]{1, 7, 64}) {
                            for (int prelim : new int[]{0, 31 * 31, 5 * 961}) {
                                assertArrayEquals(legacyThreads(c, count, prelim, surface, aquifers), FusedDispatchPlan.threads(c, count, prelim, surface),
                                        g + " aquifers=" + aquifers + " xz=" + xz + " surface=" + surface + " count=" + count + " prelim=" + prelim);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void dispatchOrderEqualsTheVulkanOneWithAndWithoutSurface() {
        assertArrayEquals(legacyOrder(false), FusedDispatchPlan.order(false));
        assertArrayEquals(legacyOrder(true), FusedDispatchPlan.order(true));
        // The surface kernels rewrite blocks: the heightmap kernel runs once before them and once after.
        int[] surface = FusedDispatchPlan.order(true);
        assertEquals(FusedDispatchPlan.KERNEL_HEIGHT, surface[surface.length - 1]);
        assertEquals(FusedDispatchPlan.KERNEL_SURF_B, surface[surface.length - 2]);
        assertFalse(Arrays.stream(FusedDispatchPlan.order(false)).anyMatch(k -> k == FusedDispatchPlan.KERNEL_SURF_A || k == FusedDispatchPlan.KERNEL_SURF_B));
    }

    @Test void aSkippedStageGetsNoThreads() {
        Compiled none = compiled(OVERWORLD, false, 0, 6, 4);
        long[] threads = FusedDispatchPlan.threads(none, 8, 0, false);
        assertEquals(0, threads[FusedDispatchPlan.KERNEL_XZ], "no cross-section cache channels");
        assertEquals(0, threads[FusedDispatchPlan.KERNEL_PRELIM], "no preliminary columns");
        assertEquals(0, threads[FusedDispatchPlan.KERNEL_AQUIFER], "no aquifers compiled in");
        assertEquals(0, threads[FusedDispatchPlan.KERNEL_SURF_A]);
        assertEquals(0, threads[FusedDispatchPlan.KERNEL_SURF_B]);
        assertTrue(threads[FusedDispatchPlan.KERNEL_COLUMN] > 0 && threads[FusedDispatchPlan.KERNEL_BLOCK] > 0);
        assertFalse(FusedDispatchPlan.aquifers(none));
        assertTrue(FusedDispatchPlan.aquifers(compiled(OVERWORLD, true, 0, 6, 4)));
    }

    @Test void groupsRoundUpToWholeWorkgroupsOf64() {
        assertEquals(0, FusedDispatchPlan.groups(0));
        assertEquals(1, FusedDispatchPlan.groups(1));
        assertEquals(1, FusedDispatchPlan.groups(64));
        assertEquals(2, FusedDispatchPlan.groups(65));
        assertEquals(64, FusedDispatchPlan.groups(64L * 64));
        assertEquals(3_000_000_000L / 64 + 1, FusedDispatchPlan.groups(3_000_000_000L + 1));
    }

    @Test void slotBufferSizesAreWhatTheVulkanSlotAllocated() {
        Compiled c = compiled(OVERWORLD, true, 3, 6, 4);
        var g = c.geometry();
        int n = 12;
        assertEquals(n * 8 * 4L, SlotBuffer.CHUNKS.size(c, n));
        assertEquals((long) n * 6 * g.columnCount() * 8, SlotBuffer.COLUMNS.size(c, n));
        assertEquals((long) n * 4 * g.cornerCount() * 8, SlotBuffer.CORNERS.size(c, n));
        assertEquals((long) n * g.aquiferCellCount() * 8 * 4, SlotBuffer.AQUIFER.size(c, n));
        assertEquals((long) n * g.blockCount(), SlotBuffer.BLOCKS.size(c, n));
        assertEquals((long) n * 2048 * 4, SlotBuffer.BEARD.size(c, n));
        assertEquals(n * 4L, SlotBuffer.FLAGS.size(c, n));
        assertEquals((long) n * 2 * 256 * 4, SlotBuffer.HEIGHTS.size(c, n));
        assertEquals((long) n * 3 * 256 * 8, SlotBuffer.XZCACHE.size(c, n));
        assertEquals((long) n * 961 * 4, SlotBuffer.PRELIM_INDEX.size(c, n));
        assertEquals((long) n * 961 * 8, SlotBuffer.PRELIM_COLUMNS.size(c, n));
        assertEquals((long) n * 961 * 4, SlotBuffer.PRELIM_OUT.size(c, n));
        assertEquals((long) n * c.biomeWordsPerChunk() * 4, SlotBuffer.BIOMES.size(c, n));
        assertEquals((long) n * 256 * 4, SlotBuffer.SURF_TMP.size(c, n));
        assertEquals(n * 2048L, FusedDispatchPlan.beardCapacityInts(n));
        assertEquals(n * 961, FusedDispatchPlan.prelimCapacity(n));
    }

    @Test void emptyChannelGroupsStillGetOneChannelOfStorage() {
        Compiled c = compiled(NETHER, false, 0, 0, 0);
        var g = c.geometry();
        assertEquals((long) 2 * g.columnCount() * 8, SlotBuffer.COLUMNS.size(c, 2));
        assertEquals((long) 2 * g.cornerCount() * 8, SlotBuffer.CORNERS.size(c, 2));
        assertEquals((long) 2 * 256 * 8, SlotBuffer.XZCACHE.size(c, 2));
    }

    @Test void bindingsAreTheKernelsWithPermAndDtabAtOneAndTwoAndNothingSharesABinding() {
        Set<Integer> seen = new HashSet<>(List.of(FusedDispatchPlan.PERM_BINDING, FusedDispatchPlan.DTAB_BINDING));
        assertEquals(2, seen.size());
        for (SlotBuffer buffer : SlotBuffer.values()) {
            assertTrue(buffer.binding() >= 0 && buffer.binding() < FusedDispatchPlan.BINDINGS, buffer + " binding in range");
            assertTrue(seen.add(buffer.binding()), buffer + " has its own binding");
        }
        assertEquals(FusedDispatchPlan.BINDINGS, seen.size(), "every binding is taken, in the order chunks, perm, dtab, columns, ... surfTmp");
        // The order the Vulkan device bound its buffers in before the plan was shared.
        SlotBuffer[] legacy = {SlotBuffer.CHUNKS, null, null, SlotBuffer.COLUMNS, SlotBuffer.CORNERS, SlotBuffer.AQUIFER, SlotBuffer.BLOCKS,
                SlotBuffer.BEARD, SlotBuffer.FLAGS, SlotBuffer.HEIGHTS, SlotBuffer.XZCACHE, SlotBuffer.PRELIM_INDEX, SlotBuffer.PRELIM_COLUMNS,
                SlotBuffer.PRELIM_OUT, SlotBuffer.BIOMES, SlotBuffer.SURF_TMP};
        for (int binding = 0; binding < legacy.length; binding++) if (legacy[binding] != null) assertEquals(binding, legacy[binding].binding());
    }

    @Test void allocationsAreAtLeast16BytesAndMultiplesOf16() {
        assertEquals(16, FusedDispatchPlan.allocationBytes(0));
        assertEquals(16, FusedDispatchPlan.allocationBytes(1));
        assertEquals(16, FusedDispatchPlan.allocationBytes(16));
        assertEquals(32, FusedDispatchPlan.allocationBytes(17));
        assertEquals(4096, FusedDispatchPlan.allocationBytes(4090));
    }

    @Test void tablesAreSizedByTheirElements() {
        Compiled c = compiled(NETHER, false, 0, 1, 1);
        assertEquals(7 * 4, FusedDispatchPlan.permBytes(c));
        assertEquals(5 * 8, FusedDispatchPlan.doubleTableBytes(c));
    }
}
