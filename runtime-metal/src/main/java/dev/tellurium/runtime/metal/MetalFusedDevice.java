// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.metal;

import dev.tellurium.compiler.vulkan.fused.FusedGpuBackend;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.tellurium.runtime.vulkan.fused.FusedDispatchPlan;
import dev.tellurium.runtime.vulkan.fused.FusedDispatchPlan.SlotBuffer;
import dev.tellurium.runtime.vulkan.fused.FusedNoiseDevice;
import dev.tellurium.runtime.vulkan.fused.MetalKernelSource;
import dev.tellurium.semantic.program.NumericProfile;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fused NOISE kernels on Metal directly.  The emulated (IEEE_BITS) kernels are the Metal source SPIRV-Cross writes
 * for them ({@link MetalKernelSource}), built into compute pipelines here and dispatched in the 64-thread groups the
 * kernels declare; MoltenVK cannot know that size for a kernel handed over as Metal source, so it ran one thread per
 * group and left 31 of 32 SIMD lanes idle.
 *
 * <p>Every buffer is a shared-storage MTLBuffer (Apple GPUs use the CPU's memory): the engine's ByteBuffers are views
 * of the buffers' contents, nothing is copied, and the results are readable once {@link Slot#poll} says true.
 * What runs and on which buffers is {@link FusedDispatchPlan}'s, as for the Vulkan device.  A slot is reused only after
 * its command buffer completed; a failed or overdue one marks the device lost, and the buffers of a submission that was
 * never proven finished are never freed.</p>
 */
public final class MetalFusedDevice implements FusedGpuBackend {
    private static final boolean LOG_KERNELS = Boolean.getBoolean("tellurium.fast.logKernels");
    private static final boolean DEBUG_BUFFERS = Boolean.getBoolean("tellurium.fast.debugBuffers");
    /** Developer fault injection, as for the Vulkan device: from this many submissions on, a batch is reported as never finishing. */
    private static final long STALL_AFTER = Long.getLong("tellurium.fast.stallFenceAfterSubmissions", 0);

    private static final long MTL_RESOURCE_STORAGE_MODE_SHARED = 0;
    private static final long MTL_LANGUAGE_VERSION_3_1 = (3L << 16) | 1;
    private static final long MTL_MATH_MODE_SAFE = 0;
    private static final long MTL_BARRIER_SCOPE_BUFFERS = 1;
    /** MTLCommandBufferStatus: Completed and Error are the two end states. */
    private static final long STATUS_COMPLETED = 4, STATUS_ERROR = 5;

    private final MemorySegment device;
    private final MemorySegment queue;
    private final String deviceName;
    private volatile boolean lost;
    private volatile String lostReason = "";
    private final List<Program> programs = new ArrayList<>();
    private boolean closed;
    private final AtomicLong submissions = new AtomicLong();

    private MetalFusedDevice(MemorySegment device, MemorySegment queue, String deviceName) {
        this.device = device;
        this.queue = queue;
        this.deviceName = deviceName;
    }

    /** Null when a Metal device can be opened here, else why not (not macOS, no Metal device, the frameworks do not load). */
    public static String unavailableReason() {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(java.util.Locale.ROOT).startsWith("mac")) return "Metal needs macOS, this is " + os;
        try {
            ObjC.require();
            MemorySegment device = ObjC.createSystemDefaultDevice();
            if (ObjC.isNull(device)) return "this Mac has no Metal device";
            ObjC.release(device);
            return null;
        } catch (LinkageError | RuntimeException failure) {
            return "the Objective-C runtime or Metal could not be loaded: " + failure;
        }
    }

    /** Opens the system's default Metal device; throws with the reason when there is none. */
    public static MetalFusedDevice open() {
        String unavailable = unavailableReason();
        if (unavailable != null) throw new IllegalStateException(unavailable);
        try (ObjC.Pool ignored = ObjC.pool()) {
            MemorySegment device = ObjC.createSystemDefaultDevice();
            MemorySegment queue = ObjC.ptr(device, "newCommandQueue");
            if (ObjC.isNull(queue)) {
                ObjC.release(device);
                throw new IllegalStateException("The Metal device did not give a command queue");
            }
            return new MetalFusedDevice(device, queue, ObjC.string(ObjC.ptr(device, "name")));
        }
    }

    @Override public String deviceName() { return deviceName; }
    @Override public NumericProfile numericProfile() { return NumericProfile.GPU_IEEE_BITS; }
    @Override public boolean lost() { return lost; }
    @Override public String lostReason() { return lostReason; }
    @Override public void loseDeviceForTest(String reason) { lostReason = reason; lost = true; }
    @Override public boolean profiling() { return false; }
    @Override public boolean debugBuffers() { return DEBUG_BUFFERS; }
    @Override public String profileSummary() { return "per-kernel profiling is not available on the Metal backend"; }

    /** @throws IllegalArgumentException when the program is not of the IEEE_BITS dialect, the only one Apple GPUs can run */
    @Override
    public Program load(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
        if (maxBatchChunks <= 0 || slotCount <= 0) throw new IllegalArgumentException("Batch size and slot count must be positive");
        FusedNoiseDevice.requireDialect(NumericProfile.GPU_IEEE_BITS, compiled.dialect());
        synchronized (this) {
            if (closed) throw new IllegalStateException("Fused NOISE device is closed");
            if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
        }
        String unsupported = MetalKernelSource.unsupportedReason();
        if (unsupported != null) throw new IllegalStateException(unsupported);
        // Building the kernels takes seconds; a program that is already loaded keeps generating meanwhile.
        Program program = new Program(compiled, maxBatchChunks, slotCount);
        synchronized (this) {
            if (closed) {
                program.close();
                throw new IllegalStateException("Fused NOISE device is closed");
            }
            programs.add(program);
        }
        return program;
    }

    // ---------------------------------------------------------------- Metal objects

    /** A shared-storage MTLBuffer and the bytes of its contents (null when too large for a ByteBuffer). */
    record Buffer(MemorySegment id, ByteBuffer bytes, long size) {}

    /** Allocates a buffer of {@link FusedDispatchPlan#allocationBytes} bytes. */
    Buffer newBuffer(long size) {
        long bytes = FusedDispatchPlan.allocationBytes(size);
        MemorySegment id = ObjC.ptr(device, "newBufferWithLength:options:", bytes, MTL_RESOURCE_STORAGE_MODE_SHARED);
        if (ObjC.isNull(id)) throw new IllegalStateException("Metal could not allocate a buffer of " + bytes + " bytes");
        MemorySegment contents = ObjC.ptr(id, "contents");
        if (ObjC.isNull(contents)) {
            ObjC.release(id);
            throw new IllegalStateException("A shared Metal buffer of " + bytes + " bytes has no CPU address");
        }
        // The engine reads the buffers it names through a ByteBuffer; one over 2 GiB (a debug dump of a huge batch) has no view.
        return new Buffer(id, bytes <= Integer.MAX_VALUE ? ObjC.view(contents, bytes) : null, bytes);
    }

    /**
     * Builds one compute pipeline (+1) from Metal source: safe math, the function {@code entry}.  Metal's own error
     * text is the failure's message.  The pipeline must allow groups of {@link FusedDispatchPlan#GROUP_SIZE} threads.
     */
    MemorySegment newPipeline(String source, String entry) {
        try (ObjC.Pool ignored = ObjC.pool(); Arena arena = Arena.ofConfined()) {
            MemorySegment options = ObjC.ptr(ObjC.ptr(ObjC.cls("MTLCompileOptions"), "alloc"), "init");
            MemorySegment library = MemorySegment.NULL;
            MemorySegment function = MemorySegment.NULL;
            try {
                ObjC.call(options, "setLanguageVersion:", MTL_LANGUAGE_VERSION_3_1);
                // Fast math off: the kernels are integer-only, and a compiler free to reorder float arithmetic has no place near them.
                if (ObjC.responds(options, "setMathMode:")) ObjC.call(options, "setMathMode:", MTL_MATH_MODE_SAFE);
                else ObjC.call(options, "setFastMathEnabled:", false);
                MemorySegment errors = arena.allocate(ValueLayout.ADDRESS);
                library = ObjC.ptr(device, "newLibraryWithSource:options:error:", ObjC.nsString(source), options, errors);
                if (ObjC.isNull(library)) {
                    throw new IllegalStateException("Metal could not compile " + entry + ": " + ObjC.errorText(errors.get(ValueLayout.ADDRESS, 0)));
                }
                function = ObjC.ptr(library, "newFunctionWithName:", ObjC.nsString(entry));
                if (ObjC.isNull(function)) throw new IllegalStateException("The Metal source has no function " + entry);
                errors.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                MemorySegment pipeline = ObjC.ptr(device, "newComputePipelineStateWithFunction:error:", function, errors);
                if (ObjC.isNull(pipeline)) {
                    throw new IllegalStateException("Metal could not build a pipeline for " + entry + ": " + ObjC.errorText(errors.get(ValueLayout.ADDRESS, 0)));
                }
                long most = ObjC.num(pipeline, "maxTotalThreadsPerThreadgroup");
                if (most < FusedDispatchPlan.GROUP_SIZE) {
                    ObjC.release(pipeline);
                    throw new IllegalStateException("The pipeline for " + entry + " allows " + most + " threads per group, fewer than the "
                            + FusedDispatchPlan.GROUP_SIZE + " the kernels are written for");
                }
                return pipeline;
            } finally {
                ObjC.release(function);
                ObjC.release(library);
                ObjC.release(options);
            }
        }
    }

    /**
     * Encodes one command buffer and commits it: the push constants (ints) at buffer index 0, every non-null
     * {@code buffers[binding]} at index {@code binding + 1} (where MetalKernelSource's layout puts it), then each pipeline in
     * order dispatched in {@code groups[i]} groups of {@link FusedDispatchPlan#GROUP_SIZE} threads, with a buffer
     * barrier between dispatches.  Returns the command buffer, retained: release it once it completed.
     */
    MemorySegment commit(int[] push, MemorySegment[] buffers, MemorySegment[] pipelines, long[] groups) {
        if (pipelines.length != groups.length) throw new IllegalArgumentException("A group count for every pipeline");
        for (long g : groups) if (g <= 0 || g > 0xFFFF_FFFFL) throw new IllegalStateException("Dispatch of " + g + " groups");
        try (ObjC.Pool ignored = ObjC.pool(); Arena arena = Arena.ofConfined()) {
            MemorySegment commands = ObjC.ptr(queue, "commandBuffer");
            MemorySegment encoder = ObjC.ptr(commands, "computeCommandEncoder");
            if (ObjC.isNull(encoder)) throw new IllegalStateException("Metal did not give a compute command encoder");
            MemorySegment constants = arena.allocate(Math.max(4, push.length * 4L), 4);
            for (int i = 0; i < push.length; i++) constants.setAtIndex(ValueLayout.JAVA_INT, i, push[i]);
            ObjC.call(encoder, "setBytes:length:atIndex:", constants, push.length * 4L, 0L);
            for (int binding = 0; binding < buffers.length; binding++) {
                if (buffers[binding] != null) ObjC.call(encoder, "setBuffer:offset:atIndex:", buffers[binding], 0L, binding + 1L);
            }
            ObjC.Size group = new ObjC.Size(FusedDispatchPlan.GROUP_SIZE, 1, 1);
            for (int step = 0; step < pipelines.length; step++) {
                if (step > 0) ObjC.call(encoder, "memoryBarrierWithScope:", MTL_BARRIER_SCOPE_BUFFERS);
                ObjC.call(encoder, "setComputePipelineState:", pipelines[step]);
                ObjC.call(encoder, "dispatchThreadgroups:threadsPerThreadgroup:", new ObjC.Size(groups[step], 1, 1), group);
            }
            ObjC.call(encoder, "endEncoding");
            MemorySegment retained = ObjC.retain(commands);
            ObjC.call(retained, "commit");
            return retained;
        }
    }

    /** The command buffer's MTLCommandBufferStatus. */
    static long status(MemorySegment commands) { return ObjC.num(commands, "status"); }

    /** Whether the command buffer is in an end state: nothing of it still runs. */
    static boolean finished(MemorySegment commands) {
        long status = status(commands);
        return status == STATUS_COMPLETED || status == STATUS_ERROR;
    }

    /** The error of a command buffer that failed. */
    static String failure(MemorySegment commands) {
        try (ObjC.Pool ignored = ObjC.pool()) {
            return ObjC.errorText(ObjC.ptr(commands, "error"));
        }
    }

    /** Whether a status is a successful end. */
    static boolean completed(long status) { return status == STATUS_COMPLETED; }

    /** Whether a status is a failed end. */
    static boolean failed(long status) { return status == STATUS_ERROR; }

    // ---------------------------------------------------------------- program
    public final class Program implements FusedGpuBackend.Program, AutoCloseable {
        private final FusedNoiseCompiler.Compiled compiled;
        private final int maxBatchChunks;
        private final MemorySegment[] pipelines = new MemorySegment[FusedDispatchPlan.KERNEL_COUNT];
        private Buffer permBuffer;
        private Buffer dtabBuffer;
        private final Slot[] slots;
        private final long compileNanos;
        private boolean destroyed;

        Program(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
            this.compiled = compiled;
            this.maxBatchChunks = maxBatchChunks;
            this.slots = new Slot[slotCount];
            long start = System.nanoTime();
            try {
                compileKernels();
                permBuffer = newBuffer(FusedDispatchPlan.permBytes(compiled));
                permBuffer.bytes().asIntBuffer().put(compiled.permTable());
                dtabBuffer = newBuffer(FusedDispatchPlan.doubleTableBytes(compiled));
                dtabBuffer.bytes().asDoubleBuffer().put(compiled.doubleTable());
                for (int i = 0; i < slotCount; i++) slots[i] = new Slot();
            } catch (Throwable failure) {
                destroyAll();
                throw failure;
            }
            compileNanos = System.nanoTime() - start;
        }

        /** All kernels, in parallel: shaderc and SPIRV-Cross and Metal's compiler each work on one kernel per thread. */
        private void compileKernels() {
            int threads = Math.max(1, Math.min(FusedDispatchPlan.KERNEL_COUNT, Runtime.getRuntime().availableProcessors()));
            ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task, "tellurium-metal-compile");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<MemorySegment>> futures = new ArrayList<>();
                for (int k = 0; k < FusedDispatchPlan.KERNEL_COUNT; k++) {
                    int kernel = k;
                    futures.add(pool.submit(() -> compileKernel(kernel)));
                }
                Throwable first = null;
                for (int k = 0; k < futures.size(); k++) {
                    try {
                        pipelines[k] = futures.get(k).get();
                    } catch (ExecutionException failure) {
                        if (first == null) first = failure.getCause();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        if (first == null) first = interrupted;
                    }
                }
                if (first != null) {
                    if (first instanceof RuntimeException runtime) throw runtime;
                    if (first instanceof Error error) throw error;
                    throw new IllegalStateException(first);
                }
            } finally {
                pool.shutdownNow();
            }
        }

        private MemorySegment compileKernel(int kernel) {
            String name = FusedDispatchPlan.kernelName(kernel);
            long start = System.nanoTime();
            byte[] spirv = FusedNoiseDevice.compileGlsl(compiled.source(), name);
            FusedNoiseDevice.requireNoFloatTypes(spirv, name);
            String msl = MetalKernelSource.msl(spirv);
            long converted = System.nanoTime();
            MemorySegment pipeline = newPipeline(msl, MetalKernelSource.ENTRY_POINT);
            if (LOG_KERNELS) System.out.println("[tellurium-fast] kernel " + name + ": spirv " + spirv.length + " bytes, msl " + msl.length()
                    + " chars, shaderc+spirv-cross " + (converted - start) / 1_000_000 + " ms, metal pipeline " + (System.nanoTime() - converted) / 1_000_000 + " ms");
            return pipeline;
        }

        @Override public FusedNoiseCompiler.Compiled compiled() { return compiled; }
        @Override public long compileNanos() { return compileNanos; }
        @Override public int slotCount() { return slots.length; }
        @Override public Slot slot(int index) { return slots[index]; }

        @Override
        public synchronized void close() { destroyAll(); }

        /** True when a submission of this program was never proven finished: nothing it may still use was freed. */
        synchronized boolean quarantined() {
            for (Slot slot : slots) if (slot != null && slot.unproven()) return true;
            return false;
        }

        /** Frees what was created; with a submission not proven finished (it uses the pipelines and tables too) nothing is. */
        private synchronized void destroyAll() {
            if (destroyed || quarantined()) return;
            destroyed = true;
            for (Slot slot : slots) if (slot != null) slot.destroy();
            if (permBuffer != null) ObjC.release(permBuffer.id());
            if (dtabBuffer != null) ObjC.release(dtabBuffer.id());
            for (MemorySegment pipeline : pipelines) if (pipeline != null) ObjC.release(pipeline);
        }

        /** One in-flight batch: all buffers are owned until its command buffer completes. */
        public final class Slot implements FusedGpuBackend.Slot {
            private final Buffer[] owned = new Buffer[SlotBuffer.values().length];
            /** What the kernels see at each binding (the program's tables, this slot's buffers). */
            private final MemorySegment[] bound = new MemorySegment[FusedDispatchPlan.BINDINGS];
            private final int prelimCapacity;
            private MemorySegment commands;
            private volatile boolean pending;
            private long submittedNanos;

            Slot() {
                prelimCapacity = FusedDispatchPlan.prelimCapacity(maxBatchChunks);
                try {
                    for (SlotBuffer which : SlotBuffer.values()) {
                        Buffer buffer = newBuffer(which.size(compiled, maxBatchChunks));
                        owned[which.ordinal()] = buffer;
                        bound[which.binding()] = buffer.id();
                    }
                    bound[FusedDispatchPlan.PERM_BINDING] = permBuffer.id();
                    bound[FusedDispatchPlan.DTAB_BINDING] = dtabBuffer.id();
                } catch (Throwable failure) {
                    destroy();
                    throw failure;
                }
            }

            private ByteBuffer bytes(SlotBuffer which) { return owned[which.ordinal()].bytes(); }

            @Override public boolean pending() { return pending; }
            @Override public ByteBuffer chunkInfo() { return bytes(SlotBuffer.CHUNKS); }
            @Override public ByteBuffer beardData() { return bytes(SlotBuffer.BEARD); }
            @Override public ByteBuffer prelimIndex() { return bytes(SlotBuffer.PRELIM_INDEX); }
            @Override public ByteBuffer prelimColumns() { return bytes(SlotBuffer.PRELIM_COLUMNS); }
            @Override public int prelimColumnCapacity() { return prelimCapacity; }
            @Override public ByteBuffer biomes() { return bytes(SlotBuffer.BIOMES); }
            @Override public ByteBuffer blocks() { return bytes(SlotBuffer.BLOCKS); }
            @Override public ByteBuffer heights() { return bytes(SlotBuffer.HEIGHTS); }
            @Override public ByteBuffer aquifers() { return bytes(SlotBuffer.AQUIFER); }
            @Override public int flags(int chunk) { return bytes(SlotBuffer.FLAGS).getInt(chunk * 4); }
            @Override public ByteBuffer debugColumns() { return bytes(SlotBuffer.COLUMNS); }
            @Override public ByteBuffer debugCorners() { return bytes(SlotBuffer.CORNERS); }

            @Override
            public void submit(int count, int prelimCount, boolean surface) {
                if (prelimCount < 0 || prelimCount > prelimCapacity) throw new IllegalArgumentException("Invalid preliminary column count");
                if (pending) throw new IllegalStateException("Slot is still in flight");
                if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
                if (count <= 0 || count > maxBatchChunks) throw new IllegalArgumentException("Invalid batch size " + count);
                ByteBuffer flags = bytes(SlotBuffer.FLAGS);
                for (int i = 0; i < count; i++) flags.putInt(i * 4, 0);
                long[] threads = FusedDispatchPlan.threads(compiled, count, prelimCount, surface);
                int[] order = FusedDispatchPlan.order(surface);
                List<MemorySegment> steps = new ArrayList<>(order.length);
                List<Long> stepGroups = new ArrayList<>(order.length);
                for (int kernel : order) {
                    if (threads[kernel] == 0) continue;
                    steps.add(pipelines[kernel]);
                    stepGroups.add(FusedDispatchPlan.groups(threads[kernel]));
                }
                MemorySegment submitted;
                try {
                    submitted = commit(new int[]{count, prelimCount}, bound, steps.toArray(MemorySegment[]::new),
                            stepGroups.stream().mapToLong(Long::longValue).toArray());
                } catch (RuntimeException | Error failure) {
                    // As on Vulkan a submission that cannot be made gives the device up: nothing more is submitted
                    // and every later chunk goes to the original code.  The slot was not handed to the GPU.
                    lostReason = "Metal submit failed: " + failure;
                    lost = true;
                    throw new IllegalStateException(lostReason, failure);
                }
                commands = submitted;
                pending = true;
                submittedNanos = System.nanoTime();
                submissions.incrementAndGet();
            }

            @Override
            public boolean poll() {
                if (!pending) return true;
                boolean stalled = STALL_AFTER > 0 && submissions.get() >= STALL_AFTER;
                long status = stalled ? 2 : status(commands);
                if (completed(status)) {
                    ObjC.release(commands);
                    commands = null;
                    pending = false;
                    return true;
                }
                if (failed(status)) {
                    // The command buffer ended, but its results are not to be used: the slot stays owned (as a Vulkan
                    // slot of a failed fence does) and the device is given up.
                    if (!lost) {
                        lostReason = "Metal command buffer failed: " + failure(commands);
                        lost = true;
                    }
                } else if (!lost && FusedNoiseDevice.overdue(submittedNanos, System.nanoTime(), FusedNoiseDevice.FENCE_TIMEOUT_NANOS)) {
                    // A queue that neither finishes nor reports an error would keep the chunks of this batch waiting for ever.
                    lostReason = "a batch did not finish within " + FusedNoiseDevice.FENCE_TIMEOUT_NANOS / 1_000_000_000L + " s";
                    lost = true;
                }
                return false;
            }

            /** Pending, and not known to have ended (a failed command buffer has ended; it is still not completed work). */
            synchronized boolean unproven() {
                return pending && commands != null && !finished(commands);
            }

            void destroy() {
                if (unproven()) return; // never free storage of an unproven submission
                if (commands != null) { ObjC.release(commands); commands = null; }
                for (Buffer buffer : owned) if (buffer != null) ObjC.release(buffer.id());
                java.util.Arrays.fill(owned, null);
            }
        }
    }

    /** Waits (up to the batch timeout) for the submissions still running, so that their storage can be freed. */
    private void drain() {
        long deadline = System.nanoTime() + FusedNoiseDevice.FENCE_TIMEOUT_NANOS;
        for (Program program : programs) {
            while (program.quarantined() && System.nanoTime() < deadline) {
                try { Thread.sleep(1); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!lost) drain();
        boolean quarantined = false;
        for (Program program : programs) {
            try {
                program.close();
            } catch (RuntimeException ignored) {
                // keep tearing down the remaining objects
            }
            quarantined |= program.quarantined();
        }
        programs.clear();
        if (quarantined) {
            // A device cannot be released under work it may still be doing; the process keeps it until it exits.
            System.out.println("[tellurium-fast] GPU device not released: a batch was never proven finished (" + lostReason + ")");
            return;
        }
        ObjC.release(queue);
        ObjC.release(device);
    }
}
