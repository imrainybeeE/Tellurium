// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Compiled;

/**
 * What one batch of the fused NOISE kernels runs and what it runs on, written once for every backend (Vulkan's
 * {@link FusedNoiseDevice} and the native Metal one): the kernels, how many threads each gets, in which order they are
 * dispatched, and the size and binding of every buffer a batch slot owns.  The kernels read their buffers by binding
 * number, so a backend that bound them differently would run a different program.
 */
public final class FusedDispatchPlan {
    /** Threads per workgroup, the kernels' local_size_x. */
    public static final int GROUP_SIZE = 64;
    /** Storage buffers bound for a kernel (bindings 0 to 15). */
    public static final int BINDINGS = 16;
    public static final int KERNEL_COLUMN = 0, KERNEL_XZ = 1, KERNEL_CORNER = 2, KERNEL_PRELIM = 3, KERNEL_AQUIFER = 4,
            KERNEL_BLOCK = 5, KERNEL_HEIGHT = 6, KERNEL_SURF_A = 7, KERNEL_SURF_B = 8;
    /** The kernels by index: each is the macro that selects it in the program's GLSL source. */
    private static final String[] KERNELS = {"K_COLUMN", "K_XZ", "K_CORNER", "K_PRELIM", "K_AQUIFER", "K_BLOCK", "K_HEIGHT",
            "K_SURF_A", "K_SURF_B"};
    public static final int KERNEL_COUNT = KERNELS.length;
    /** The binding of the program-wide permutation table (ints) and double table. */
    public static final int PERM_BINDING = 1, DTAB_BINDING = 2;
    /** Bytes of push constants: the chunk count and the preliminary column count, as two ints. */
    public static final int PUSH_CONSTANT_BYTES = 8;

    private FusedDispatchPlan() {}

    /** The GLSL macro that selects kernel {@code kernel}. */
    public static String kernelName(int kernel) { return KERNELS[kernel]; }

    /** The buffers each batch slot owns and binds, with the binding the kernels read them at. */
    public enum SlotBuffer {
        CHUNKS(0), COLUMNS(3), CORNERS(4), AQUIFER(5), BLOCKS(6), BEARD(7), FLAGS(8), HEIGHTS(9), XZCACHE(10),
        PRELIM_INDEX(11), PRELIM_COLUMNS(12), PRELIM_OUT(13), BIOMES(14), SURF_TMP(15);

        private final int binding;

        SlotBuffer(int binding) { this.binding = binding; }

        public int binding() { return binding; }

        /** The buffer's size in bytes, before any alignment, for a slot of {@code maxBatchChunks} chunks of {@code compiled}. */
        public long size(Compiled compiled, int maxBatchChunks) {
            var g = compiled.geometry();
            long n = maxBatchChunks;
            return switch (this) {
                case CHUNKS -> n * dev.tellurium.compiler.vulkan.fused.FusedGpuBackend.CHUNK_INFO_INTS * 4;
                case COLUMNS -> n * Math.max(1, compiled.flatChannels()) * g.columnCount() * 8;
                case CORNERS -> n * Math.max(1, compiled.interpolatedChannels()) * g.cornerCount() * 8;
                case AQUIFER -> n * g.aquiferCellCount() * 8 * 4;
                case BLOCKS -> n * g.blockCount();
                case BEARD -> beardCapacityInts(maxBatchChunks) * 4;
                case FLAGS -> n * 4;
                case HEIGHTS -> n * 2 * 256 * 4;
                case XZCACHE -> n * Math.max(1, compiled.xzChannels()) * 256 * 8;
                case PRELIM_INDEX -> prelimCapacity(maxBatchChunks) * 4;
                case PRELIM_COLUMNS -> prelimCapacity(maxBatchChunks) * 8;
                case PRELIM_OUT -> prelimCapacity(maxBatchChunks) * 4;
                case BIOMES -> n * compiled.biomeWordsPerChunk() * 4;
                case SURF_TMP -> n * 256 * 4;
            };
        }
    }

    /** Ints of structure (beardifier) input a slot takes. */
    public static long beardCapacityInts(int maxBatchChunks) { return (long) maxBatchChunks * 2048; }

    /** Unique preliminary-surface columns a slot takes: 31 x 31 per chunk, all distinct at worst. */
    public static int prelimCapacity(int maxBatchChunks) { return maxBatchChunks * 961; }

    /** The size a buffer is allocated with: at least 16 bytes, a multiple of 16. */
    public static long allocationBytes(long size) { return Math.max(16, (size + 15) & ~15L); }

    /** The perm table's and double table's sizes in bytes (before {@link #allocationBytes}). */
    public static long permBytes(Compiled compiled) { return (long) compiled.permTable().length * 4; }
    public static long doubleTableBytes(Compiled compiled) { return (long) compiled.doubleTable().length * 8; }

    /** Whether the program was compiled with the aquifer stage (and so produces aquifer cells). */
    public static boolean aquifers(Compiled compiled) {
        return compiled.source().contains("const bool AQUIFERS = true;");
    }

    /**
     * Threads each kernel is dispatched with for {@code count} chunks, indexed by kernel; zero means the kernel
     * is skipped (no cross-section cache channels, no aquifers compiled in, no surface stage, no preliminary
     * columns).
     */
    public static long[] threads(Compiled compiled, int count, int prelimCount, boolean surface) {
        var g = compiled.geometry();
        return new long[]{(long) count * g.columnCount(),
                compiled.xzChannels() > 0 ? (long) count * 256 : 0L, (long) count * g.cornerCount(),
                (long) prelimCount,
                aquifers(compiled) ? (long) count * g.aquiferCellCount() : 0L,
                (long) count * GROUP_SIZE * g.storageHeight(), (long) count * 256,
                surface ? (long) count * GROUP_SIZE : 0L, surface ? (long) count * GROUP_SIZE : 0L};
    }

    /** Dispatch order; the surface kernels rewrite blocks, so heightmaps are recomputed after them. */
    public static int[] order(boolean surface) {
        return surface ? new int[]{KERNEL_COLUMN, KERNEL_XZ, KERNEL_CORNER, KERNEL_PRELIM, KERNEL_AQUIFER, KERNEL_BLOCK, KERNEL_HEIGHT,
                KERNEL_SURF_A, KERNEL_SURF_B, KERNEL_HEIGHT}
                : new int[]{KERNEL_COLUMN, KERNEL_XZ, KERNEL_CORNER, KERNEL_PRELIM, KERNEL_AQUIFER, KERNEL_BLOCK, KERNEL_HEIGHT};
    }

    /** Workgroups for {@code threads} invocations. */
    public static long groups(long threads) { return (threads + GROUP_SIZE - 1) / GROUP_SIZE; }
}
