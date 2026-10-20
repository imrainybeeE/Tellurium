// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.metal;

import dev.tellurium.compiler.vulkan.fused.FusedGpuBackend;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Compiled;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Dialect;
import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.Geometry;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Metal backend against the real device of a Mac: its lifecycle, its failure paths and what it dispatches. */
@EnabledOnOs(OS.MAC)
class MetalFusedDeviceTest {
    private static final Geometry GEOMETRY = new Geometry(-64, 384, 384, 4, 8, 4, 48, -8, 5, 49, -3);

    /**
     * A program whose kernels are small enough to check by hand and read the buffers the real ones do (flags is binding 8,
     * heights 9, push constants first): K_COLUMN adds the chunk's number to its (cleared) flag, K_BLOCK (dispatched after it) adds 1000, the surface
     * kernels add 10 and then double, K_HEIGHT marks the heights with the index in the workgroup, which shows its width.  The same source is every kernel; the macros pick the body.
     */
    private static final String SOURCE = """
            #version 450
            layout(local_size_x = 64) in;
            layout(std430, binding = 8) buffer Flags { uint flags[]; };
            layout(std430, binding = 9) buffer Heights { uint heights[]; };
            layout(push_constant) uniform Push { int count; int prelimCount; } push;
            void main() {
                uint g = gl_GlobalInvocationID.x;
                uint count = uint(push.count);
            #ifdef K_COLUMN
                if (g < count) flags[g] = flags[g] + g + 1u;
            #endif
            #ifdef K_BLOCK
                if (g < count) flags[g] = flags[g] + 1000u;
            #endif
            #ifdef K_SURF_A
                if (g < count) flags[g] = flags[g] + 10u;
            #endif
            #ifdef K_SURF_B
                if (g < count) flags[g] = flags[g] * 2u;
            #endif
            #ifdef K_HEIGHT
                if (g < count * 256u) heights[g] = (g * 7u) | (gl_LocalInvocationID.x << 24);
            #endif
            }
            """;

    private MetalFusedDevice device;

    private static Compiled compiled(Dialect dialect) {
        return new Compiled(SOURCE, new double[]{1.5, 2.5}, new int[]{3, 1, 2}, 1, 1, GEOMETRY, "f".repeat(64), List.of(), List.of(),
                "s", 0, 0, "x", dialect);
    }

    @BeforeEach void open() {
        assumeTrue(MetalFusedDevice.unavailableReason() == null, "no Metal device on this Mac");
        device = MetalFusedDevice.open();
    }

    @AfterEach void close() {
        if (device != null) device.close();
    }

    @Test void theDeviceIsTheIntegerEmulatingBackendAndNamesTheGpu() {
        assertEquals(NumericProfile.GPU_IEEE_BITS, device.numericProfile());
        assertFalse(device.deviceName().isBlank());
        assertFalse(device.lost());
    }

    @Test void aProgramOfTheNativeDialectIsRefusedBeforeAnythingIsBuilt() {
        var error = assertThrows(IllegalArgumentException.class, () -> device.load(compiled(Dialect.NATIVE_FP64), 4, 1));
        assertTrue(error.getMessage().contains("NATIVE_FP64"), error.getMessage());
    }

    @Test void batchAndSlotCountsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> device.load(compiled(Dialect.IEEE_BITS), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> device.load(compiled(Dialect.IEEE_BITS), 4, 0));
    }

    @Test void aClosedDeviceLoadsNothing() {
        device.close();
        assertThrows(IllegalStateException.class, () -> device.load(compiled(Dialect.IEEE_BITS), 4, 1));
    }

    @Test void aLostDeviceLoadsNothingAndNamesWhy() {
        device.loseDeviceForTest("fault injected");
        assertTrue(device.lost());
        var error = assertThrows(IllegalStateException.class, () -> device.load(compiled(Dialect.IEEE_BITS), 4, 1));
        assertTrue(error.getMessage().contains("fault injected"), error.getMessage());
    }

    @Test void aSourceMetalRejectsFailsWithMetalsOwnText() {
        var error = assertThrows(IllegalStateException.class,
                () -> device.newPipeline("kernel void main0(device uint* out [[buffer(0)]]) { out[0] = nowhere; }", "main0"));
        assertTrue(error.getMessage().contains("nowhere"), error.getMessage());
    }

    @Test void aSourceWithoutTheNamedFunctionIsAnError() {
        var error = assertThrows(IllegalStateException.class,
                () -> device.newPipeline("kernel void other(device uint* out [[buffer(0)]]) { out[0] = 1u; }", "main0"));
        assertTrue(error.getMessage().contains("main0"), error.getMessage());
    }

    @Test void aDispatchNeedsAtLeastOneGroupEach() {
        MemorySegment pipeline = device.newPipeline("kernel void main0(device uint* out [[buffer(1)]]) { out[0] = 1u; }", "main0");
        try {
            assertThrows(IllegalStateException.class, () -> device.commit(new int[]{0, 0}, new MemorySegment[0], new MemorySegment[]{pipeline}, new long[]{0}));
            assertThrows(IllegalArgumentException.class, () -> device.commit(new int[]{0, 0}, new MemorySegment[0], new MemorySegment[]{pipeline}, new long[]{1, 1}));
        } finally {
            ObjC.release(pipeline);
        }
    }

    @Test void kernelsRunInGroupsOf64AndEachDispatchSeesTheEarlierOnesWrites() throws InterruptedException {
        var program = device.load(compiled(Dialect.IEEE_BITS), 8, 2);
        assertEquals(2, program.slotCount());
        FusedGpuBackend.Slot slot = program.slot(0);
        for (boolean surface : new boolean[]{false, true}) {
            int count = 5;
            slot.submit(count, 0, surface);
            assertTrue(slot.pending());
            await(slot);
            assertFalse(slot.pending());
            assertFalse(device.lost(), device.lostReason());
            for (int chunk = 0; chunk < count; chunk++) {
                int expected = surface ? (chunk + 1 + 1000 + 10) * 2 : chunk + 1 + 1000;
                assertEquals(expected, slot.flags(chunk), "chunk " + chunk + " surface=" + surface);
            }
            ByteBuffer heights = slot.heights();
            for (int i = 0; i < count * 256; i++) assertEquals((i * 7) | ((i % 64) << 24), heights.getInt(i * 4), "height " + i + " (thread index in a group of 64 on top)");
        }
        program.close();
    }

    @Test void flagsAreClearedForTheBatchBeforeTheKernelsRun() throws InterruptedException {
        var program = device.load(compiled(Dialect.IEEE_BITS), 8, 1);
        FusedGpuBackend.Slot slot = program.slot(0);
        slot.submit(3, 0, false);
        await(slot);
        assertEquals(1001, slot.flags(0));
        assertEquals(1003, slot.flags(2));
        assertEquals(0, slot.flags(5), "a chunk beyond the count is not part of the batch");
        // The kernels add to the flag they find: a second batch in the same slot gets the same answer only if the flags were cleared.
        slot.submit(2, 0, false);
        await(slot);
        assertEquals(1001, slot.flags(0));
        assertEquals(1002, slot.flags(1));
        assertEquals(1003, slot.flags(2), "beyond the second batch's count: left as the first batch made it");
        program.close();
    }

    @Test void aSubmissionIsCheckedBeforeItIsHandedToTheGpu() throws InterruptedException {
        var program = device.load(compiled(Dialect.IEEE_BITS), 4, 1);
        FusedGpuBackend.Slot slot = program.slot(0);
        assertThrows(IllegalArgumentException.class, () -> slot.submit(0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> slot.submit(5, 0, false));
        assertThrows(IllegalArgumentException.class, () -> slot.submit(1, -1, false));
        assertThrows(IllegalArgumentException.class, () -> slot.submit(1, slot.prelimColumnCapacity() + 1, false));
        assertFalse(slot.pending());
        assertEquals(4 * 961, slot.prelimColumnCapacity());
        slot.submit(1, 0, false);
        assertThrows(IllegalStateException.class, () -> slot.submit(1, 0, false), "a slot in flight is not reused");
        await(slot);
        program.close();
    }

    @Test void aLostDeviceSubmitsNothingMore() {
        var program = device.load(compiled(Dialect.IEEE_BITS), 4, 1);
        device.loseDeviceForTest("driver reset");
        var error = assertThrows(IllegalStateException.class, () -> program.slot(0).submit(1, 0, false));
        assertTrue(error.getMessage().contains("driver reset"), error.getMessage());
        assertFalse(program.slot(0).pending());
    }

    @Test void theEngineReadsTheBuffersOfTheGpuInPlace() {
        var program = device.load(compiled(Dialect.IEEE_BITS), 4, 1);
        FusedGpuBackend.Slot slot = program.slot(0);
        assertSame(slot.blocks(), slot.blocks(), "the same bytes every time, not a copy");
        assertTrue(slot.blocks().capacity() >= 4 * GEOMETRY.blockCount());
        assertTrue(slot.aquifers().capacity() >= 4 * GEOMETRY.aquiferCellCount() * 32);
        assertTrue(slot.chunkInfo().capacity() >= 4 * FusedGpuBackend.CHUNK_INFO_INTS * 4);
        assertEquals(java.nio.ByteOrder.LITTLE_ENDIAN, slot.blocks().order());
    }

    @Test void theEntryPointOfTheIsolatedRuntimeOpensTheMetalBackendOnAMac() {
        assertNull(System.getProperty("tellurium.fast.backend"), "the test JVM does not ask for Vulkan");
        try (FusedGpuBackend opened = dev.tellurium.runtime.vulkan.fused.FusedNoiseDevice.openIsolated(null)) {
            assertInstanceOf(MetalFusedDevice.class, opened);
            assertEquals(NumericProfile.GPU_IEEE_BITS, opened.numericProfile());
        }
    }

    private void await(FusedGpuBackend.Slot slot) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!slot.poll()) {
            assertFalse(device.lost(), device.lostReason());
            assertTrue(System.nanoTime() < deadline, "the batch did not finish in 20 s");
            Thread.sleep(1);
        }
    }
}
