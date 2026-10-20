// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalKernelSourceTest {
    /**
     * The SPIR-V of one emulated kernel (K_HEIGHT of a fixture router, which calls no helper) and the Metal
     * source MoltenVK 1.4.2 made of it without argument buffers, both from MoltenVK's MVK_CONFIG_SHADER_DUMP_DIR on
     * an Apple M3 (MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0).  The
     * conversion here has to give the same text, resource layout included, or MoltenVK would bind the wrong
     * buffers to a kernel handed over as Metal source.
     */
    @Test
    void aKernelWithoutHelpersIsWhatMoltenVkMakesOfItCharacterForCharacter() throws IOException {
        byte[] spirv = resource("moltenvk-1.4.2-k_height-direct.spv");
        String moltenVk = new String(resource("moltenvk-1.4.2-k_height-direct.metal"), StandardCharsets.UTF_8);
        assertEquals(moltenVk, MetalKernelSource.msl(spirv));
    }

    @Test
    void theTestsRunOnAnLwjglThatCanConvert() {
        assertEquals(null, MetalKernelSource.unsupportedReason());
    }

    @Test
    void theModuleIsTheMarkerFollowedByTheTerminatedSourceInWholeWords() throws IOException {
        byte[] spirv = resource("moltenvk-1.4.2-k_height-direct.spv");
        byte[] code = MetalKernelSource.moduleCode(spirv);
        assertEquals(0, code.length % 4);
        assertEquals(MetalKernelSource.MSL_SOURCE_MAGIC, ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        String text = new String(code, 4, code.length - 4, StandardCharsets.UTF_8);
        int end = text.indexOf('\0');
        assertEquals(MetalKernelSource.msl(spirv), text.substring(0, end));
        assertTrue(text.substring(end).chars().allMatch(c -> c == 0), "only NUL padding after the source");
    }

    @Test
    void onlyTheLargeIntegerHelpersBecomeCalls() {
        String msl = """
                static inline __attribute__((always_inline))
                uint2 wg_fp64_add(thread const uint2& left, thread const uint2& right)
                static inline __attribute__((always_inline))
                int wgf_fp64_to_i32_java(thread const uint2& d)
                static inline __attribute__((always_inline))
                uint2 jdiv(thread const uint2& a, thread const uint2& b)
                static inline __attribute__((always_inline))
                bool wg_fp64_less(thread const uint2& left, thread const uint2& right)
                """;
        String result = MetalKernelSource.callHelpers(msl);
        assertTrue(result.contains("__attribute__((noinline))\nuint2 wg_fp64_add("));
        assertTrue(result.contains("__attribute__((noinline))\nint wgf_fp64_to_i32_java("));
        assertTrue(result.contains("static inline __attribute__((always_inline))\nuint2 jdiv("), "library functions stay inlined");
        assertTrue(result.contains("static inline __attribute__((always_inline))\nbool wg_fp64_less("), "small helpers stay inlined");
        assertFalse(result.contains("always_inline))\nuint2 wg_fp64_add("));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MetalKernelSourceTest.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("test resource missing: " + name);
            return in.readAllBytes();
        }
    }
}
