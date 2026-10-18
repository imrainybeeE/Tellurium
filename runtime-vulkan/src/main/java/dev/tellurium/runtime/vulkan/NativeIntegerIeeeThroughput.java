// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Opt-in sustained-throughput microbenchmark of the integer-carrier IEEE helpers
 * ({@code wg_fp64_*}, {@code wg_fp32_*}) on a physical device, next to native
 * int32, int64 and fp32 instructions for scale.
 *
 * <p>Every kernel runs a long dependent chain per lane over {@code 2^20} lanes, reading its
 * inputs from a storage buffer and looping a push-constant number of times so nothing folds.
 * The first lanes are re-computed on the host (Java IEEE arithmetic) to prove the work ran and
 * stayed on normal finite values. The helper source is taken verbatim from
 * {@link IntegerIeeeEmitter#helperSource(Set)}. Not a conformance test and not Minecraft
 * qualification.</p>
 */
public final class NativeIntegerIeeeThroughput {
    private static final int LOCAL = 64;
    private static final int WORDS = 8;
    private static final long SEED = 0x5eed1234abcdL;
    private static final long FENCE_TIMEOUT = 30_000_000_000L;
    private static final int NATIVE_UNROLL = 8;

    private static int lanes = 1 << 20;
    private static int runs = 7;
    private static double targetMs = 300.0;

    private enum Data { ADD64, MUL64, LERP64, SINGLE64, ADD32, MUL32, SINGLE32, INT64, INT32, NATIVE_F32 }

    private record Kernel(String id, String kind, int opsPerIter, String body, Set<String> roots, Data data,
                          String calib, String countRoot) {}

    private static List<Kernel> kernels() {
        var list = new ArrayList<Kernel>();
        // Calibration: the per-iteration cost of the re-seed used by single-input kernels.
        list.add(new Kernel("calib.reseed64", "f64", 0,
                "x = wg_bench_reseed64(x, a, 0x40100000u, 0x80000000u);", Set.of("wg_fp64_add"), Data.SINGLE64, null, null));
        list.add(new Kernel("calib.reseed32", "f32", 0,
                "x = wg_bench_reseed32(x, a, 0x40800000u, 0x80000000u);", Set.of("wg_fp32_add"), Data.SINGLE32, null, null));
        list.add(new Kernel("fp64.add", "f64", 2, "x = wg_fp64_add(x, a); x = wg_fp64_add(x, b);",
                Set.of("wg_fp64_add"), Data.ADD64, null, "wg_fp64_add"));
        list.add(new Kernel("fp64.mul", "f64", 2, "x = wg_fp64_mul(x, a); x = wg_fp64_mul(x, b);",
                Set.of("wg_fp64_mul"), Data.MUL64, null, "wg_fp64_mul"));
        list.add(new Kernel("fp64.div", "f64", 2, "x = wg_fp64_div(x, a); x = wg_fp64_div(x, b);",
                Set.of("wg_fp64_div"), Data.MUL64, null, "wg_fp64_div"));
        list.add(new Kernel("fp64.sqrt", "f64", 1,
                "x = wg_fp64_sqrt(x); x = wg_bench_reseed64(x, a, 0x3ff00000u, 0u);",
                Set.of("wg_fp64_sqrt"), Data.SINGLE64, "calib.reseed64", "wg_fp64_sqrt"));
        list.add(new Kernel("fp64.floor", "f64", 1,
                "x = wg_fp64_floor(x); x = wg_bench_reseed64(x, a, 0x40100000u, 0x80000000u);",
                Set.of("wg_fp64_floor"), Data.SINGLE64, "calib.reseed64", "wg_fp64_floor"));
        list.add(new Kernel("fp64.to_fp32", "f64", 1,
                "uint r = wg_fp64_to_fp32(x); x = wg_bench_reseed64(uvec2(r, x.y), a, 0x40100000u, 0x80000000u);",
                Set.of("wg_fp64_to_fp32"), Data.SINGLE64, "calib.reseed64", "wg_fp64_to_fp32"));
        list.add(new Kernel("fp64.from_int", "f64", 1,
                "x = wg_bench_reseed64(wg_fp64_from_int(int(x.x)), a, 0x40100000u, 0x80000000u);",
                Set.of("wg_fp64_from_int"), Data.SINGLE64, "calib.reseed64", "wg_fp64_from_int"));
        list.add(new Kernel("fp64.floor_to_i32", "f64", 1,
                "int r = wg_fp64_floor_to_i32(x); x = wg_bench_reseed64(uvec2(uint(r), x.y), a, 0x40100000u, 0x80000000u);",
                Set.of("wg_fp64_floor_to_i32"), Data.SINGLE64, "calib.reseed64", "wg_fp64_floor_to_i32"));
        list.add(new Kernel("fp64.less", "f64", 1,
                "bool c = wg_fp64_less(x, a); x = wg_bench_reseed64(uvec2(x.x ^ (c ? 1u : 0u), x.y), a, 0x40100000u, 0x80000000u);",
                Set.of("wg_fp64_less"), Data.SINGLE64, "calib.reseed64", "wg_fp64_less"));
        list.add(new Kernel("fp64.lerp", "f64", 2, "x = wg_fp64_lerp(x, a, f); x = wg_fp64_lerp(x, b, f);",
                Set.of("wg_fp64_lerp"), Data.LERP64, null, "wg_fp64_lerp"));
        list.add(new Kernel("fp32.add", "f32", 2, "x = wg_fp32_add(x, a); x = wg_fp32_add(x, b);",
                Set.of("wg_fp32_add"), Data.ADD32, null, "wg_fp32_add"));
        list.add(new Kernel("fp32.mul", "f32", 2, "x = wg_fp32_mul(x, a); x = wg_fp32_mul(x, b);",
                Set.of("wg_fp32_mul"), Data.MUL32, null, "wg_fp32_mul"));
        list.add(new Kernel("fp32.div", "f32", 2, "x = wg_fp32_div(x, a); x = wg_fp32_div(x, b);",
                Set.of("wg_fp32_div"), Data.MUL32, null, "wg_fp32_div"));
        list.add(new Kernel("fp32.sqrt", "f32", 1,
                "x = wg_fp32_sqrt(x); x = wg_bench_reseed32(x, a, 0x3f800000u, 0u);",
                Set.of("wg_fp32_sqrt"), Data.SINGLE32, "calib.reseed32", "wg_fp32_sqrt"));
        list.add(new Kernel("fp32.floor", "f32", 1,
                "x = wg_fp32_floor(x); x = wg_bench_reseed32(x, a, 0x40800000u, 0x80000000u);",
                Set.of("wg_fp32_floor"), Data.SINGLE32, "calib.reseed32", "wg_fp32_floor"));
        list.add(new Kernel("native.int32.add", "i32", 2 * NATIVE_UNROLL, "x = x + y; y = y + x;", Set.of(), Data.INT32, null, null));
        list.add(new Kernel("native.int32.mul", "i32", 2 * NATIVE_UNROLL, "x = x * y; y = y * x;", Set.of(), Data.INT32, null, null));
        list.add(new Kernel("native.int64.add", "i64", 2 * NATIVE_UNROLL, "x = x + y; y = y + x;", Set.of(), Data.INT64, null, null));
        list.add(new Kernel("native.int64.mul", "i64", 2 * NATIVE_UNROLL, "x = x * y; y = y * x;", Set.of(), Data.INT64, null, null));
        list.add(new Kernel("native.fp32.add", "nf32", 2 * NATIVE_UNROLL, "x = x + y; y = y + x;", Set.of(), Data.NATIVE_F32, null, null));
        list.add(new Kernel("native.fp32.mul", "nf32", 2 * NATIVE_UNROLL, "x = x * y; y = y * x;", Set.of(), Data.NATIVE_F32, null, null));
        return list;
    }

    private NativeIntegerIeeeThroughput() {}

    public static void main(String[] args) throws Exception {
        String output = null;
        String only = null;
        for (String arg : args) {
            if (arg.startsWith("--output=")) output = arg.substring(9);
            else if (arg.startsWith("--runs=")) runs = Integer.parseInt(arg.substring(7));
            else if (arg.startsWith("--target-ms=")) targetMs = Double.parseDouble(arg.substring(12));
            else if (arg.startsWith("--lanes=")) lanes = Integer.parseInt(arg.substring(8));
            else if (arg.startsWith("--only=")) only = arg.substring(7);
        }
        var results = new ArrayList<Result>();
        Map<String, Result> byId = new HashMap<>();
        try (Gpu gpu = new Gpu()) {
            gpu.open();
            System.out.println("DEVICE name=" + gpu.name + " timestampPeriodNs=" + gpu.timestampPeriod
                    + " timestampValidBits=" + gpu.timestampValidBits + " shaderInt64=" + gpu.int64
                    + " lanes=" + lanes + " runs=" + runs + " targetMs=" + targetMs);
            for (Kernel kernel : kernels()) {
                if (only != null && !kernel.id.startsWith("calib") && !kernel.id.matches(only)) continue;
                Result result;
                try {
                    result = gpu.measure(kernel);
                } catch (RuntimeException failure) {
                    System.out.println("KERNEL_FAIL kernel=" + kernel.id + " reason=" + failure.getMessage());
                    continue;
                }
                results.add(result);
                byId.put(kernel.id, result);
                System.out.println(result.line());
            }
        }
        System.out.println("SUMMARY (net of re-seed calibration where marked; headline timer per row)");
        var json = new StringBuilder("{\n  \"lanes\": " + lanes + ",\n  \"kernels\": [");
        boolean first = true;
        for (Result r : results) {
            Result calib = r.kernel.calib == null ? null : byId.get(r.kernel.calib);
            double netIterSec = r.iterSec();
            if (calib != null) netIterSec -= calib.iterSec();
            double opsPerSec = r.kernel.opsPerIter == 0 ? 0.0 : (double) lanes * r.kernel.opsPerIter / netIterSec;
            double grossOpsPerSec = r.kernel.opsPerIter == 0 ? 0.0 : (double) lanes * r.kernel.opsPerIter / r.iterSec();
            r.netOpsPerSec = opsPerSec;
            Result i32 = byId.get("native.int32.add");
            double i32Rate = i32 == null ? 0 : (double) lanes * i32.kernel.opsPerIter / i32.iterSec();
            String eq = r.kernel.opsPerIter == 0 || i32Rate == 0 ? "n/a" : String.format("%.1f", i32Rate / opsPerSec);
            System.out.printf("TABLE kernel=%s opsPerSec=%.4e grossOpsPerSec=%.4e int32AddEquivalentPerOp=%s timer=%s spreadMaxOverMin=%.2f verify=%s%n",
                    r.kernel.id, opsPerSec, grossOpsPerSec, eq, r.timer, r.spread, r.verify);
            if (!first) json.append(',');
            first = false;
            json.append(String.format("%n    {\"kernel\": \"%s\", \"opsPerIter\": %d, \"iters\": %d, \"netOpsPerSec\": %.6e, \"grossOpsPerSec\": %.6e, "
                            + "\"medianWallMs\": %.3f, \"gpuTsScaledMs\": %.3f, \"gpuTsRawTicksOverWall\": %.3f, \"timer\": \"%s\", \"spread\": %.3f, \"verify\": \"%s\", "
                            + "\"staticAluInstrs\": %d, \"staticTotalInstrs\": %d, \"staticLoops\": %d, \"int32AddEquivalentPerOp\": \"%s\"}",
                    r.kernel.id, r.kernel.opsPerIter, r.iters, opsPerSec, grossOpsPerSec, r.medianWallNs / 1e6,
                    r.medianTsNs / 1e6, r.tsRawOverWall, r.timer, r.spread, r.verify, r.staticAlu, r.staticTotal, r.staticLoops, eq));
        }
        json.append("\n  ]\n}\n");
        if (output != null) {
            Path path = Path.of(output).toAbsolutePath().normalize();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, json.toString());
            System.out.println("REPORT " + path);
        }
        boolean unstable = results.stream().anyMatch(r -> r.spread > 2.0);
        boolean badVerify = results.stream().anyMatch(r -> r.verify.startsWith("FAIL"));
        System.out.println("nativeIntegerIeeeThroughput DONE kernels=" + results.size()
                + " unstableOver2x=" + unstable + " verifyFailures=" + badVerify);
    }

    // ----------------------------------------------------------------- results

    private static final class Result {
        final Kernel kernel;
        int iters;
        double medianWallNs, medianTsNs, medianTsRawTicks, tsScaledOverWall, tsRawOverWall, spread;
        String timer = "wall";
        String verify = "n/a";
        int staticAlu = -1, staticTotal = -1, staticLoops = -1;
        double netOpsPerSec;
        Result(Kernel kernel) { this.kernel = kernel; }

        double iterSec() { return (timer.equals("gpu-ts") ? medianTsNs : medianWallNs) / 1e9 / iters; }

        String line() {
            return String.format("KERNEL kernel=%s opsPerIter=%d iters=%d medianWallMs=%.2f gpuTsScaledMs=%.2f gpuTsScaledOverWall=%.3f gpuTsRawTicksOverWall=%.3f timer=%s spreadMaxOverMin=%.2f verify=%s staticAluInstrs=%d staticTotalInstrs=%d staticLoops=%d",
                    kernel.id, kernel.opsPerIter, iters, medianWallNs / 1e6, medianTsNs / 1e6, tsScaledOverWall, tsRawOverWall, timer, spread, verify,
                    staticAlu, staticTotal, staticLoops);
        }
    }

    // ----------------------------------------------------------------- shader

    private static String shaderSource(Kernel k) {
        var s = new StringBuilder("#version 450\n");
        if (k.kind.equals("i64")) s.append("#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require\n");
        s.append("layout(local_size_x=").append(LOCAL).append(", local_size_y=1, local_size_z=1) in;\n")
                .append("layout(std430, binding=0) readonly buffer Inputs { uint inb[]; };\n")
                .append("layout(std430, binding=1) writeonly buffer Outputs { uint outb[]; };\n")
                .append("layout(push_constant) uniform Dispatch { uint count; uint iters; } pc;\n")
                .append("bool wg_failed;\n");
        if (!k.roots.isEmpty()) s.append(IntegerIeeeEmitter.helperSource(k.roots)).append('\n');
        s.append("""
                uvec2 wg_bench_reseed64(uvec2 x, uvec2 salt, uint exponentHi, uint signAllowed) {
                    uint h = (x.x ^ x.y) * 747796405u + salt.x;
                    uint h2 = (h ^ (h >> 15u)) * 2246822519u;
                    return uvec2(h2 ^ salt.y, (((h ^ (h2 >> 11u)) & 0x000fffffu) | exponentHi) | (h2 & signAllowed));
                }
                uint wg_bench_reseed32(uint x, uint salt, uint exponentBits, uint signAllowed) {
                    uint h = x * 747796405u + salt;
                    uint h2 = (h ^ (h >> 15u)) * 2246822519u;
                    return (((h2 ^ (h2 >> 13u)) & 0x007fffffu) | exponentBits) | (h2 & signAllowed);
                }
                void main() {
                    uint lane = gl_GlobalInvocationID.x;
                    if (lane >= pc.count) return;
                    uint base = lane * 8u;
                """);
        String body = k.body;
        switch (k.kind) {
            case "f64" -> s.append("""
                    uvec2 x = uvec2(inb[base], inb[base + 1u]), a = uvec2(inb[base + 2u], inb[base + 3u]);
                    uvec2 b = uvec2(inb[base + 4u], inb[base + 5u]), f = uvec2(inb[base + 6u], inb[base + 7u]);
                    for (uint i = 0u; i < pc.iters; i++) {
                    """).append(body).append("""
                    }
                    outb[lane * 2u] = x.x; outb[lane * 2u + 1u] = x.y;
                    """);
            case "f32" -> s.append("""
                    uint x = inb[base], a = inb[base + 2u], b = inb[base + 4u];
                    for (uint i = 0u; i < pc.iters; i++) {
                    """).append(body).append("""
                    }
                    outb[lane * 2u] = x; outb[lane * 2u + 1u] = 0u;
                    """);
            case "i32" -> s.append("uint x = inb[base], y = inb[base + 2u];\nfor (uint i = 0u; i < pc.iters; i++) {\n")
                    .append(body.repeat(NATIVE_UNROLL)).append("\n}\noutb[lane * 2u] = x; outb[lane * 2u + 1u] = y;\n");
            case "i64" -> s.append("uint64_t x = packUint2x32(uvec2(inb[base], inb[base + 1u])), y = packUint2x32(uvec2(inb[base + 2u], inb[base + 3u]));\nfor (uint i = 0u; i < pc.iters; i++) {\n")
                    .append(body.repeat(NATIVE_UNROLL)).append("\n}\nuvec2 r = unpackUint2x32(x); outb[lane * 2u] = r.x; outb[lane * 2u + 1u] = r.y;\n");
            case "nf32" -> s.append("float x = uintBitsToFloat(inb[base]), y = uintBitsToFloat(inb[base + 2u]);\nfor (uint i = 0u; i < pc.iters; i++) {\n")
                    .append(body.repeat(NATIVE_UNROLL)).append("\n}\noutb[lane * 2u] = floatBitsToUint(x); outb[lane * 2u + 1u] = floatBitsToUint(y);\n");
            default -> throw new IllegalStateException(k.kind);
        }
        return s.append("}\n").toString();
    }

    // ----------------------------------------------------------------- static SPIR-V instruction counts

    private static final Pattern ALU = Pattern.compile(
            "Op(IAdd|ISub|IMul|UMulExtended|SMulExtended|IAddCarry|ISubBorrow|UDiv|SDiv|UMod|SMod|SNegate|Shift\\w+|Bitwise\\w+|Not|BitCount|BitReverse|BitField\\w+|FindUMsb|FindSMsb|FindILsb|[SU](Less|Greater)Than\\w*|IEqual|INotEqual|Select|Logical\\w+|FAdd|FSub|FMul|FDiv|FNegate|ConvertUToF|ConvertSToF|ConvertFToU|ConvertFToS|Bitcast)");

    private static int[] staticCounts(String assembly, String root) {
        Map<String, int[]> perFn = new HashMap<>();
        Map<String, List<String>> calls = new HashMap<>();
        String current = null;
        for (String raw : assembly.split("\n")) {
            String line = raw.trim();
            if (line.contains("= OpFunction ")) {
                current = line.substring(0, line.indexOf(' '));
                perFn.put(current, new int[3]);
                calls.put(current, new ArrayList<>());
                continue;
            }
            if (current == null) continue;
            if (line.equals("OpFunctionEnd")) { current = null; continue; }
            if (line.startsWith("%") && line.endsWith(":")) continue; // label
            int[] c = perFn.get(current);
            String[] tok = line.split("\\s+");
            String op = tok[0].startsWith("%") && tok.length > 2 ? tok[2] : tok[0];
            if (op.equals("OpLabel") || op.equals("OpVariable")) continue;
            c[1]++;
            if (ALU.matcher(op).matches()) c[0]++;
            if (op.equals("OpLoopMerge")) c[2]++;
            if (op.equals("OpFunctionCall")) calls.get(current).add(tok[4]);
        }
        var seen = new java.util.LinkedHashSet<String>();
        var work = new java.util.ArrayDeque<String>();
        String rootId = null;
        for (String key : perFn.keySet()) if (key.startsWith("%" + root + "_")) { rootId = key; break; }
        if (rootId == null) return new int[] {-1, -1, -1};
        work.add(rootId);
        while (!work.isEmpty()) {
            String f = work.poll();
            if (!seen.add(f)) continue;
            for (String callee : calls.getOrDefault(f, List.of())) if (perFn.containsKey(callee)) work.add(callee);
        }
        int[] total = new int[3];
        for (String f : seen) for (int i = 0; i < 3; i++) total[i] += perFn.get(f)[i];
        return total;
    }

    private static String compileAssembly(String source) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        long result = 0;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            result = shaderc_compile_into_spv_assembly(compiler, source, shaderc_compute_shader, "throughput.comp", "main", options);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("assembly compile failed: " + shaderc_result_get_error_message(result));
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    // ----------------------------------------------------------------- data and host references

    private static long mix(long z) {
        z += 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static double u(long lane, int k) { return (mix(SEED + lane * 16 + k) >>> 11) * 0x1.0p-53; }

    private static double sgn(long lane, int k, double v) { return (mix(SEED + lane * 16 + k + 8) & 1L) == 0 ? v : -v; }

    private static void put64(int[] w, int slot, double v) {
        long bits = Double.doubleToRawLongBits(v);
        w[slot] = (int) bits;
        w[slot + 1] = (int) (bits >>> 32);
    }

    private static double get64(int[] w, int slot) {
        return Double.longBitsToDouble((w[slot] & 0xffffffffL) | ((long) w[slot + 1] << 32));
    }

    private static void fill(Data data, long lane, int[] w) {
        Arrays.fill(w, 0);
        switch (data) {
            case ADD64 -> {
                put64(w, 0, sgn(lane, 0, 1 + u(lane, 0)));
                double a = sgn(lane, 1, 0.25 + 3.75 * u(lane, 1));
                put64(w, 2, a);
                put64(w, 4, -a);
            }
            case MUL64 -> {
                put64(w, 0, 1 + u(lane, 0));
                double a = 0.5 + 1.5 * u(lane, 1);
                put64(w, 2, a);
                put64(w, 4, 1.0 / a);
            }
            case LERP64 -> {
                put64(w, 0, u(lane, 0));
                put64(w, 2, -2 + 4 * u(lane, 1));
                put64(w, 4, -2 + 4 * u(lane, 2));
                put64(w, 6, 0.1 + 0.8 * u(lane, 3));
            }
            case SINGLE64 -> {
                put64(w, 0, 1 + u(lane, 0));
                long salt = mix(SEED + lane * 16 + 5);
                w[2] = (int) salt;
                w[3] = (int) (salt >>> 32);
            }
            case ADD32 -> {
                float a = (float) sgn(lane, 1, 0.25 + 3.75 * u(lane, 1));
                w[0] = Float.floatToRawIntBits((float) sgn(lane, 0, 1 + u(lane, 0)));
                w[2] = Float.floatToRawIntBits(a);
                w[4] = Float.floatToRawIntBits(-a);
            }
            case MUL32 -> {
                float a = (float) (0.5 + 1.5 * u(lane, 1));
                w[0] = Float.floatToRawIntBits((float) (1 + u(lane, 0)));
                w[2] = Float.floatToRawIntBits(a);
                w[4] = Float.floatToRawIntBits(1.0f / a);
            }
            case SINGLE32 -> {
                w[0] = Float.floatToRawIntBits((float) (1 + u(lane, 0)));
                w[2] = (int) mix(SEED + lane * 16 + 5);
            }
            case INT64 -> {
                long x = mix(SEED + lane * 16) | 1L, y = mix(SEED + lane * 16 + 1) | 1L;
                w[0] = (int) x; w[1] = (int) (x >>> 32); w[2] = (int) y; w[3] = (int) (y >>> 32);
            }
            case INT32 -> {
                w[0] = (int) mix(SEED + lane * 16) | 1;
                w[2] = (int) mix(SEED + lane * 16 + 1) | 1;
            }
            case NATIVE_F32 -> {
                w[0] = Float.floatToRawIntBits((float) (1 + u(lane, 0)));
                w[2] = Float.floatToRawIntBits((float) (1 + u(lane, 1)));
            }
        }
    }

    private static long[] reseed64(long x, long salt, int exponentHi, int signAllowed) {
        int xLo = (int) x, xHi = (int) (x >>> 32), sLo = (int) salt, sHi = (int) (salt >>> 32);
        int h = (xLo ^ xHi) * 747796405 + sLo;
        int h2 = (h ^ (h >>> 15)) * 0x85ebca77;
        int lo = h2 ^ sHi;
        int hi = (((h ^ (h2 >>> 11)) & 0x000fffff) | exponentHi) | (h2 & signAllowed);
        return new long[] {(lo & 0xffffffffL) | ((long) hi << 32)};
    }

    private static int reseed32(int x, int salt, int exponentBits, int signAllowed) {
        int h = x * 747796405 + salt;
        int h2 = (h ^ (h >>> 15)) * 0x85ebca77;
        return (((h2 ^ (h2 >>> 13)) & 0x007fffff) | exponentBits) | (h2 & signAllowed);
    }

    /** Host re-computation of one lane's two output words; null when no reference exists. */
    private static int[] reference(Kernel k, int[] w, int iters) {
        String id = k.id;
        if (id.startsWith("calib.reseed64")) {
            long x = Double.doubleToRawLongBits(get64(w, 0)), salt = (w[2] & 0xffffffffL) | ((long) w[3] << 32);
            for (int i = 0; i < iters; i++) x = reseed64(x, salt, 0x40100000, 0x80000000)[0];
            return new int[] {(int) x, (int) (x >>> 32)};
        }
        if (id.startsWith("calib.reseed32")) {
            int x = w[0];
            for (int i = 0; i < iters; i++) x = reseed32(x, w[2], 0x40800000, 0x80000000);
            return new int[] {x, 0};
        }
        if (id.startsWith("fp64.")) {
            double x = get64(w, 0), a = get64(w, 2), b = get64(w, 4), f = get64(w, 6);
            long salt = (w[2] & 0xffffffffL) | ((long) w[3] << 32);
            long bits = 0;
            for (int i = 0; i < iters; i++) {
                switch (id) {
                    case "fp64.add" -> { x = x + a; x = x + b; }
                    case "fp64.mul" -> { x = x * a; x = x * b; }
                    case "fp64.div" -> { x = x / a; x = x / b; }
                    case "fp64.lerp" -> { x = x + f * (a - x); x = x + f * (b - x); }
                    case "fp64.sqrt" -> x = Double.longBitsToDouble(reseed64(Double.doubleToRawLongBits(Math.sqrt(x)), salt, 0x3ff00000, 0)[0]);
                    case "fp64.floor" -> x = Double.longBitsToDouble(reseed64(Double.doubleToRawLongBits(Math.floor(x)), salt, 0x40100000, 0x80000000)[0]);
                    case "fp64.from_int" -> x = Double.longBitsToDouble(reseed64(Double.doubleToRawLongBits((double) (int) Double.doubleToRawLongBits(x)), salt, 0x40100000, 0x80000000)[0]);
                    case "fp64.floor_to_i32" -> {
                        long cur = Double.doubleToRawLongBits(x);
                        long mixed = (((int) Math.floor(x)) & 0xffffffffL) | (cur & 0xffffffff00000000L);
                        x = Double.longBitsToDouble(reseed64(mixed, salt, 0x40100000, 0x80000000)[0]);
                    }
                    case "fp64.less" -> {
                        long cur = Double.doubleToRawLongBits(x);
                        double aSalt = Double.longBitsToDouble(salt);
                        long mixed = ((cur ^ (x < aSalt ? 1L : 0L)) & 0xffffffffL) | (cur & 0xffffffff00000000L);
                        x = Double.longBitsToDouble(reseed64(mixed, salt, 0x40100000, 0x80000000)[0]);
                    }
                    case "fp64.to_fp32" -> {
                        long cur = Double.doubleToRawLongBits(x);
                        long mixed = (Float.floatToRawIntBits((float) x) & 0xffffffffL) | (cur & 0xffffffff00000000L);
                        x = Double.longBitsToDouble(reseed64(mixed, salt, 0x40100000, 0x80000000)[0]);
                    }
                    default -> { return null; }
                }
            }
            bits = Double.doubleToRawLongBits(x);
            return new int[] {(int) bits, (int) (bits >>> 32)};
        }
        if (id.startsWith("fp32.")) {
            float x = Float.intBitsToFloat(w[0]), a = Float.intBitsToFloat(w[2]), b = Float.intBitsToFloat(w[4]);
            int salt = w[2];
            int bitsX = w[0];
            for (int i = 0; i < iters; i++) {
                switch (id) {
                    case "fp32.add" -> { x = x + a; x = x + b; }
                    case "fp32.mul" -> { x = x * a; x = x * b; }
                    case "fp32.div" -> { x = x / a; x = x / b; }
                    case "fp32.sqrt" -> x = Float.intBitsToFloat(reseed32(Float.floatToRawIntBits((float) Math.sqrt(x)), salt, 0x3f800000, 0));
                    case "fp32.floor" -> x = Float.intBitsToFloat(reseed32(Float.floatToRawIntBits((float) Math.floor(x)), salt, 0x40800000, 0x80000000));
                    default -> { return null; }
                }
            }
            return new int[] {Float.floatToRawIntBits(x), 0};
        }
        if (id.startsWith("native.int32.")) {
            int x = w[0], y = w[2];
            boolean mul = id.endsWith("mul");
            for (int i = 0; i < iters * NATIVE_UNROLL; i++) {
                if (mul) { x = x * y; y = y * x; } else { x = x + y; y = y + x; }
            }
            return new int[] {x, y};
        }
        if (id.startsWith("native.int64.")) {
            long x = (w[0] & 0xffffffffL) | ((long) w[1] << 32), y = (w[2] & 0xffffffffL) | ((long) w[3] << 32);
            boolean mul = id.endsWith("mul");
            for (int i = 0; i < iters * NATIVE_UNROLL; i++) {
                if (mul) { x = x * y; y = y * x; } else { x = x + y; y = y + x; }
            }
            return new int[] {(int) x, (int) (x >>> 32)};
        }
        return null; // native fp32 is not replayed: the driver may use fast-math reassociation
    }

    // ----------------------------------------------------------------- Vulkan

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: " + result);
    }

    private record Alloc(long buffer, long memory, ByteBuffer mapped) {}

    private static final class Gpu implements AutoCloseable {
        VkInstance instance;
        VkPhysicalDevice physical;
        VkDevice device;
        VkQueue queue;
        int family = -1;
        String name;
        double timestampPeriod;
        int timestampValidBits;
        boolean int64;
        Alloc input, output;

        void open() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var app = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Tellurium throughput")).apiVersion(VK_MAKE_VERSION(1, 2, 0));
                var ext = VulkanPortability.instanceExtensionsToEnable(stack);
                var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                        .flags(VulkanPortability.instanceFlags(ext)).ppEnabledExtensionNames(ext);
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateInstance(create, null, out), "vkCreateInstance");
                instance = new VkInstance(out.get(0), create);
                IntBuffer count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan devices");
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, devices), "enumerate");
                physical = new VkPhysicalDevice(devices.get(0), instance);
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(physical, props);
                name = props.deviceNameString();
                timestampPeriod = props.limits().timestampPeriod();
                var features = VkPhysicalDeviceFeatures.calloc(stack);
                vkGetPhysicalDeviceFeatures(physical, features);
                int64 = features.shaderInt64();
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
                for (int i = 0; i < families.capacity(); i++) {
                    if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        family = i;
                        timestampValidBits = families.get(i).timestampValidBits();
                        break;
                    }
                }
                if (family < 0) throw new IllegalStateException("No compute queue");
                var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1.0f));
                var enabled = VkPhysicalDeviceFeatures.calloc(stack).shaderInt64(int64);
                var dev = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                        .pEnabledFeatures(enabled)
                        .ppEnabledExtensionNames(VulkanPortability.deviceExtensionsToEnable(physical, stack));
                check(vkCreateDevice(physical, dev, null, out), "vkCreateDevice");
                device = new VkDevice(out.get(0), physical, dev);
                vkGetDeviceQueue(device, family, 0, out);
                queue = new VkQueue(out.get(0), device);
            }
            input = allocate((long) lanes * WORDS * 4);
            output = allocate((long) lanes * 2 * 4);
        }

        Alloc allocate(long bytes) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(device, create, null, out), "vkCreateBuffer");
                long buffer = out.get(0);
                var req = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device, buffer, req);
                var mem = VkPhysicalDeviceMemoryProperties.calloc(stack);
                vkGetPhysicalDeviceMemoryProperties(physical, mem);
                int type = -1, need = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                for (int i = 0; i < mem.memoryTypeCount(); i++)
                    if ((req.memoryTypeBits() & (1 << i)) != 0 && (mem.memoryTypes(i).propertyFlags() & need) == need) { type = i; break; }
                if (type < 0) throw new IllegalStateException("no host-visible coherent memory");
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(req.size()).memoryTypeIndex(type), null, out), "vkAllocateMemory");
                long memory = out.get(0);
                check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
                PointerBuffer mapped = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory");
                return new Alloc(buffer, memory, MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(bytes)).order(ByteOrder.nativeOrder()));
            }
        }

        Result measure(Kernel k) {
            var result = new Result(k);
            String source = shaderSource(k);
            if (k.countRoot != null) {
                String assembly = compileAssembly(source);
                if (System.getProperty("tellurium.throughput.dumpAsm") != null) {
                    try {
                        Files.writeString(Path.of(System.getProperty("tellurium.throughput.dumpAsm"), k.id + ".spvasm"), assembly);
                    } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                }
                int[] counts = staticCounts(assembly, k.countRoot);
                result.staticAlu = counts[0];
                result.staticTotal = counts[1];
                result.staticLoops = counts[2];
            }
            if (k.kind.equals("i64") && !int64) throw new IllegalStateException("shaderInt64 not supported");
            byte[] spirv = new ShadercCompiler().compile(source);
            IntBuffer in = input.mapped.asIntBuffer();
            int[] w = new int[WORDS];
            for (int lane = 0; lane < lanes; lane++) {
                fill(k.data, lane, w);
                in.position(lane * WORDS);
                in.put(w);
            }
            int[] laneIds = new int[64];
            for (int i = 0; i < 32; i++) { laneIds[i] = i; laneIds[32 + i] = lanes - 32 + i; }
            int[][] inputs = new int[64][WORDS];
            for (int i = 0; i < 64; i++) fill(k.data, laneIds[i], inputs[i]);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer h = stack.mallocLong(1);
                ByteBuffer code = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.nativeOrder());
                long shader = 0, dsl = 0, pl = 0, pipe = 0, pool = 0, cmdPool = 0, fence = 0, query = 0;
                try {
                    code.put(spirv).flip();
                    check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, h), "shader module");
                    shader = h.get(0);
                    var bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
                    for (int i = 0; i < 2; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                    check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, h), "dsl");
                    dsl = h.get(0);
                    var push = VkPushConstantRange.calloc(1, stack);
                    push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(8);
                    check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                            .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(push), null, h), "pipeline layout");
                    pl = h.get(0);
                    var ci = VkComputePipelineCreateInfo.calloc(1, stack);
                    ci.get(0).sType$Default().layout(pl).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shader).pName(stack.UTF8("main"));
                    check(vkCreateComputePipelines(device, 0, ci, null, h), "pipeline");
                    pipe = h.get(0);
                    var sizes = VkDescriptorPoolSize.calloc(1, stack);
                    sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                    check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, h), "pool");
                    pool = h.get(0);
                    check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                            .descriptorPool(pool).pSetLayouts(stack.longs(dsl)), h), "descriptor set");
                    long set = h.get(0);
                    var writes = VkWriteDescriptorSet.calloc(2, stack);
                    Alloc[] bufs = {input, output};
                    for (int i = 0; i < 2; i++) {
                        var info = VkDescriptorBufferInfo.calloc(1, stack);
                        info.get(0).buffer(bufs[i].buffer).offset(0).range(bufs[i].mapped.capacity());
                        writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .descriptorCount(1).pBufferInfo(info);
                    }
                    vkUpdateDescriptorSets(device, writes, null);
                    boolean ts = timestampValidBits > 0 && timestampPeriod > 0;
                    if (ts) {
                        check(vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                                .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2), null, h), "query pool");
                        query = h.get(0);
                    }
                    check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, h), "command pool");
                    cmdPool = h.get(0);
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, h), "fence");
                    fence = h.get(0);
                    PointerBuffer cmdOut = stack.mallocPointer(1);
                    check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                            .commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), cmdOut), "cmd alloc");
                    VkCommandBuffer cmd = new VkCommandBuffer(cmdOut.get(0), device);

                    // calibrate iteration count on wall time
                    int iters = 4;
                    double[] timing;
                    for (int round = 0; round < 12; round++) {
                        timing = run(stack, cmd, pipe, pl, set, query, fence, iters);
                        double ms = timing[0] / 1e6;
                        if (ms > targetMs * 0.6 && ms < targetMs * 1.6) break;
                        double scale = Math.max(0.1, Math.min(64.0, targetMs / Math.max(ms, 0.05)));
                        iters = (int) Math.max(1, Math.min(1 << 24, Math.round(iters * scale)));
                    }
                    result.iters = iters;
                    run(stack, cmd, pipe, pl, set, query, fence, iters); // warm-up
                    double[] wall = new double[runs], gpu = new double[runs];
                    for (int r = 0; r < runs; r++) {
                        timing = run(stack, cmd, pipe, pl, set, query, fence, iters);
                        wall[r] = timing[0];
                        gpu[r] = timing[1];
                    }
                    result.medianWallNs = median(wall);
                    result.medianTsNs = median(gpu);
                    double min = Arrays.stream(wall).min().getAsDouble(), max = Arrays.stream(wall).max().getAsDouble();
                    result.spread = max / min;
                    // medianTsNs holds the scaled value (raw ticks * timestampPeriod); the raw tick delta is kept too.
                    result.medianTsRawTicks = result.medianTsNs;
                    result.medianTsNs = result.medianTsRawTicks * timestampPeriod;
                    double ratio = result.medianTsNs / result.medianWallNs;
                    result.tsScaledOverWall = ratio;
                    result.tsRawOverWall = result.medianTsRawTicks / result.medianWallNs;
                    if (ts && result.medianTsNs > 0 && ratio > 0.7 && ratio < 1.3) result.timer = "gpu-ts";
                    else result.timer = "wall";
                    // verify the last run's output on 64 lanes
                    int bad = 0, checked = 0;
                    boolean haveRef = false;
                    for (int i = 0; i < 64; i++) {
                        int[] expected = reference(k, inputs[i], iters);
                        if (expected == null) break;
                        haveRef = true;
                        checked++;
                        int lane = laneIds[i];
                        int lo = output.mapped.getInt(lane * 8), hi = output.mapped.getInt(lane * 8 + 4);
                        boolean match = expected[0] == lo && (k.kind.equals("f32") || expected[1] == hi);
                        if (!match) bad++;
                    }
                    result.verify = !haveRef ? "n/a(no host replay)" : bad == 0 ? "OK(" + checked + " lanes bit-exact)" : "FAIL(" + bad + "/" + checked + ")";
                    return result;
                } finally {
                    if (query != 0) vkDestroyQueryPool(device, query, null);
                    if (fence != 0) vkDestroyFence(device, fence, null);
                    if (cmdPool != 0) vkDestroyCommandPool(device, cmdPool, null);
                    if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
                    if (pipe != 0) vkDestroyPipeline(device, pipe, null);
                    if (pl != 0) vkDestroyPipelineLayout(device, pl, null);
                    if (dsl != 0) vkDestroyDescriptorSetLayout(device, dsl, null);
                    if (shader != 0) vkDestroyShaderModule(device, shader, null);
                    MemoryUtil.memFree(code);
                }
            }
        }

        /** One timed dispatch; returns {wallNs, gpuTimestampNs or -1}. */
        double[] run(MemoryStack parent, VkCommandBuffer cmd, long pipe, long pl, long set, long query, long fence, int iters) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkResetCommandBuffer(cmd, 0), "reset cmd");
                check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()), "begin");
                if (query != 0) {
                    vkCmdResetQueryPool(cmd, query, 0, 2);
                    vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query, 0);
                }
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, stack.longs(set), null);
                vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(lanes, iters));
                vkCmdDispatch(cmd, (lanes + LOCAL - 1) / LOCAL, 1, 1);
                if (query != 0) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query, 1);
                var barrier = VkMemoryBarrier.calloc(1, stack);
                barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
                check(vkEndCommandBuffer(cmd), "end");
                check(vkResetFences(device, stack.longs(fence)), "reset fence");
                long t0 = System.nanoTime();
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd.address())), fence), "submit");
                check(vkWaitForFences(device, fence, true, FENCE_TIMEOUT), "wait");
                double wall = System.nanoTime() - t0;
                double gpu = -1;
                if (query != 0) {
                    LongBuffer data = stack.mallocLong(2);
                    int rc = vkGetQueryPoolResults(device, query, 0, 2, data, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
                    if (rc == VK_SUCCESS) {
                        long delta = data.get(1) - data.get(0);
                        if (timestampValidBits < 64) delta &= (1L << timestampValidBits) - 1;
                        gpu = delta;
                    }
                }
                return new double[] {wall, gpu};
            }
        }

        @Override public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                for (Alloc a : new Alloc[] {input, output}) {
                    if (a == null) continue;
                    vkUnmapMemory(device, a.memory);
                    vkDestroyBuffer(device, a.buffer, null);
                    vkFreeMemory(device, a.memory, null);
                }
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        }
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}
