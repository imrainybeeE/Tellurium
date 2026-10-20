// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import static org.lwjgl.util.spvc.Spvc.*;

/**
 * The emulated (GPU_IEEE_BITS) kernels as Metal source, for MoltenVK.
 *
 * <p>MoltenVK turns SPIR-V into Metal source with SPIRV-Cross, which declares every function
 * {@code static inline __attribute__((always_inline))} whatever the SPIR-V asks.  The emulated kernels call
 * the integer IEEE helpers from thousands of places, so Metal's compiler then builds one enormous function:
 * one kernel took 1,062 s to become a pipeline on an Apple M3.  This class runs the same conversion
 * (SPIRV-Cross with MoltenVK's settings for this pipeline layout), turns the helpers ({@code wg_}, {@code wgf_})
 * into real calls, and hands MoltenVK the Metal source directly (its MSL-source shader module, recognised by
 * {@link #MSL_SOURCE_MAGIC}); the same kernel then builds in under a second.  Only the inlining changes: for a
 * kernel without helpers the text is what MoltenVK generates itself, character for character.</p>
 *
 * <p>The resource layout copies MoltenVK 1.4's for one descriptor set of {@link FusedNoiseDevice#BINDINGS}
 * storage buffers and 8 bytes of push constants without Metal argument buffers: the push constants at buffer
 * index 0 and binding {@code n} at buffer index {@code n + 1}, each buffer bound directly.  The device's
 * instance turns MoltenVK's argument buffers off for this ({@link #ARGUMENT_BUFFERS_SETTING}): a kernel handed
 * over as Metal source carries no reflection data, and through an argument buffer MoltenVK then never made the
 * kernel's buffers resident, so every kernel read zeros and wrote nothing (seen on an Apple M3).</p>
 *
 * <p>The native Metal backend (runtime-metal) compiles the same source with the same layout, and dispatches it with the
 * 64-thread groups the kernels declare.</p>
 */
public final class MetalKernelSource {
    /** MoltenVK's marker for a shader module whose code is Metal source text instead of SPIR-V. */
    static final int MSL_SOURCE_MAGIC = 0x19960412;
    /** SPIRV-Cross names the entry point main0 ("main" is not a legal Metal function name). */
    public static final String ENTRY_POINT = "main0";
    static final int PUSH_CONSTANT_BUFFER_INDEX = 0;
    /** The MoltenVK setting (VK_EXT_layer_settings, layer "MoltenVK") that this layout needs at 0: never. */
    static final String ARGUMENT_BUFFERS_SETTING = "MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS";
    private static final int MSL_VERSION = 30100;
    private static final int EXECUTION_MODEL_GL_COMPUTE = 5;
    private static final Pattern INLINED_HELPER = Pattern.compile("static inline __attribute__\\(\\(always_inline\\)\\)\\n(\\S+ wgf?_)");

    private MetalKernelSource() {}

    /**
     * Null when this LWJGL can convert, else why not.  The conversion is checked against MoltenVK 1.4.2 as LWJGL
     * 3.4.1 ships it; an older SPIRV-Cross (LWJGL 3.3.3's) writes the kernels' signed modulo as Metal's %, which
     * differs for negative operands, so it is refused rather than trusted.
     */
    public static String unsupportedReason() {
        // Read at run time: Version's int fields are compile-time constants, fixed to the LWJGL compiled against.
        String running = org.lwjgl.Version.getVersion();
        java.util.regex.Matcher number = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(running);
        int version = number.find() ? Integer.parseInt(number.group(1)) * 10000 + Integer.parseInt(number.group(2)) * 100
                + Integer.parseInt(number.group(3)) : 0;
        if (version < 30401) return "the emulated kernels on MoltenVK need LWJGL 3.4.1 or later; this build bundles " + running;
        try {
            Class.forName("org.lwjgl.util.spvc.Spvc", false, MetalKernelSource.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return "the emulated kernels on MoltenVK need SPIRV-Cross (lwjgl-spvc), which this build does not bundle";
        }
        return null;
    }

    /** The bytes of a MoltenVK shader module holding the kernel's Metal source. */
    static byte[] moduleCode(byte[] spirv) {
        byte[] text = (msl(spirv) + "\0").getBytes(StandardCharsets.UTF_8);
        ByteBuffer code = ByteBuffer.allocate((4 + text.length + 3) & ~3).order(ByteOrder.LITTLE_ENDIAN);
        code.putInt(MSL_SOURCE_MAGIC).put(text);
        return code.array();
    }

    /** The kernel as MoltenVK would write it in Metal, with the integer helpers left as calls. */
    public static String msl(byte[] spirv) {
        return callHelpers(convert(spirv));
    }

    private static String convert(byte[] spirv) {
        if (spirv.length == 0 || (spirv.length & 3) != 0) throw new IllegalArgumentException("SPIR-V must be whole words");
        IntBuffer words = MemoryUtil.memAllocInt(spirv.length / 4);
        long context = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            words.put(ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()).flip();
            PointerBuffer out = stack.mallocPointer(1);
            check(spvc_context_create(out), "context");
            context = out.get(0);
            check(spvc_context_parse_spirv(context, words, words.remaining(), out), "parse");
            long ir = out.get(0);
            check(spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out), "compiler");
            long compiler = out.get(0);
            check(spvc_compiler_create_compiler_options(compiler, out), "options");
            long options = out.get(0);
            check(spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION), "MSL version");
            check(spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS), "platform");
            check(spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, false), "argument buffers");
            check(spvc_compiler_install_compiler_options(compiler, options), "install options");
            for (int binding = 0; binding < FusedNoiseDevice.BINDINGS; binding++) {
                add(compiler, stack, 0, binding, binding + 1);
            }
            add(compiler, stack, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, PUSH_CONSTANT_BUFFER_INDEX);
            check(spvc_compiler_compile(compiler, out), "compile");
            return MemoryUtil.memUTF8(out.get(0));
        } finally {
            if (context != 0) spvc_context_destroy(context);
            MemoryUtil.memFree(words);
        }
    }

    private static void add(long compiler, MemoryStack stack, int set, int binding, int index) {
        SpvcMslResourceBinding resource = SpvcMslResourceBinding.calloc(stack);
        spvc_msl_resource_binding_init(resource);
        resource.stage(EXECUTION_MODEL_GL_COMPUTE).desc_set(set).binding(binding).msl_buffer(index);
        check(spvc_compiler_msl_add_resource_binding(compiler, resource), "resource binding " + set + "/" + binding);
    }

    /** The integer IEEE helpers become real calls; everything else keeps SPIRV-Cross's forced inlining. */
    static String callHelpers(String msl) {
        return INLINED_HELPER.matcher(msl).replaceAll("__attribute__((noinline))\n$1");
    }

    private static void check(int result, String step) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("SPIRV-Cross failed (" + step + "): result " + result);
    }
}
