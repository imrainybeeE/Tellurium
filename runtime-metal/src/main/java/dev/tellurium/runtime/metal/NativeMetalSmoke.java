// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.metal;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Opt-in real-device check of the Metal glue (gradle :runtime-metal:nativeMetalSmoke): two compute pipelines built from
 * Metal source, dispatched in 64-thread groups into shared buffers, the results verified on the host.  It proves what
 * the backend depends on: push constants at buffer 0 and buffers at binding + 1, groups of 64 threads (a thread's index
 * in its group and the group width are written out), the barrier between dispatches, and that a source Metal rejects
 * fails with Metal's own text.  Not an exactness test of the kernels.
 */
public final class NativeMetalSmoke {
    private static final String SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            struct Push { uint count; uint unused; };
            kernel void main0(constant Push& push [[buffer(0)]], device uint* out [[buffer(1)]], device uint* shape [[buffer(2)]],
                              uint gid [[thread_position_in_grid]], uint lane [[thread_position_in_threadgroup]],
                              uint width [[threads_per_threadgroup]]) {
                if (gid >= push.count) return;
                out[gid] = gid * 3u + 1u;
                shape[gid] = lane | (width << 16);
            }
            kernel void second(constant Push& push [[buffer(0)]], device uint* out [[buffer(1)]], device uint* shape [[buffer(2)]],
                               uint gid [[thread_position_in_grid]]) {
                if (gid >= push.count) return;
                out[gid] = out[gid] * 2u + 5u;
            }
            """;

    private NativeMetalSmoke() {}

    public static void main(String[] args) throws Exception {
        String unavailable = MetalFusedDevice.unavailableReason();
        if (unavailable != null) throw new IllegalStateException("No Metal device: " + unavailable);
        try (MetalFusedDevice device = MetalFusedDevice.open()) {
            System.out.println("Metal device: " + device.deviceName());
            // 1000 threads: 16 groups of 64, the last one partly used.
            int count = 1000;
            long groups = (count + 63) / 64;
            MemorySegment first = device.newPipeline(SOURCE, "main0");
            MemorySegment second = device.newPipeline(SOURCE, "second");
            MetalFusedDevice.Buffer out = device.newBuffer(4096 * 4L);
            MetalFusedDevice.Buffer shape = device.newBuffer(4096 * 4L);
            for (int i = 0; i < 4096; i++) { out.bytes().putInt(i * 4, 0xDEAD0000 + i); shape.bytes().putInt(i * 4, -1); }
            MemorySegment commands = device.commit(new int[]{count, 0}, new MemorySegment[]{out.id(), shape.id()},
                    new MemorySegment[]{first, second}, new long[]{groups, groups});
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!MetalFusedDevice.finished(commands)) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("The command buffer did not finish in 10 s");
                Thread.sleep(1);
            }
            if (!MetalFusedDevice.completed(MetalFusedDevice.status(commands))) {
                throw new IllegalStateException("Command buffer failed: " + MetalFusedDevice.failure(commands));
            }
            check(out.bytes(), shape.bytes(), count);
            System.out.println("dispatch ok: " + count + " threads in " + groups + " groups of 64, second dispatch saw the first's writes");

            // A source Metal rejects reports Metal's error, naming the problem.
            try {
                device.newPipeline("kernel void main0(device uint* out [[buffer(0)]]) { out[0] = undeclared_name; }", "main0");
                throw new IllegalStateException("A bad Metal source was accepted");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("undeclared_name")) throw expected;
                System.out.println("bad source rejected: " + firstLine(expected.getMessage()));
            }
            ObjC.release(commands);
            ObjC.release(out.id());
            ObjC.release(shape.id());
            ObjC.release(first);
            ObjC.release(second);
        }
        System.out.println("nativeMetalSmoke passed");
    }

    private static void check(ByteBuffer out, ByteBuffer shape, int count) {
        for (int i = 0; i < count; i++) {
            int expected = (i * 3 + 1) * 2 + 5;
            if (out.getInt(i * 4) != expected) throw new IllegalStateException("out[" + i + "] = " + out.getInt(i * 4) + ", expected " + expected);
            int expectedShape = (i % 64) | (64 << 16);
            if (shape.getInt(i * 4) != expectedShape) {
                throw new IllegalStateException("shape[" + i + "] = 0x" + Integer.toHexString(shape.getInt(i * 4)) + ", expected 0x" + Integer.toHexString(expectedShape)
                        + " (thread index in group | group width << 16)");
            }
        }
        for (int i = count; i < 4096; i++) {
            if (out.getInt(i * 4) != 0xDEAD0000 + i || shape.getInt(i * 4) != -1) throw new IllegalStateException("Element " + i + " beyond the count was written");
        }
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
