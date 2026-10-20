// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.runtime.vulkan.fused.FusedDispatchPlan.SlotBuffer;
import dev.tellurium.semantic.program.NumericProfile;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Persistent Vulkan device for the fused NOISE kernels.  One device, one
 * compute queue, one compiled program per captured router, and a fixed ring
 * of batch slots.  Each slot owns all of its buffers; a slot is reused only
 * after its fence signalled.  A failed or timed-out fence marks the device
 * lost: no buffer of an unproven submission is ever reused or freed.
 */
public final class FusedNoiseDevice implements dev.tellurium.compiler.vulkan.fused.FusedGpuBackend {
    static final int BINDINGS = FusedDispatchPlan.BINDINGS;
    private static final int KERNEL_COUNT = FusedDispatchPlan.KERNEL_COUNT;
    /** Diagnostic: keep intermediate buffers host-visible so they can be compared with a CPU reference. */
    public static final boolean DEBUG_BUFFERS = Boolean.getBoolean("tellurium.fast.debugBuffers");
    public static final boolean PROFILE = Boolean.getBoolean("tellurium.fast.profile");
    private static final boolean LOG_KERNELS = Boolean.getBoolean("tellurium.fast.logKernels");
    private static final java.util.concurrent.atomic.AtomicLong[] PROFILE_NANOS = new java.util.concurrent.atomic.AtomicLong[KERNEL_COUNT];
    private static final java.util.concurrent.atomic.AtomicLong PROFILE_CHUNKS = new java.util.concurrent.atomic.AtomicLong();
    static { for (int i = 0; i < KERNEL_COUNT; i++) PROFILE_NANOS[i] = new java.util.concurrent.atomic.AtomicLong(); }

    public static String summary() {
        long chunks = Math.max(1, PROFILE_CHUNKS.get());
        StringBuilder out = new StringBuilder("chunks=" + PROFILE_CHUNKS.get());
        for (int i = 0; i < KERNEL_COUNT; i++) out.append(' ').append(FusedDispatchPlan.kernelName(i)).append('=')
                .append(String.format(java.util.Locale.ROOT, "%.3f", PROFILE_NANOS[i].get() / 1e6 / chunks)).append("ms/chunk");
        return out.toString();
    }

    private final VkInstance instance;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final VkQueue queue;
    private final int queueFamily;
    private final String deviceName;
    private final NumericProfile numericProfile;
    /** MoltenVK runs the emulated kernels from Metal source with the integer helpers as calls (MetalKernelSource). */
    private final boolean metalSource;
    private final long commandPool;
    private final VkPhysicalDeviceMemoryProperties memoryProperties;
    private volatile boolean lost;
    private volatile String lostReason = "";
    private final List<Program> programs = new ArrayList<>();
    private boolean closed;
    /**
     * Vulkan wants a command pool, and every command buffer from it, used by one thread at a time: slots of a
     * program being loaded allocate from the pool while the GPU worker records into buffers of another.
     */
    private final Object poolLock = new Object();
    /** The queue is used by one thread at a time too (submissions, and waiting for it to drain at close). */
    private final Object queueLock = new Object();
    /** A submission whose fence has not signalled after this long is taken as never going to. */
    public static final long FENCE_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(
            Math.max(1, Long.getLong("tellurium.fast.fenceTimeoutSeconds", 60)));
    /** Developer fault injection: from this many submissions on, a fence is reported as never signalling. */
    private static final long STALL_AFTER = Long.getLong("tellurium.fast.stallFenceAfterSubmissions", 0);
    private final java.util.concurrent.atomic.AtomicLong submissions = new java.util.concurrent.atomic.AtomicLong();

    /** Whether a fence that has been pending since {@code submittedNanos} has run out of time. */
    public static boolean overdue(long submittedNanos, long nowNanos, long timeoutNanos) {
        return nowNanos - submittedNanos >= timeoutNanos;
    }

    private FusedNoiseDevice(VkInstance instance, VkPhysicalDevice physical, VkDevice device, VkQueue queue,
                             int queueFamily, String deviceName, NumericProfile numericProfile, boolean metalSource, long commandPool,
                             VkPhysicalDeviceMemoryProperties memoryProperties) {
        this.instance = instance;
        this.physical = physical;
        this.device = device;
        this.queue = queue;
        this.queueFamily = queueFamily;
        this.deviceName = deviceName;
        this.numericProfile = numericProfile;
        this.metalSource = metalSource;
        this.commandPool = commandPool;
        this.memoryProperties = memoryProperties;
    }

    @Override public String deviceName() { return deviceName; }
    @Override public NumericProfile numericProfile() { return numericProfile; }
    @Override public boolean profiling() { return PROFILE; }
    @Override public boolean debugBuffers() { return DEBUG_BUFFERS; }
    @Override public String profileSummary() { return summary(); }

    /**
     * Entry point for an isolated class loader: gives this loader's LWJGL its
     * own native extraction directory so it cannot collide with another LWJGL
     * copy in the same process, then opens the device.
     */
    public static dev.tellurium.compiler.vulkan.fused.FusedGpuBackend openIsolated(String nativeDirectory) {
        if (nativeDirectory != null && !nativeDirectory.isBlank()) {
            org.lwjgl.system.Configuration.SHARED_LIBRARY_EXTRACT_PATH.set(nativeDirectory);
        }
        // This LWJGL copy is private to the isolated loader, so its thread-local stack
        // size can be set here instead of requiring a JVM flag (the default 64 KiB is
        // too small for the descriptor and pipeline setup).  Value is in KiB.
        org.lwjgl.system.Configuration.STACK_SIZE.set(16384);
        var metal = openMetal();
        return metal != null ? metal : open();
    }

    /** The native Metal backend's class (runtime-metal), looked up by name: this module does not depend on it. */
    static final String METAL_BACKEND_CLASS = "dev.tellurium.runtime.metal.MetalFusedDevice";
    /** System property choosing the backend on macOS: unset or anything but "vulkan" is the native Metal one, "vulkan" is MoltenVK. */
    static final String BACKEND_PROPERTY = "tellurium.fast.backend";

    /**
     * Whether {@code openIsolated} runs the native Metal backend: on macOS, when the runtime carries it and the
     * backend property does not ask for Vulkan.
     */
    static boolean wantsMetal(String osName, boolean metalPresent, String backendProperty) {
        return osName != null && osName.toLowerCase(java.util.Locale.ROOT).startsWith("mac") && metalPresent
                && !"vulkan".equalsIgnoreCase(backendProperty == null ? "" : backendProperty.trim());
    }

    /**
     * The Metal backend, or null when it is not to be used.  When it is wanted and fails to open the failure is
     * thrown, with its reason: falling back to MoltenVK would run the same kernels at a fraction of the speed without
     * anyone seeing why.
     */
    private static dev.tellurium.compiler.vulkan.fused.FusedGpuBackend openMetal() {
        Class<?> metal;
        try {
            metal = Class.forName(METAL_BACKEND_CLASS, true, FusedNoiseDevice.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            metal = null;
        }
        if (!wantsMetal(System.getProperty("os.name"), metal != null, System.getProperty(BACKEND_PROPERTY))) return null;
        try {
            return (dev.tellurium.compiler.vulkan.fused.FusedGpuBackend) metal.getMethod("open").invoke(null);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("The Metal backend could not be opened (-D" + BACKEND_PROPERTY
                    + "=vulkan runs the MoltenVK one instead): " + cause, cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The Metal backend is present but unusable: " + failure, failure);
        }
    }
    /** Raw device handle for developer probes in this package. */
    VkDevice vkDevice() { return device; }
    @Override public boolean lost() { return lost; }
    @Override public String lostReason() { return lostReason; }
    @Override public void loseDeviceForTest(String reason) { lostReason = reason; lost = true; }

    /** System property choosing the arithmetic: auto (the GPU's own fp64 when it has it), native, or ieee (always emulated). */
    static final String NUMERIC_PROPERTY = "tellurium.fast.numeric";

    /**
     * auto: native arithmetic on a GPU with shaderFloat64, emulated in integers otherwise; native: only a GPU with
     * shaderFloat64; ieee: emulated even where the GPU has fp64 (to compare the two dialects on one device).
     */
    enum NumericMode {
        AUTO, NATIVE, IEEE;

        /** An unset or blank value is auto; anything else that is not one of the three names is an error, not a guess. */
        static NumericMode parse(String value) {
            if (value == null || value.isBlank()) return AUTO;
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> AUTO;
                case "native" -> NATIVE;
                case "ieee" -> IEEE;
                default -> throw new IllegalStateException(NUMERIC_PROPERTY + " must be auto, native or ieee, not '" + value.trim() + "'");
            };
        }
    }

    /** What device selection needs to know about one physical device. */
    record Candidate(boolean shaderInt64, boolean shaderFloat64, boolean hasComputeQueue, int score) {}

    /** The chosen device (an index into the candidates) and the arithmetic its kernels use. */
    record Selection(int index, NumericProfile profile) {}

    /**
     * Picks the device and the numeric profile.  A candidate needs shaderInt64, a compute queue and a score above
     * 0 (a real GPU: discrete 3, integrated 2).  Unless the mode is ieee, the devices with shaderFloat64 are
     * considered first, exactly as before the emulated profile existed, so a machine with such a device runs the
     * native kernels on the same device as it did; only when none qualifies (and never in native mode) does the
     * best device with shaderInt64 alone run the emulated ones.  Among equals the best score wins, then the
     * earlier device.  The profile is NATIVE_QUALIFIED when the chosen device has shaderFloat64 (and the mode is
     * not ieee), GPU_IEEE_BITS otherwise.
     */
    static Selection select(List<Candidate> candidates, NumericMode mode) {
        int best = mode == NumericMode.IEEE ? -1 : best(candidates, true);
        if (best < 0 && mode != NumericMode.NATIVE) best = best(candidates, false);
        if (best < 0) {
            throw new IllegalStateException(mode == NumericMode.NATIVE ? "No Vulkan GPU with shaderFloat64 and shaderInt64"
                    : "No Vulkan GPU with shaderInt64");
        }
        boolean native64 = mode != NumericMode.IEEE && candidates.get(best).shaderFloat64();
        return new Selection(best, native64 ? NumericProfile.NATIVE_QUALIFIED : NumericProfile.GPU_IEEE_BITS);
    }

    private static int best(List<Candidate> candidates, boolean needFloat64) {
        int best = -1;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!candidate.shaderInt64() || !candidate.hasComputeQueue() || candidate.score() <= 0) continue;
            if (needFloat64 && !candidate.shaderFloat64()) continue;
            if (best < 0 || candidate.score() > candidates.get(best).score()) best = i;
        }
        return best;
    }

    /** A program of the other dialect would run the wrong arithmetic (or fail on a missing feature): refuse it. */
    public static void requireDialect(NumericProfile deviceProfile, FusedNoiseCompiler.Dialect dialect) {
        if (dialect.profile() != deviceProfile) {
            throw new IllegalArgumentException("Kernels compiled for the " + dialect + " dialect (" + dialect.profile()
                    + ") cannot run on a device with the " + deviceProfile + " numeric profile");
        }
    }

    /** The emulated dialect has no floating-point type in its kernels; shaderc must not have introduced one. */
    public static void requireNoFloatTypes(byte[] spirv, String kernel) {
        try {
            SpirvNumericContract.requireSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Fused kernel " + kernel + " does not satisfy the integer-only arithmetic contract: "
                    + failure.getMessage(), failure);
        }
    }

    /** Pipeline cache file name; the emulated dialect's caches are kept apart from the native ones. */
    static String cacheFileName(NumericProfile profile, String fingerprint) {
        return (profile == NumericProfile.GPU_IEEE_BITS ? "fused-ieee-" : "fused-") + fingerprint.substring(0, 32) + ".vkcache";
    }

    public static FusedNoiseDevice open() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Tellurium")).apiVersion(VK12.VK_API_VERSION_1_2);
            var offered = dev.tellurium.runtime.vulkan.VulkanPortability.instanceExtensionNames(stack);
            var portabilityInstance = dev.tellurium.runtime.vulkan.VulkanPortability.instanceExtensionsToEnable(stack, offered);
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                    .flags(dev.tellurium.runtime.vulkan.VulkanPortability.instanceFlags(portabilityInstance))
                    .ppEnabledExtensionNames(portabilityInstance);
            if (offered.contains(dev.tellurium.runtime.vulkan.VulkanPortability.LAYER_SETTINGS_EXTENSION)) {
                // MoltenVK: no Metal argument buffers in this instance, the layout MetalKernelSource writes for.
                var extensions = stack.mallocPointer((portabilityInstance == null ? 0 : portabilityInstance.remaining()) + 1);
                if (portabilityInstance != null) extensions.put(portabilityInstance);
                extensions.put(stack.UTF8(dev.tellurium.runtime.vulkan.VulkanPortability.LAYER_SETTINGS_EXTENSION)).flip();
                instanceInfo.ppEnabledExtensionNames(extensions)
                        .pNext(dev.tellurium.runtime.vulkan.VulkanPortability.moltenVkSetting(stack, MetalKernelSource.ARGUMENT_BUFFERS_SETTING, 0));
            }
            PointerBuffer pointer = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance");
            VkInstance instance = new VkInstance(pointer.get(0), instanceInfo);
            try {
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
                NumericMode numericMode = NumericMode.parse(System.getProperty(NUMERIC_PROPERTY));
                VkPhysicalDevice[] physicalDevices = new VkPhysicalDevice[devices.capacity()];
                String[] names = new String[devices.capacity()];
                int[] families = new int[devices.capacity()];
                List<Candidate> candidates = new ArrayList<>();
                for (int i = 0; i < devices.capacity(); i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                    var props = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(candidate, props);
                    var features = VkPhysicalDeviceFeatures.calloc(stack);
                    vkGetPhysicalDeviceFeatures(candidate, features);
                    physicalDevices[i] = candidate;
                    names[i] = props.deviceNameString();
                    families[i] = computeFamily(candidate, stack);
                    int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3
                            : props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 0;
                    candidates.add(new Candidate(features.shaderInt64(), features.shaderFloat64(), families[i] >= 0, score));
                }
                Selection selection = select(candidates, numericMode);
                VkPhysicalDevice best = physicalDevices[selection.index()];
                int bestFamily = families[selection.index()];
                String bestName = names[selection.index()];
                NumericProfile profile = selection.profile();
                var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(bestFamily).pQueuePriorities(stack.floats(1.0f));
                // shaderFloat64 only for the native dialect: the emulated one has no double in its kernels at all.
                var enabled = VkPhysicalDeviceFeatures.calloc(stack).shaderFloat64(profile == NumericProfile.NATIVE_QUALIFIED).shaderInt64(true);
                PointerBuffer deviceExtensions = dev.tellurium.runtime.vulkan.VulkanPortability.deviceExtensionsToEnable(best, stack);
                // A portability (MoltenVK) device builds the emulated kernels from Metal source: from SPIR-V, Metal's
                // compiler inlines every integer helper and takes many minutes per kernel.
                boolean metalSource = profile == NumericProfile.GPU_IEEE_BITS && deviceExtensions != null
                        && !"false".equalsIgnoreCase(System.getProperty("tellurium.fast.metalSource", "").trim());
                if (metalSource && MetalKernelSource.unsupportedReason() != null) throw new IllegalStateException(MetalKernelSource.unsupportedReason());
                var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues).pEnabledFeatures(enabled)
                        .ppEnabledExtensionNames(deviceExtensions);
                check(vkCreateDevice(best, deviceInfo, null, pointer), "vkCreateDevice");
                VkDevice device = new VkDevice(pointer.get(0), best, deviceInfo);
                long pool = VK_NULL_HANDLE;
                VkPhysicalDeviceMemoryProperties memory = null;
                try {
                    vkGetDeviceQueue(device, bestFamily, 0, pointer);
                    VkQueue queue = new VkQueue(pointer.get(0), device);
                    var poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(bestFamily);
                    LongBuffer handle = stack.mallocLong(1);
                    check(vkCreateCommandPool(device, poolInfo, null, handle), "vkCreateCommandPool");
                    pool = handle.get(0);
                    memory = VkPhysicalDeviceMemoryProperties.malloc();
                    vkGetPhysicalDeviceMemoryProperties(best, memory);
                    return new FusedNoiseDevice(instance, best, device, queue, bestFamily, bestName, profile, metalSource, pool, memory);
                } catch (Throwable failure) {
                    if (memory != null) memory.free();
                    if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, pool, null);
                    vkDestroyDevice(device, null);
                    throw failure;
                }
            } catch (Throwable failure) {
                vkDestroyInstance(instance, null);
                throw failure;
            }
        }
    }

    private static int computeFamily(VkPhysicalDevice candidate, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
        var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0 && families.get(i).queueCount() > 0) return i;
        }
        return -1;
    }

    /** Compiles all kernels of one fused program and allocates its batch slots. */
    @Override
    public Program load(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
        if (maxBatchChunks <= 0 || slotCount <= 0) throw new IllegalArgumentException("Batch size and slot count must be positive");
        requireDialect(numericProfile, compiled.dialect());
        synchronized (this) {
            if (closed) throw new IllegalStateException("Fused NOISE device is closed");
            if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
        }
        // Building the kernels can take seconds, or minutes on a cold driver cache; a program that is already
        // loaded keeps generating meanwhile, so the device's monitor is not held here.
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

    // ---------------------------------------------------------------- buffers
    final class Buffer {
        final long buffer;
        final long memory;
        final long size;
        final ByteBuffer mapped;

        Buffer(long size, boolean hostVisible) {
            this.size = FusedDispatchPlan.allocationBytes(size);
            long createdBuffer = VK_NULL_HANDLE, createdMemory = VK_NULL_HANDLE;
            ByteBuffer view = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(this.size)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateBuffer(device, info, null, handle), "vkCreateBuffer");
                createdBuffer = handle.get(0);
                var requirements = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, createdBuffer, requirements);
                int type = hostVisible
                        ? memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                        : memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
                var allocate = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(requirements.size()).memoryTypeIndex(type);
                check(vkAllocateMemory(device, allocate, null, handle), "vkAllocateMemory(" + this.size + ")");
                createdMemory = handle.get(0);
                check(vkBindBufferMemory(device, createdBuffer, createdMemory, 0), "vkBindBufferMemory");
                if (hostVisible) {
                    PointerBuffer data = stack.mallocPointer(1);
                    check(vkMapMemory(device, createdMemory, 0, this.size, 0, data), "vkMapMemory");
                    view = MemoryUtil.memByteBuffer(data.get(0), (int) this.size).order(ByteOrder.LITTLE_ENDIAN);
                }
            } catch (Throwable failure) {
                // Nothing of a buffer that could not be finished is kept (freeing memory unmaps it).
                if (createdBuffer != VK_NULL_HANDLE) vkDestroyBuffer(device, createdBuffer, null);
                if (createdMemory != VK_NULL_HANDLE) vkFreeMemory(device, createdMemory, null);
                throw failure;
            }
            buffer = createdBuffer;
            memory = createdMemory;
            mapped = view;
        }

        void destroy() {
            if (mapped != null) vkUnmapMemory(device, memory);
            vkDestroyBuffer(device, buffer, null);
            vkFreeMemory(device, memory, null);
        }
    }

    private int memoryType(int typeBits, int required, int preferred) {
        int fallback = -1;
        for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) == 0) continue;
            int flags = memoryProperties.memoryTypes(i).propertyFlags();
            if ((flags & required) != required) continue;
            if ((flags & preferred) == preferred) return i;
            if (fallback < 0) fallback = i;
        }
        if (fallback < 0) throw new IllegalStateException("No Vulkan memory type for flags " + required);
        return fallback;
    }

    // ---------------------------------------------------------------- program
    public final class Program implements AutoCloseable, dev.tellurium.compiler.vulkan.fused.FusedGpuBackend.Program {
        public final FusedNoiseCompiler.Compiled compiled;
        public final int maxBatchChunks;
        // Zero or null until created, so that a program that could not be finished is taken apart by the same code as a whole one.
        private long descriptorSetLayout;
        private long pipelineLayout;
        private final long[] pipelines = new long[KERNEL_COUNT];
        private final long[] modules = new long[KERNEL_COUNT];
        private long descriptorPool;
        private Buffer permBuffer;
        private Buffer dtabBuffer;
        private final Slot[] slots;
        public final long compileNanos;
        private boolean destroyed;

        Program(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount) {
            this.compiled = compiled;
            this.maxBatchChunks = maxBatchChunks;
            this.slots = new Slot[slotCount];
            long start = System.nanoTime();
            try {
                build(compiled, slotCount);
            } catch (Throwable failure) {
                destroyAll();
                throw failure;
            }
            compileNanos = System.nanoTime() - start;
        }

        private void build(FusedNoiseCompiler.Compiled compiled, int slotCount) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var bindings = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
                for (int i = 0; i < BINDINGS; i++) {
                    bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                }
                LongBuffer handle = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                        .pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
                descriptorSetLayout = handle.get(0);
                var push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
                check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
                pipelineLayout = handle.get(0);
                long pipelineCache = createPipelineCache(stack, compiled.fingerprint());
                try {
                for (int k = 0; k < KERNEL_COUNT; k++) {
                    long kernelStart = System.nanoTime();
                    byte[] spirv = compileGlsl(compiled.source(), FusedDispatchPlan.kernelName(k));
                    if (numericProfile == NumericProfile.GPU_IEEE_BITS) requireNoFloatTypes(spirv, FusedDispatchPlan.kernelName(k));
                    byte[] moduleCode = metalSource ? MetalKernelSource.moduleCode(spirv) : spirv;
                    long shadercNanos = System.nanoTime() - kernelStart;
                    ByteBuffer code = MemoryUtil.memAlloc(moduleCode.length);
                    try {
                        code.put(moduleCode).flip();
                        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle),
                                "vkCreateShaderModule(" + FusedDispatchPlan.kernelName(k) + ")");
                    } finally {
                        MemoryUtil.memFree(code);
                    }
                    modules[k] = handle.get(0);
                    var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(modules[k]).pName(stack.UTF8(metalSource ? MetalKernelSource.ENTRY_POINT : "main"));
                    var info = VkComputePipelineCreateInfo.calloc(1, stack);
                    info.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                    check(vkCreateComputePipelines(device, pipelineCache, info, null, handle), "vkCreateComputePipelines(" + FusedDispatchPlan.kernelName(k) + ")");
                    pipelines[k] = handle.get(0);
                    // Build times per kernel, for whoever is working on them (-Dtellurium.fast.logKernels=true).
                    if (LOG_KERNELS) System.out.println("[tellurium-fast] kernel " + FusedDispatchPlan.kernelName(k) + ": spirv " + spirv.length + " bytes, shaderc "
                            + shadercNanos / 1_000_000 + " ms, pipeline " + (System.nanoTime() - kernelStart - shadercNanos) / 1_000_000 + " ms");
                }
                savePipelineCache(pipelineCache, compiled.fingerprint());
                } finally {
                    vkDestroyPipelineCache(device, pipelineCache, null);
                }
                var sizes = VkDescriptorPoolSize.calloc(1, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(BINDINGS * slotCount);
                check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(slotCount).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
                descriptorPool = handle.get(0);
            }
            permBuffer = new Buffer(FusedDispatchPlan.permBytes(compiled), true);
            permBuffer.mapped.asIntBuffer().put(compiled.permTable());
            dtabBuffer = new Buffer(FusedDispatchPlan.doubleTableBytes(compiled), true);
            dtabBuffer.mapped.asDoubleBuffer().put(compiled.doubleTable());
            for (int i = 0; i < slotCount; i++) slots[i] = new Slot();
        }

        @Override public FusedNoiseCompiler.Compiled compiled() { return compiled; }
        @Override public long compileNanos() { return compileNanos; }
        @Override public int slotCount() { return slots.length; }
        @Override public Slot slot(int index) { return slots[index]; }

        @Override
        public synchronized void close() {
            destroyAll();
        }

        /** True when a submission of this program was never proven finished: nothing it may still use was freed. */
        synchronized boolean quarantined() {
            for (Slot slot : slots) if (slot != null && slot.pending) return true;
            return false;
        }

        /**
         * Frees what was created (a destroy call ignores a null handle).  A submission that was never proven
         * finished may still be running on the GPU, and it uses the pipelines, the descriptors and the shared
         * tables as well as its own slot: then nothing of the program is freed.
         */
        private synchronized void destroyAll() {
            if (destroyed || quarantined()) return;
            destroyed = true;
            for (Slot slot : slots) if (slot != null) slot.destroy();
            if (permBuffer != null) permBuffer.destroy();
            if (dtabBuffer != null) dtabBuffer.destroy();
            vkDestroyDescriptorPool(device, descriptorPool, null);
            for (int k = 0; k < KERNEL_COUNT; k++) {
                vkDestroyPipeline(device, pipelines[k], null);
                vkDestroyShaderModule(device, modules[k], null);
            }
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }

        /** One in-flight batch: all buffers are owned until its fence signals. */
        public final class Slot implements dev.tellurium.compiler.vulkan.fused.FusedGpuBackend.Slot {
            public static final int CHUNK_INFO_INTS = 8;
            private Buffer chunks, columns, corners, aquifer, blocks, beard, flags, heights, xzcache, prelimIndex, prelimCols, prelimOut;
            /** Block output is produced in device memory (the surface kernels rewrite it in place) and copied out once. */
            private Buffer blocksHost, biomes, surfTmp, aquiferHost;
            private final int prelimCapacity;
            private long descriptorSet;
            private VkCommandBuffer commands;
            private long fence;
            private volatile boolean pending;
            private int pendingChunks;
            private long submittedNanos;
            public final int beardCapacityInts;

            Slot() {
                beardCapacityInts = (int) FusedDispatchPlan.beardCapacityInts(maxBatchChunks);
                prelimCapacity = FusedDispatchPlan.prelimCapacity(maxBatchChunks);
                try {
                    build();
                } catch (Throwable failure) {
                    destroy();
                    throw failure;
                }
            }

            private void build() {
                chunks = slotBuffer(SlotBuffer.CHUNKS, true);
                columns = slotBuffer(SlotBuffer.COLUMNS, DEBUG_BUFFERS);
                corners = slotBuffer(SlotBuffer.CORNERS, DEBUG_BUFFERS);
                aquifer = slotBuffer(SlotBuffer.AQUIFER, false);
                blocks = slotBuffer(SlotBuffer.BLOCKS, false);
                blocksHost = slotBuffer(SlotBuffer.BLOCKS, true);
                aquiferHost = slotBuffer(SlotBuffer.AQUIFER, true);
                biomes = slotBuffer(SlotBuffer.BIOMES, true);
                surfTmp = slotBuffer(SlotBuffer.SURF_TMP, false);
                beard = slotBuffer(SlotBuffer.BEARD, true);
                flags = slotBuffer(SlotBuffer.FLAGS, true);
                heights = slotBuffer(SlotBuffer.HEIGHTS, true);
                prelimIndex = slotBuffer(SlotBuffer.PRELIM_INDEX, true);
                prelimCols = slotBuffer(SlotBuffer.PRELIM_COLUMNS, true);
                prelimOut = slotBuffer(SlotBuffer.PRELIM_OUT, false);
                xzcache = slotBuffer(SlotBuffer.XZCACHE, false);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer handle = stack.mallocLong(1);
                    check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout)), handle), "vkAllocateDescriptorSets");
                    descriptorSet = handle.get(0);
                    Buffer[] bound = new Buffer[BINDINGS];
                    bound[FusedDispatchPlan.PERM_BINDING] = permBuffer;
                    bound[FusedDispatchPlan.DTAB_BINDING] = dtabBuffer;
                    bound[SlotBuffer.CHUNKS.binding()] = chunks;
                    bound[SlotBuffer.COLUMNS.binding()] = columns;
                    bound[SlotBuffer.CORNERS.binding()] = corners;
                    bound[SlotBuffer.AQUIFER.binding()] = aquifer;
                    bound[SlotBuffer.BLOCKS.binding()] = blocks;
                    bound[SlotBuffer.BEARD.binding()] = beard;
                    bound[SlotBuffer.FLAGS.binding()] = flags;
                    bound[SlotBuffer.HEIGHTS.binding()] = heights;
                    bound[SlotBuffer.XZCACHE.binding()] = xzcache;
                    bound[SlotBuffer.PRELIM_INDEX.binding()] = prelimIndex;
                    bound[SlotBuffer.PRELIM_COLUMNS.binding()] = prelimCols;
                    bound[SlotBuffer.PRELIM_OUT.binding()] = prelimOut;
                    bound[SlotBuffer.BIOMES.binding()] = biomes;
                    bound[SlotBuffer.SURF_TMP.binding()] = surfTmp;
                    var infos = VkDescriptorBufferInfo.calloc(BINDINGS, stack);
                    var writes = VkWriteDescriptorSet.calloc(BINDINGS, stack);
                    for (int i = 0; i < BINDINGS; i++) {
                        infos.get(i).buffer(bound[i].buffer).offset(0).range(VK_WHOLE_SIZE);
                        writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .pBufferInfo(VkDescriptorBufferInfo.create(infos.get(i).address(), 1));
                    }
                    vkUpdateDescriptorSets(device, writes, null);
                    PointerBuffer pointer = stack.mallocPointer(1);
                    synchronized (poolLock) {
                        check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
                                "vkAllocateCommandBuffers");
                        commands = new VkCommandBuffer(pointer.get(0), device);
                    }
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
                    fence = handle.get(0);
                }
            }

            private Buffer slotBuffer(SlotBuffer which, boolean hostVisible) {
                return new Buffer(which.size(compiled, maxBatchChunks), hostVisible);
            }

            public boolean pending() { return pending; }
            public int pendingChunks() { return pendingChunks; }
            public ByteBuffer chunkInfo() { return chunks.mapped; }
            public ByteBuffer beardData() { return beard.mapped; }

            /** Records and submits all kernels for {@code count} chunks already written to the mapped inputs. */
            @Override public ByteBuffer prelimIndex() { return prelimIndex.mapped; }
            @Override public ByteBuffer prelimColumns() { return prelimCols.mapped; }
            @Override public int prelimColumnCapacity() { return prelimCapacity; }

            @Override public ByteBuffer biomes() { return biomes.mapped; }

            @Override
            public void submit(int count, int prelimCount, boolean surface) {
                if (prelimCount < 0 || prelimCount > prelimCapacity) throw new IllegalArgumentException("Invalid preliminary column count");
                if (pending) throw new IllegalStateException("Slot is still in flight");
                if (lost) throw new IllegalStateException("Fused NOISE device is lost: " + lostReason);
                if (count <= 0 || count > maxBatchChunks) throw new IllegalArgumentException("Invalid batch size " + count);
                for (int i = 0; i < count; i++) flags.mapped.putInt(i * 4, 0);
                long[] threads = FusedDispatchPlan.threads(compiled, count, prelimCount, surface);
                int[] order = FusedDispatchPlan.order(surface);
                if (PROFILE) {
                    // Diagnostic: one fenced submission per kernel so each kernel's wall time is visible.
                    for (int step = 0; step < order.length; step++) {
                        int k = order[step];
                        if (threads[k] == 0) continue;
                        long start = System.nanoTime();
                        record(count, prelimCount, threads, order, step, step + 1);
                        // record() marked the slot owned; a wait that fails leaves it so
                        int status = vkWaitForFences(device, new long[]{fence}, true, 60_000_000_000L);
                        if (status != VK_SUCCESS) { lostReason = "profile fence VkResult=" + status; lost = true; throw new IllegalStateException(lostReason); }
                        PROFILE_NANOS[k].addAndGet(System.nanoTime() - start);
                    }
                    PROFILE_CHUNKS.addAndGet(count);
                    return; // the slot is still marked owned; its fence has signalled, so poll() completes at once
                }
                record(count, prelimCount, threads, order, 0, order.length);
            }

            private void record(int count, int prelimCount, long[] threads, int[] order, int from, int to) {
                // Recording uses the pool the buffer came from (see poolLock).  The slot is marked owned as soon as
                // the queue has taken the submission, before anything can fail or time out after it.
                synchronized (poolLock) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    vkResetCommandBuffer(commands, 0);
                    check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
                    vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                            stack.longs(descriptorSet), null);
                    vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(count, prelimCount));
                    for (int step = from; step < to; step++) if (threads[order[step]] > 0) dispatch(stack, order[step], threads[order[step]]);
                    if (to == order.length) {
                        var toTransfer = VkMemoryBarrier.calloc(1, stack).sType$Default()
                                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                                toTransfer, null, null);
                        var region = VkBufferCopy.calloc(1, stack);
                        region.get(0).srcOffset(0).dstOffset(0).size((long) count * compiled.geometry().blockCount());
                        vkCmdCopyBuffer(commands, blocks.buffer, blocksHost.buffer, region);
                        if (compiledAquifers()) {
                            var cells = VkBufferCopy.calloc(1, stack);
                            cells.get(0).srcOffset(0).dstOffset(0).size((long) count * compiled.geometry().aquiferCellCount() * 8 * 4);
                            vkCmdCopyBuffer(commands, aquifer.buffer, aquiferHost.buffer, cells);
                        }
                        var copied = VkMemoryBarrier.calloc(1, stack).sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                                copied, null, null);
                    }
                    var hostBarrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                            hostBarrier, null, null);
                    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
                    check(vkResetFences(device, stack.longs(fence)), "vkResetFences");
                    var submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands));
                    int submitted;
                    synchronized (queueLock) {
                        submitted = vkQueueSubmit(queue, submit, fence);
                    }
                    if (submitted != VK_SUCCESS) {
                        // Vulkan's failures of a submission are the device being lost or out of memory.  Neither
                        // is something to retry batch after batch: the device is given up, so nothing more is
                        // submitted and every later chunk goes to the original code.  The slot is not marked
                        // owned (the queue did not take the work), and is not used again either.
                        lostReason = "vkQueueSubmit failed with VkResult=" + submitted;
                        lost = true;
                        throw new IllegalStateException(lostReason);
                    }
                    pending = true;
                    pendingChunks = count;
                    submittedNanos = System.nanoTime();
                    submissions.incrementAndGet();
                }
                }
            }

            private void dispatch(MemoryStack stack, int kernel, long threads) {
                vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[kernel]);
                long groups = FusedDispatchPlan.groups(threads);
                if (groups > 65535L * 65535L) throw new IllegalStateException("Dispatch too large");
                // A Metal-source module carries no SPIR-V for MoltenVK to read local_size_x = 64 from, so MoltenVK runs
                // it with one thread per threadgroup: dispatch one group per invocation.  Correct (the kernels use only
                // gl_GlobalInvocationID), but it leaves 31 of every 32 SIMD lanes idle.
                vkCmdDispatch(commands, (int) (metalSource ? groups * 64 : groups), 1, 1);
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                        barrier, null, null);
            }

            /** Waits for the fence; returns false on timeout (slot then stays owned and the device is lost). */
            public boolean await(long timeoutNanos) {
                if (!pending) return true;
                int status = vkWaitForFences(device, new long[]{fence}, true, timeoutNanos);
                if (status == VK_SUCCESS) {
                    pending = false;
                    return true;
                }
                lost = true;
                lostReason = "Fence wait returned VkResult=" + status;
                return false;
            }

            public boolean poll() {
                if (!pending) return true;
                boolean stalled = STALL_AFTER > 0 && submissions.get() >= STALL_AFTER;
                int status = stalled ? VK_NOT_READY : vkGetFenceStatus(device, fence);
                if (status == VK_SUCCESS) {
                    pending = false;
                    return true;
                }
                if (status != VK_NOT_READY) {
                    lostReason = "Fence status VkResult=" + status;
                    lost = true;
                } else if (!lost && overdue(submittedNanos, System.nanoTime(), FENCE_TIMEOUT_NANOS)) {
                    // A queue that neither finishes nor reports an error would keep the chunks of this batch waiting
                    // for ever.  The slot stays owned; the device is given up and the chunks go to the original code.
                    lostReason = "a batch did not finish within " + FENCE_TIMEOUT_NANOS / 1_000_000_000L + " s";
                    lost = true;
                }
                return false;
            }

            public ByteBuffer blocks() { return blocksHost.mapped; }
            public ByteBuffer debugColumns() { return columns.mapped; }
            public ByteBuffer debugCorners() { return corners.mapped; }
            public ByteBuffer heights() { return heights.mapped; }
            public ByteBuffer aquifers() { return aquiferHost.mapped; }
            public int flags(int chunk) { return flags.mapped.getInt(chunk * 4); }

            void destroy() {
                if (pending) return; // never free storage of an unproven submission
                vkDestroyFence(device, fence, null);
                fence = VK_NULL_HANDLE;
                // The descriptor set goes with the program's descriptor pool and the command buffer with the device's pool.
                for (Buffer b : new Buffer[]{chunks, columns, corners, aquifer, blocks, beard, flags, heights, xzcache, prelimIndex, prelimCols, prelimOut,
                        blocksHost, biomes, surfTmp, aquiferHost}) if (b != null) b.destroy();
            }
        }

        private boolean compiledAquifers() {
            return FusedDispatchPlan.aquifers(compiled);
        }

    }

    private java.nio.file.Path cacheFile(String fingerprint) {
        String dir = System.getProperty("tellurium.fast.pipelineCacheDir", "tellurium-cache").trim();
        if (dir.isEmpty() || dir.equals("NONE")) return null;
        return java.nio.file.Path.of(dir, cacheFileName(numericProfile, fingerprint));
    }

    private long createPipelineCache(MemoryStack stack, String fingerprint) {
        java.nio.file.Path file = cacheFile(fingerprint);
        byte[] initial = null;
        if (file != null && java.nio.file.Files.isRegularFile(file)) {
            try { initial = java.nio.file.Files.readAllBytes(file); } catch (java.io.IOException ignored) { initial = null; }
        }
        var info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
        ByteBuffer data = null;
        if (initial != null && initial.length > 0) {
            data = MemoryUtil.memAlloc(initial.length);
            data.put(initial).flip();
            info.pInitialData(data);
        }
        try {
            LongBuffer handle = stack.mallocLong(1);
            int result = vkCreatePipelineCache(device, info, null, handle);
            if (result != VK_SUCCESS && data != null) {
                // Stale or foreign cache data: start empty.
                info.pInitialData(null);
                result = vkCreatePipelineCache(device, info, null, handle);
            }
            check(result, "vkCreatePipelineCache");
            return handle.get(0);
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }

    private void savePipelineCache(long cache, String fingerprint) {
        java.nio.file.Path file = cacheFile(fingerprint);
        if (file == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer size = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(device, cache, size, null) != VK_SUCCESS) return;
            ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                if (vkGetPipelineCacheData(device, cache, size, data) != VK_SUCCESS) return;
                byte[] bytes = new byte[(int) size.get(0)];
                data.get(0, bytes);
                java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
                java.nio.file.Files.write(file, bytes);
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (java.io.IOException ignored) {
            // cache is an optimization only
        }
    }

    public static byte[] compileGlsl(String source, String kernel) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) throw new IllegalStateException("shaderc initialization failed");
        long options = shaderc_compile_options_initialize();
        long result = 0;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            shaderc_compile_options_add_macro_definition(options, kernel, "1");
            result = shaderc_compile_into_spv(compiler, source, shaderc_compute_shader, "tellurium-fused.comp", "main", options);
            if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                String message = result == 0 ? "no result" : shaderc_result_get_error_message(result);
                throw new IllegalStateException("Fused kernel " + kernel + " failed to compile: " + message);
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            String dontInline = System.getProperty("tellurium.fast.dontInline", "wgs_").trim();
            if (!dontInline.isEmpty() && !dontInline.equals("NONE")) {
                out = dev.tellurium.compiler.vulkan.worldgen.SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(out, dontInline);
            }
            return out;
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!lost) {
            synchronized (queueLock) {
                vkDeviceWaitIdle(device);
            }
        }
        boolean quarantined = false;
        for (Program program : programs) {
            try {
                program.close();
            } catch (RuntimeException ignored) {
                // keep tearing down the remaining handles
            }
            quarantined |= program.quarantined();
        }
        programs.clear();
        if (quarantined) {
            // A device cannot be destroyed under work it may still be doing; the process keeps it until it exits.
            System.out.println("[tellurium-fast] GPU device not released: a batch was never proven finished (" + lostReason + ")");
            return;
        }
        synchronized (poolLock) {
            vkDestroyCommandPool(device, commandPool, null);
        }
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        memoryProperties.free();
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + result);
    }
}
