// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.fused.FastIeeeHelpers;
import dev.tellurium.compiler.vulkan.fused.FusedIeeeHelpers;
import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.fused.FusedLibCampaign;
import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.NoiseParameters;
import dev.tellurium.semantic.snapshot.EndIslandParameters;
import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
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
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceFloatControlsProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_COMPUTE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkAllocateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkAllocateMemory;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;
import static org.lwjgl.vulkan.VK10.vkCmdBindDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkCmdBindPipeline;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkCmdPushConstants;
import static org.lwjgl.vulkan.VK10.vkCreateBuffer;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateComputePipelines;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;
import static org.lwjgl.vulkan.VK10.vkDestroyBuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkFreeMemory;
import static org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK10.vkMapMemory;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkUnmapMemory;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Opt-in real-device conformance for the integer-carrier helpers. This class
 * is deliberately outside the ordinary JUnit task and never supplies a CPU
 * answer when Vulkan execution fails.
 */
public final class NativeIntegerIeeeRunner {
    private static final int LOCAL_SIZE = 64;
    private static final long FENCE_TIMEOUT_NANOS = 10_000_000_000L;
    /**
     * Diagnostic only (--host-nan-sign-agnostic): Java leaves the sign and payload of a freshly generated NaN
     * unspecified and the host CPU decides (x86 yields 0xfff8..., AArch64 yields 0x7ff8...), so a captured
     * reference computed on the host JVM can differ from the device in the NaN sign bit alone. Off by default;
     * the strict raw-bit comparison is the qualification gate.
     */
    private static boolean nanSignAgnostic;
    private static int nanSignDifferences;

    private NativeIntegerIeeeRunner() {}

    public static void main(String[] args) throws Exception {
        boolean fusedIeee = hasFlag(args, "--fused-ieee");
        boolean fusedLib = hasFlag(args, "--fused-lib");
        boolean fastIeee = hasFlag(args, "--fast-ieee");
        if ((fusedIeee ? 1 : 0) + (fusedLib ? 1 : 0) + (fastIeee ? 1 : 0) > 1) {
            throw new IllegalArgumentException("--fused-ieee, --fused-lib and --fast-ieee do not combine");
        }
        if (fusedIeee || fusedLib || fastIeee) {
            // The fused campaigns have their own shader, vectors and report; they do not combine with the other modes.
            String mode = fastIeee ? "--fast-ieee" : fusedLib ? "--fused-lib" : "--fused-ieee";
            for (String other : List.of("--release", "--arithmetic-only", "--blended-only", "--blend-only", "--noise-only",
                    "--host-nan-sign-agnostic")) {
                if (hasFlag(args, other)) throw new IllegalArgumentException(mode + " does not combine with " + other);
            }
        }
        String source = shaderSource();
        boolean release = hasFlag(args, "--release");
        boolean arithmeticOnly = hasFlag(args, "--arithmetic-only");
        nanSignAgnostic = hasFlag(args, "--host-nan-sign-agnostic");
        int binaryVectorCount = release ? 1_024 : 320;
        int unaryVectorCount = release ? 1_000_000 : 100_000;
        int rngVectorCount = release ? 1_000_000 : 100_000;
        try (Session session = new Session()) {
            session.enableInt64 = fastIeee || fusedLib;
            session.selectAndOpen();
            if (fastIeee) {
                FastIeee.run(session, outputPath(args));
                return;
            }
            if (fusedIeee) {
                FusedIeee.run(session, outputPath(args));
                return;
            }
            if (fusedLib) {
                FusedLibCampaign.run((spirv, operation, input, count, outputWords, extra) ->
                        session.dispatch(spirv, operation, input, count, outputWords, extra, true), session.name(), outputPath(args));
                return;
            }
            if (hasFlag(args, "--blended-only")) {
                runCapturedBlendedNoise(session, release);
                return;
            }
            if (hasFlag(args, "--blend-only")) {
                runCapturedBlendDensity(session, release);
                return;
            }
            if (hasFlag(args, "--noise-only")) {
                runCapturedNoise(session, release);
                runCapturedEndIsland(session, release);
                runCapturedWeirdScaled(session, release);
                return;
            }
            byte[] spirv = new ShadercCompiler().compile(source);
            var moduleInspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                    .inspectSpirv(spirv, dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS);
            if (!moduleInspection.valid()) {
                throw new IllegalStateException("Compiled integer conformance module violates GPU_IEEE_BITS: "
                        + moduleInspection.violations());
            }
            long vectorSeed = release ? 0x13579bdf2468ace0L : 0x4d595df4d0f33173L;
            long vectorSeed64 = release ? 0x0f1e2d3c4b5a6978L : 0x243f6a8885a308d3L;
            long rngSeed = release ? 0xdeadbeef01234567L : 0x6a09e667f3bcc909L;
            List<Integer> binary32 = vectors32(binaryVectorCount, vectorSeed);
            List<Integer> unary32 = vectors32(unaryVectorCount, vectorSeed ^ 0x9e3779b97f4a7c15L);
            List<Long> binary64 = vectors64(binaryVectorCount, vectorSeed64);
            List<Long> unary64 = vectors64(unaryVectorCount, vectorSeed64 ^ 0x94d049bb133111ebL);
            List<RngVector> rng = rngVectors(rngVectorCount, rngSeed);
            int comparisons = 0;
            int[] operationCounts = new int[19];
            for (int operation = 0; operation <= 3; operation++) {
                operationCounts[operation] = session.check32(spirv, operation, binary32);
                comparisons += operationCounts[operation];
            }
            for (int operation = 4; operation <= 5; operation++) {
                operationCounts[operation] = session.checkUnary32(spirv, operation, unary32);
                comparisons += operationCounts[operation];
            }
            for (int operation = 6; operation <= 8; operation++) {
                operationCounts[operation] = session.check64(spirv, operation, binary64);
                comparisons += operationCounts[operation];
            }
            for (int operation = 9; operation <= 10; operation++) {
                operationCounts[operation] = session.checkUnary64(spirv, operation, unary64);
                comparisons += operationCounts[operation];
            }
            operationCounts[11] = session.checkRng(spirv, rng);
            comparisons += operationCounts[11];
            for (int operation = 12; operation <= 13; operation++) {
                operationCounts[operation] = session.check64(spirv, operation, binary64);
                comparisons += operationCounts[operation];
            }
            operationCounts[14] = session.checkUnary64(spirv, 14, unary64);
            comparisons += operationCounts[14];
            for (int operation = 15; operation <= 17; operation++) {
                operationCounts[operation] = session.checkInteger32(spirv, operation, binary32);
                comparisons += operationCounts[operation];
            }
            operationCounts[18] = session.checkIntTo64(spirv, binary32);
            comparisons += operationCounts[18];
            if (!arithmeticOnly) {
                runCapturedNoise(session, release);
                runCapturedEndIsland(session, release);
                runCapturedWeirdScaled(session, release);
            }
            String sourceHash = Hashes.sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String spirvHash = Hashes.sha256(spirv);
            String report = report(session.name(), sourceHash, spirvHash, comparisons, operationCounts,
                    (arithmeticOnly ? (release ? "release-arithmetic-only" : "arithmetic-only")
                            : (release ? "release" : "qualification"))
                            + (nanSignAgnostic ? "-host-nan-sign-agnostic" : ""));
            String output = outputPath(args);
            if (output != null) {
                Path reportPath = Path.of(output).toAbsolutePath().normalize();
                Path parent = reportPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(reportPath, report);
            }
            System.out.println("nativeIntegerIeee DEVICE_PASS device=" + session.name()
                    + " comparisons=" + comparisons + " sourceSha256=" + sourceHash
                    + (nanSignAgnostic ? " nanSignDifferences=" + nanSignDifferences : "")
                    + (output == null ? "" : " report=" + Path.of(output).toAbsolutePath().normalize()));
        }
    }

    private static boolean capturedBitsMatch(long expectedBits, long actualBits) {
        if (expectedBits == actualBits) return true;
        if (nanSignAgnostic && Double.isNaN(Double.longBitsToDouble(expectedBits))
                && Double.isNaN(Double.longBitsToDouble(actualBits))) {
            nanSignDifferences++;
            return true;
        }
        return false;
    }

    private static String outputPath(String[] args) {
        for (String arg : args) if (arg.startsWith("--output=")) return arg.substring("--output=".length());
        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) if (flag.equals(arg)) return true;
        return false;
    }

    private static String report(String device, String sourceHash, String spirvHash,
                                 int comparisons, int[] operationCounts, String campaign) {
        String[] names = {"fp32.add", "fp32.subtract", "fp32.multiply", "fp32.divide", "floor.fp32", "sqrt.fp32",
                "fp64.add", "fp64.multiply", "fp64.divide", "floor.fp64", "sqrt.fp64", "rng.xoroshiro",
                "i64.add", "i64.subtract", "i64.negate", "i32.add", "i32.subtract", "i32.multiply",
                "fp64.from_int"};
        StringBuilder json = new StringBuilder("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"status\": \"PASS\",\n")
                .append("  \"campaign\": \"").append(campaign).append("\",\n")
                .append("  \"profile\": \"GPU_IEEE_BITS\",\n")
                .append("  \"device\": \"").append(jsonEscape(device)).append("\",\n")
                .append("  \"sourceSha256\": \"").append(sourceHash).append("\",\n")
                .append("  \"spirvSha256\": \"").append(spirvHash).append("\",\n")
                .append("  \"comparisons\": ").append(comparisons).append(",\n")
                .append("  \"mismatches\": 0,\n")
                .append(nanSignAgnostic ? "  \"nanSignDifferences\": " + nanSignDifferences + ",\n" : "")
                .append("  \"operations\": {");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) json.append(',');
            json.append("\n    \"").append(names[i]).append("\": ").append(operationCounts[i]);
        }
        return json.append("\n  }\n}\n").toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String shaderSource() {
        return """
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                layout(push_constant) uniform Dispatch { uint count; uint operation; } dispatch;
                bool wg_failed;
                """ + IntegerIeeeEmitter.source() + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * 4u, outputBase = index * 6u;
                    if (dispatch.operation <= 5u) {
                        uint left = inputBits[inputBase], right = inputBits[inputBase + 1u], value;
                        if (dispatch.operation == 0u) value = wg_fp32_add(left, right);
                        else if (dispatch.operation == 1u) value = wg_fp32_sub(left, right);
                        else if (dispatch.operation == 2u) value = wg_fp32_mul(left, right);
                        else if (dispatch.operation == 3u) value = wg_fp32_div(left, right);
                        else if (dispatch.operation == 4u) value = wg_fp32_floor(left);
                        else value = wg_fp32_sqrt(left);
                        outputBits[outputBase] = value;
                        outputBits[outputBase + 1u] = 0u;
                        outputBits[outputBase + 2u] = 0u;
                        outputBits[outputBase + 3u] = 0u;
                        outputBits[outputBase + 4u] = 0u;
                        outputBits[outputBase + 5u] = 0u;
                    } else if (dispatch.operation <= 10u) {
                        uvec2 left = uvec2(inputBits[inputBase], inputBits[inputBase + 1u]);
                        uvec2 right = uvec2(inputBits[inputBase + 2u], inputBits[inputBase + 3u]);
                        uvec2 value;
                        if (dispatch.operation == 6u) value = wg_fp64_add(left, right);
                        else if (dispatch.operation == 7u) value = wg_fp64_mul(left, right);
                        else if (dispatch.operation == 8u) value = wg_fp64_div(left, right);
                        else if (dispatch.operation == 9u) value = wg_fp64_floor(left);
                        else value = wg_fp64_sqrt(left);
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                        outputBits[outputBase + 2u] = 0u;
                        outputBits[outputBase + 3u] = 0u;
                        outputBits[outputBase + 4u] = 0u;
                        outputBits[outputBase + 5u] = 0u;
                    } else if (dispatch.operation == 11u) {
                        uvec2 stateLo = uvec2(inputBits[inputBase], inputBits[inputBase + 1u]);
                        uvec2 stateHi = uvec2(inputBits[inputBase + 2u], inputBits[inputBase + 3u]);
                        uvec2 value = wg_xoroshiro128pp_next(stateLo, stateHi);
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                        outputBits[outputBase + 2u] = stateLo.x;
                        outputBits[outputBase + 3u] = stateLo.y;
                        outputBits[outputBase + 4u] = stateHi.x;
                        outputBits[outputBase + 5u] = stateHi.y;
                    } else if (dispatch.operation <= 14u) {
                        uvec2 left = uvec2(inputBits[inputBase], inputBits[inputBase + 1u]);
                        uvec2 right = uvec2(inputBits[inputBase + 2u], inputBits[inputBase + 3u]);
                        uvec2 value = dispatch.operation == 12u
                                ? wg_i64_add(left, right)
                                : (dispatch.operation == 13u ? wg_i64_sub(left, right) : wg_i64_negate(left));
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                        outputBits[outputBase + 2u] = 0u;
                        outputBits[outputBase + 3u] = 0u;
                        outputBits[outputBase + 4u] = 0u;
                        outputBits[outputBase + 5u] = 0u;
                    } else if (dispatch.operation <= 17u) {
                        uint left = inputBits[inputBase], right = inputBits[inputBase + 2u], value;
                        if (dispatch.operation == 15u) value = left + right;
                        else if (dispatch.operation == 16u) value = left - right;
                        else value = left * right;
                        outputBits[outputBase] = value;
                        outputBits[outputBase + 1u] = 0u;
                        outputBits[outputBase + 2u] = 0u;
                        outputBits[outputBase + 3u] = 0u;
                        outputBits[outputBase + 4u] = 0u;
                        outputBits[outputBase + 5u] = 0u;
                    } else {
                        uvec2 value = wg_fp64_from_int(int(inputBits[inputBase]));
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                        outputBits[outputBase + 2u] = 0u;
                        outputBits[outputBase + 3u] = 0u;
                        outputBits[outputBase + 4u] = 0u;
                        outputBits[outputBase + 5u] = 0u;
                    }
                }
                """;
    }

    /**
     * Device smoke for a captured graph node.  This intentionally uses a
     * separate shader and report line from the primitive campaign: it proves
     * seeded-table lookup and graph emission without pretending that one noise
     * node is a complete Minecraft router/material replay.
     */
    private static void runCapturedNoise(Session session, boolean release) {
        String source = capturedNoiseShaderSource();
        byte[] spirv = new ShadercCompiler().compile(source);
        var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        if (!inspection.valid()) throw new IllegalStateException("Captured noise SPIR-V violates integer profile: " + inspection.violations());
        int count = release ? 4096 : 512;
        int[] input = new int[count * 4];
        for (int i = 0; i < count; i++) {
            input[i * 4] = ((i * 37) & 4095) - 2048;
            input[i * 4 + 1] = ((i * 19) & 1023) - 512;
            input[i * 4 + 2] = ((i * 53) & 4095) - 2048;
        }
        if (count >= 6) {
            // Exercise PerlinNoise.wrap at both signs and beyond the exact
            // 2^25 period; ordinary nearby points never distinguish a wrong
            // period constant in the emitted shader.
            input[0] = Integer.MAX_VALUE; input[1] = Integer.MAX_VALUE; input[2] = Integer.MIN_VALUE;
            input[4] = Integer.MIN_VALUE; input[5] = Integer.MIN_VALUE; input[6] = Integer.MAX_VALUE;
            input[8] = 33_554_432; input[10] = -33_554_432;
            input[12] = 1_000_000_000; input[14] = -1_000_000_000;
            input[16] = -123_456_789; input[18] = 987_654_321;
        }
        int[] actual = session.dispatch(spirv, 0, input, count);
        for (int i = 0; i < count; i++) {
            long actualBits = Integer.toUnsignedLong(actual[i * 6])
                    | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
            int x = input[i * 4], y = input[i * 4 + 1], z = input[i * 4 + 2];
            long expectedBits = Double.doubleToRawLongBits(capturedNoiseExpected(x, y, z));
            if (!capturedBitsMatch(expectedBits, actualBits)) {
                throw new IllegalStateException("captured NormalNoise mismatch index=" + i
                        + " point=" + x + "," + y + "," + z
                        + " expected=0x" + Long.toHexString(expectedBits)
                        + " actual=0x" + Long.toHexString(actualBits));
            }
        }
        System.out.println("capturedNormalNoise DEVICE_PASS comparisons=" + count
                + " spirvSha256=" + Hashes.sha256(spirv));
    }

    private static void runCapturedEndIsland(Session session, boolean release) {
        String source = capturedEndIslandShaderSource();
        byte[] spirv = new ShadercCompiler().compile(source);
        var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        if (!inspection.valid()) throw new IllegalStateException("Captured End-island SPIR-V violates integer profile: " + inspection.violations());
        int count = release ? 512 : 256;
        int[] input = new int[count * 4];
        for (int i = 0; i < count; i++) {
            input[i * 4] = ((i * 97) & 16383) - 8192;
            input[i * 4 + 1] = ((i * 29) & 2047) - 1024;
            input[i * 4 + 2] = ((i * 131) & 16383) - 8192;
        }
        if (count >= 6) {
            input[0] = 0; input[2] = 0;
            input[4] = 7; input[6] = -7;
            input[8] = Integer.MAX_VALUE; input[10] = Integer.MIN_VALUE;
            input[12] = 1_000_000_000; input[14] = -1_000_000_000;
            input[16] = 123456789; input[18] = -987654321;
        }
        int[] actual = session.dispatch(spirv, 0, input, count);
        EndIslandParameters parameters = capturedEndIslandParameters();
        for (int i = 0; i < count; i++) {
            long actualBits = Integer.toUnsignedLong(actual[i * 6])
                    | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
            int x = input[i * 4], z = input[i * 4 + 2];
            long expectedBits = Double.doubleToRawLongBits(capturedEndIslandExpected(parameters, x, z));
            if (!capturedBitsMatch(expectedBits, actualBits)) {
                throw new IllegalStateException("captured End-island mismatch index=" + i
                        + " point=" + x + "," + z
                        + " expected=0x" + Long.toHexString(expectedBits)
                        + " actual=0x" + Long.toHexString(actualBits));
            }
        }
        System.out.println("capturedEndIsland DEVICE_PASS comparisons=" + count
                + " spirvSha256=" + Hashes.sha256(spirv));
    }

    private static String capturedEndIslandShaderSource() {
        var node = new ProgramNode.EndIsland(capturedEndIslandParameters(), ValueType.FP64, EvaluationDomain.WORLD);
        String generated = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", node).build(),
                NumericProfile.GPU_IEEE_BITS, LOCAL_SIZE).source();
        int mainMarker = generated.indexOf("// bounds check is mandatory");
        if (mainMarker < 0) throw new IllegalStateException("Generated End-island shader has no main marker");
        String prefix = generated.substring(0, mainMarker)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = wg_node_0(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                    outputBits[outputBase + 4u] = 0u;
                    outputBits[outputBase + 5u] = 0u;
                }
                """;
    }

    private static void runCapturedWeirdScaled(Session session, boolean release) {
        int count = release ? 512 : 256;
        int[] input = new int[count * 4];
        for (int i = 0; i < count; i++) {
            input[i * 4] = ((i * 97) & 16383) - 8192;
            input[i * 4 + 1] = ((i * 29) & 2047) - 1024;
            input[i * 4 + 2] = ((i * 131) & 16383) - 8192;
        }
        if (count >= 7) {
            input[0] = -2; input[2] = 3;
            input[4] = -1; input[6] = 5;
            input[8] = 0; input[10] = -7;
            input[12] = 1; input[14] = 11;
            input[16] = 2; input[18] = 13;
            input[20] = 4; input[22] = -17;
            input[24] = 8; input[26] = 19;
        }
        NoiseParameters parameters = capturedNoiseParameters();
        for (String mapper : List.of("TYPE1", "TYPE2")) {
            byte[] spirv = new ShadercCompiler().compile(capturedWeirdScaledShaderSource(mapper));
            var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                    .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
            if (!inspection.valid()) throw new IllegalStateException("Captured weird-scaled SPIR-V violates integer profile: " + inspection.violations());
            int[] actual = session.dispatch(spirv, 0, input, count);
            for (int i = 0; i < count; i++) {
                int x = input[i * 4], y = input[i * 4 + 1], z = input[i * 4 + 2];
                double selector = (double) x / 2.0;
                double rarity = mapper.equals("TYPE1") ? rarityType1(selector) : rarityType2(selector);
                double expected = rarity * Math.abs(capturedNormalRawExpected(x / rarity,
                        y / rarity, z / rarity));
                long expectedBits = Double.doubleToRawLongBits(expected);
                long actualBits = Integer.toUnsignedLong(actual[i * 6])
                        | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
                if (!capturedBitsMatch(expectedBits, actualBits)) {
                    throw new IllegalStateException("captured " + mapper + " weird-scaled mismatch index=" + i
                            + " point=" + x + "," + y + "," + z
                            + " expected=0x" + Long.toHexString(expectedBits)
                            + " actual=0x" + Long.toHexString(actualBits));
                }
            }
            System.out.println("capturedWeirdScaled " + mapper + " DEVICE_PASS comparisons=" + count
                    + " spirvSha256=" + Hashes.sha256(spirv));
        }
    }

    private static String capturedWeirdScaledShaderSource(String mapper) {
        var selector = new ProgramNode.Binary("divide", ValueType.FP64, EvaluationDomain.WORLD,
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP64, 2.0, EvaluationDomain.WORLD));
        var node = new ProgramNode.WeirdScaledSampler(selector, capturedNoiseParameters(), mapper,
                ValueType.FP64, EvaluationDomain.WORLD);
        String generated = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", node).build(),
                NumericProfile.GPU_IEEE_BITS, LOCAL_SIZE).source();
        int mainMarker = generated.indexOf("// bounds check is mandatory");
        if (mainMarker < 0) throw new IllegalStateException("Generated weird-scaled shader has no main marker");
        String prefix = generated.substring(0, mainMarker)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = wg_node_0(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                    outputBits[outputBase + 4u] = 0u;
                    outputBits[outputBase + 5u] = 0u;
                }
        """;
    }

    private static void runCapturedBlendDensity(Session session, boolean release) {
        int count = release ? 32 : 16;
        int[] input = new int[count * 4];
        for (int i = 0; i < count; i++) {
            input[i * 4] = i * 4;
            input[i * 4 + 1] = (i & 3) * 8;
            input[i * 4 + 2] = (i % 5) * 4;
        }
        // Exercise the three direct section probes, a weighted sample and an
        // empty neighbourhood.  The source-local direct sample is the
        // previous-X boundary selected by Blender when localX == 0.
        if (count >= 4) {
            input[0] = 0; input[1] = 0; input[2] = 0;
            input[4] = 4; input[5] = 0; input[6] = 0;
            input[8] = 8; input[9] = 0; input[10] = 0;
            input[12] = 400; input[13] = 0; input[14] = 400;
        }
        StructureBlendSnapshot snapshot = capturedBlendDensitySnapshot();
        byte[] spirv = new ShadercCompiler().compile(capturedBlendDensityShaderSource(snapshot));
        var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        if (!inspection.valid()) throw new IllegalStateException("Captured blend-density SPIR-V violates integer profile: " + inspection.violations());
        int[] actual = session.dispatch(spirv, 0, input, count);
        for (int i = 0; i < count; i++) {
            int x = input[i * 4], y = input[i * 4 + 1], z = input[i * 4 + 2];
            long expectedBits = Double.doubleToRawLongBits(capturedBlendDensityExpected(x, y, z));
            long actualBits = Integer.toUnsignedLong(actual[i * 6])
                    | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
            if (actualBits != expectedBits) {
                throw new IllegalStateException("captured blend-density mismatch index=" + i
                        + " point=" + x + "," + y + "," + z
                        + " expected=0x" + Long.toHexString(expectedBits)
                        + " actual=0x" + Long.toHexString(actualBits));
            }
        }
        System.out.println("capturedBlendDensity DEVICE_PASS comparisons=" + count
                + " spirvSha256=" + Hashes.sha256(spirv));
    }

    private static StructureBlendSnapshot capturedBlendDensitySnapshot() {
        return new StructureBlendSnapshot("captured-boundary", java.util.Map.of(), java.util.Map.of(),
                BeardifierSnapshot.empty(),
                java.util.List.of(new StructureBlendSnapshot.DensitySample(2, 0, 0, 3.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectDensitySample(-1, 0, 4, 0, 0, 1.0)));
    }

    private static String capturedBlendDensityShaderSource(StructureBlendSnapshot snapshot) {
        var node = new ProgramNode.BlendDensity(
                new ProgramNode.Constant(ValueType.FP64, 5.0, EvaluationDomain.BLOCK),
                ValueType.FP64, EvaluationDomain.BLOCK);
        String generated = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", node).build(),
                NumericProfile.GPU_IEEE_BITS, LOCAL_SIZE, snapshot).source();
        int mainMarker = generated.indexOf("// bounds check is mandatory");
        if (mainMarker < 0) throw new IllegalStateException("Generated blend-density shader has no main marker");
        String prefix = generated.substring(0, mainMarker)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = wg_node_0(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                    outputBits[outputBase + 4u] = 0u;
                    outputBits[outputBase + 5u] = 0u;
                }
                """;
    }

    private static double capturedBlendDensityExpected(int blockX, int blockY, int blockZ) {
        int quartX = Math.floorDiv(blockX, 4), cellY = blockY / 8, quartZ = Math.floorDiv(blockZ, 4);
        if (quartX == 0 && cellY == 0 && quartZ == 0) return 1.0;
        double current = 5.0;
        if (cellY < -1 || cellY > 0) return current;
        double distance = Math.sqrt((double) (quartX - 2) * (quartX - 2) + (double) quartZ * quartZ);
        if (distance > 2.0) return current;
        if (distance == 0.0) return 3.0;
        double weight = 1.0 / (distance * distance * distance * distance);
        double blend = Math.max(0.0, Math.min(1.0, distance / 3.0));
        return 3.0 + blend * (current - 3.0);
    }

    private static void runCapturedBlendedNoise(Session session, boolean release) {
        int count = release ? 8 : 4;
        int[] input = new int[count * 4];
        for (int i = 0; i < count; i++) {
            input[i * 4] = (i * 13) - 400;
            input[i * 4 + 1] = ((i * 7) & 63) - 32;
            input[i * 4 + 2] = (i * 17) - 500;
        }
        if (count >= 4) {
            input[0] = 0; input[2] = 0;
            input[4] = 1; input[6] = -1;
            input[8] = 4; input[10] = 7;
            input[12] = -19; input[14] = 23;
        }
        if (count >= 8) {
            input[16] = 87; input[18] = -41;
            input[20] = 399; input[22] = -499;
        }
        BlendedNoiseParameters parameters = capturedBlendedNoiseParameters();
        byte[] spirv = new ShadercCompiler().compile(capturedBlendedNoiseShaderSource(parameters));
        var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        if (!inspection.valid()) throw new IllegalStateException("Captured blended-noise SPIR-V violates integer profile: " + inspection.violations());
        int[] actual = session.dispatch(spirv, 0, input, count);
        for (int i = 0; i < count; i++) {
            int x = input[i * 4], y = input[i * 4 + 1], z = input[i * 4 + 2];
            long expectedBits = Double.doubleToRawLongBits(capturedBlendedExpected(parameters, x, y, z));
            long actualBits = Integer.toUnsignedLong(actual[i * 6])
                    | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
            if (actualBits != expectedBits) {
                throw new IllegalStateException("captured blended-noise mismatch index=" + i
                        + " point=" + x + "," + y + "," + z
                        + " expected=0x" + Long.toHexString(expectedBits)
                        + " actual=0x" + Long.toHexString(actualBits));
            }
        }
        System.out.println("capturedBlendedNoise DEVICE_PASS comparisons=" + count
                + " spirvSha256=" + Hashes.sha256(spirv));
    }

    private static String capturedBlendedNoiseShaderSource(BlendedNoiseParameters parameters) {
        var node = new ProgramNode.BlendedNoise(parameters, ValueType.FP64, EvaluationDomain.WORLD);
        String generated = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", node).build(),
                NumericProfile.GPU_IEEE_BITS, LOCAL_SIZE).source();
        int mainMarker = generated.indexOf("// bounds check is mandatory");
        if (mainMarker < 0) throw new IllegalStateException("Generated blended-noise shader has no main marker");
        String prefix = generated.substring(0, mainMarker)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = wg_node_0(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                    outputBits[outputBase + 4u] = 0u;
                    outputBits[outputBase + 5u] = 0u;
                }
                """;
    }

    private static BlendedNoiseParameters capturedBlendedNoiseParameters() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var levels = new ArrayList<NoiseParameters.ImprovedNoiseSnapshot>();
        for (int i = 0; i < 4; i++) levels.add(new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation));
        var amplitudes = java.util.Collections.nCopies(4, 1.0);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(3, amplitudes, levels);
        return new BlendedNoiseParameters(1234L, perlin, perlin, perlin,
                1.0, 1.0, 8.555150000000001, 4.277575000000001, 2.0);
    }

    private static double capturedBlendedExpected(BlendedNoiseParameters parameters, int x, int y, int z) {
        double xzMultiplier = 684.412 * parameters.xzScale();
        double yMultiplier = 684.412 * parameters.yScale();
        double d0 = x * xzMultiplier, d1 = y * yMultiplier, d2 = z * xzMultiplier;
        double d3 = d0 / parameters.xzFactor(), d4 = d1 / parameters.yFactor(), d5 = d2 / parameters.xzFactor();
        double d6 = yMultiplier * parameters.smearScaleMultiplier();
        double d7 = d6 / parameters.yFactor();
        double main = 0.0, scale = 1.0;
        for (int octave = 0; octave < 8; octave++) {
            main += capturedLegacyLevel(parameters.mainNoise(), octave,
                    capturedWrap(d3 * scale), capturedWrap(d4 * scale), capturedWrap(d5 * scale),
                    d7 * scale, d4 * scale) / scale;
            scale /= 2.0;
        }
        double blend = (main / 10.0 + 1.0) / 2.0;
        boolean skipMin = blend >= 1.0, skipMax = blend <= 0.0;
        double min = 0.0, max = 0.0;
        scale = 1.0;
        for (int octave = 0; octave < 16; octave++) {
            double d12 = capturedWrap(d0 * scale), d13 = capturedWrap(d1 * scale), d14 = capturedWrap(d2 * scale);
            double d15 = d6 * scale, yMax = d1 * scale;
            if (!skipMin) min += capturedLegacyLevel(parameters.minLimitNoise(), octave, d12, d13, d14, d15, yMax) / scale;
            if (!skipMax) max += capturedLegacyLevel(parameters.maxLimitNoise(), octave, d12, d13, d14, d15, yMax) / scale;
            scale /= 2.0;
        }
        double lower = min / 512.0, upper = max / 512.0;
        double clamped = Math.max(0.0, Math.min(1.0, blend));
        return (lower + clamped * (upper - lower)) / 128.0;
    }

    private static double capturedLegacyLevel(NoiseParameters.PerlinNoiseSnapshot noise, int octave,
                                              double x, double y, double z, double yScale, double yMax) {
        int levelIndex = noise.levels().size() - 1 - octave;
        if (levelIndex < 0 || levelIndex >= noise.levels().size() || noise.levels().get(levelIndex) == null) return 0.0;
        return capturedSmearedIdentity(noise.levels().get(levelIndex), x, y, z, yScale, yMax);
    }

    private static double capturedSmearedIdentity(NoiseParameters.ImprovedNoiseSnapshot noise,
                                                  double x, double y, double z, double yScale, double yMax) {
        double shiftedX = x + noise.xOffset(), shiftedY = y + noise.yOffset(), shiftedZ = z + noise.zOffset();
        int x0 = (int) Math.floor(shiftedX), y0 = (int) Math.floor(shiftedY), z0 = (int) Math.floor(shiftedZ);
        double fx = shiftedX - x0, fy = shiftedY - y0, fz = shiftedZ - z0;
        double adjustedFy = fy;
        if (yScale != 0.0) {
            double clamped = yMax >= 0.0 && yMax < fy ? yMax : fy;
            double smear = Math.floor(clamped / yScale + (double) 1.0e-7f) * yScale;
            adjustedFy = fy - smear;
        }
        var permutation = noise.permutation();
        int xHash = permutation.get(x0 & 255), xHashNext = permutation.get((x0 + 1) & 255);
        int xy00 = permutation.get((xHash + y0) & 255), xy01 = permutation.get((xHash + y0 + 1) & 255);
        int xy10 = permutation.get((xHashNext + y0) & 255), xy11 = permutation.get((xHashNext + y0 + 1) & 255);
        double v000 = identityGradient(permutation.get((xy00 + z0) & 255), fx, adjustedFy, fz);
        double v100 = identityGradient(permutation.get((xy10 + z0) & 255), fx - 1.0, adjustedFy, fz);
        double v010 = identityGradient(permutation.get((xy01 + z0) & 255), fx, adjustedFy - 1.0, fz);
        double v110 = identityGradient(permutation.get((xy11 + z0) & 255), fx - 1.0, adjustedFy - 1.0, fz);
        double v001 = identityGradient(permutation.get((xy00 + z0 + 1) & 255), fx, adjustedFy, fz - 1.0);
        double v101 = identityGradient(permutation.get((xy10 + z0 + 1) & 255), fx - 1.0, adjustedFy, fz - 1.0);
        double v011 = identityGradient(permutation.get((xy01 + z0 + 1) & 255), fx, adjustedFy - 1.0, fz - 1.0);
        double v111 = identityGradient(permutation.get((xy11 + z0 + 1) & 255), fx - 1.0, adjustedFy - 1.0, fz - 1.0);
        double bx = smoothstep(fx), by = smoothstep(fy), bz = smoothstep(fz);
        return lerp(bz, lerp(by, lerp(bx, v000, v100), lerp(bx, v010, v110)),
                lerp(by, lerp(bx, v001, v101), lerp(bx, v011, v111)));
    }

    private static double capturedWrap(double value) {
        return value - Math.floor(value / 33554432.0 + 0.5) * 33554432.0;
    }

    private static double rarityType1(double value) {
        if (value < -0.5) return 0.75;
        if (value < 0.0) return 1.0;
        return value < 0.5 ? 1.5 : 2.0;
    }

    private static double rarityType2(double value) {
        if (value < -0.75) return 0.5;
        if (value < -0.5) return 0.75;
        if (value < 0.5) return 1.0;
        return value < 0.75 ? 2.0 : 3.0;
    }

    private static EndIslandParameters capturedEndIslandParameters() {
        return new EndIslandParameters(0.0, 0.0,
                java.util.stream.IntStream.range(0, 256).boxed().toList());
    }

    private static double capturedEndIslandExpected(EndIslandParameters parameters, int blockX, int blockZ) {
        int x = blockX / 8, z = blockZ / 8;
        int gridX = x / 2, gridZ = z / 2;
        int remainderX = x % 2, remainderZ = z % 2;
        int distanceSquared = x * x + z * z;
        float height = 100.0f - (float) Math.sqrt((float) distanceSquared) * 8.0f;
        height = Math.max(-100.0f, Math.min(80.0f, height));
        for (int offsetX = -12; offsetX <= 12; offsetX++) {
            for (int offsetZ = -12; offsetZ <= 12; offsetZ++) {
                long islandX = (long) gridX + offsetX;
                long islandZ = (long) gridZ + offsetZ;
                if (islandX * islandX + islandZ * islandZ > 4096L
                        && simplex(parameters, (double) islandX, (double) islandZ) < -0.9f) {
                    float islandScale = Math.abs((float) islandX) * 3439.0f
                            + Math.abs((float) islandZ) * 147.0f;
                    islandScale %= 13.0f;
                    islandScale += 9.0f;
                    float localX = (float) (remainderX - offsetX * 2);
                    float localZ = (float) (remainderZ - offsetZ * 2);
                    float islandHeight = 100.0f - (float) Math.sqrt(localX * localX + localZ * localZ) * islandScale;
                    islandHeight = Math.max(-100.0f, Math.min(80.0f, islandHeight));
                    height = Math.max(height, islandHeight);
                }
            }
        }
        return ((double) height - 8.0) / 128.0;
    }

    private static double simplex(EndIslandParameters parameters, double x, double y) {
        double f2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        double g2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double skew = (x + y) * f2;
        int latticeX = (int) Math.floor(x + skew), latticeY = (int) Math.floor(y + skew);
        double unskew = (latticeX + latticeY) * g2;
        double x0 = x - (latticeX - unskew), y0 = y - (latticeY - unskew);
        int offsetX = x0 > y0 ? 1 : 0, offsetY = x0 > y0 ? 0 : 1;
        double x1 = x0 - offsetX + g2, y1 = y0 - offsetY + g2;
        double x2 = x0 - 1.0 + 2.0 * g2, y2 = y0 - 1.0 + 2.0 * g2;
        var permutation = parameters.permutation();
        int hash0 = permutation.get((latticeX & 255) + permutation.get(latticeY & 255) & 255) % 12;
        int hash1 = permutation.get((latticeX & 255) + offsetX
                + permutation.get((latticeY & 255) + offsetY & 255) & 255) % 12;
        int hash2 = permutation.get((latticeX & 255) + 1
                + permutation.get((latticeY & 255) + 1 & 255) & 255) % 12;
        return 70.0 * (simplexCorner(hash0, x0, y0) + simplexCorner(hash1, x1, y1)
                + simplexCorner(hash2, x2, y2));
    }

    private static double simplexCorner(int hash, double x, double y) {
        double attenuation = 0.5 - x * x - y * y;
        if (attenuation < 0.0) return 0.0;
        attenuation *= attenuation;
        return attenuation * attenuation * switch (hash) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4, 6 -> x;
            case 5, 7 -> -x;
            case 8, 10 -> y;
            default -> -y;
        };
    }

    private static String capturedNoiseShaderSource() {
        var node = new ProgramNode.Noise("minecraft:test", capturedNoiseParameters(), 0.5, 2.0,
                ValueType.FP64, EvaluationDomain.WORLD);
        String generated = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", node).build(),
                NumericProfile.GPU_IEEE_BITS, LOCAL_SIZE).source();
        int mainMarker = generated.indexOf("// bounds check is mandatory");
        if (mainMarker < 0) throw new IllegalStateException("Generated captured-noise shader has no main marker");
        String prefix = generated.substring(0, mainMarker)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = wg_node_0(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                    outputBits[outputBase + 4u] = 0u;
                    outputBits[outputBase + 5u] = 0u;
                }
                """;
    }

    private static NoiseParameters capturedNoiseParameters() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(1, java.util.List.of(1.0), java.util.List.of(level));
        var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, perlin);
        return new NoiseParameters("minecraft:test", -1, java.util.List.of(1.0), 0L, captured);
    }

    private static double capturedNoiseExpected(int x, int y, int z) {
        double scaledX = x * 0.5, scaledY = y * 2.0, scaledZ = z * 0.5;
        return capturedNormalRawExpected(scaledX, scaledY, scaledZ);
    }

    private static double capturedNormalRawExpected(double x, double y, double z) {
        // PerlinNoise.firstOctave is the signed lowest octave, so the
        // fixture's firstOctave=1 starts at 2^1.  Keep this oracle aligned
        // with NoiseEvaluator and CapturedNoiseEmitter; a half-frequency
        // expectation would silently validate the wrong captured graph.
        double inputFactor = 2.0;
        double factor = 1.0181268882175227;
        return improvedIdentity(capturedWrap(x * inputFactor), capturedWrap(y * inputFactor),
                capturedWrap(z * inputFactor))
                + improvedIdentity(capturedWrap(x * factor * inputFactor),
                capturedWrap(y * factor * inputFactor), capturedWrap(z * factor * inputFactor));
    }

    private static double improvedIdentity(double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = x - x0, fy = y - y0, fz = z - z0;
        int xHash = x0 & 255, xNext = (x0 + 1) & 255;
        int xy00 = (xHash + y0) & 255, xy01 = (xHash + y0 + 1) & 255;
        int xy10 = (xNext + y0) & 255, xy11 = (xNext + y0 + 1) & 255;
        double v000 = identityGradient((xy00 + z0) & 255, fx, fy, fz);
        double v100 = identityGradient((xy10 + z0) & 255, fx - 1.0, fy, fz);
        double v010 = identityGradient((xy01 + z0) & 255, fx, fy - 1.0, fz);
        double v110 = identityGradient((xy11 + z0) & 255, fx - 1.0, fy - 1.0, fz);
        double v001 = identityGradient((xy00 + z0 + 1) & 255, fx, fy, fz - 1.0);
        double v101 = identityGradient((xy10 + z0 + 1) & 255, fx - 1.0, fy, fz - 1.0);
        double v011 = identityGradient((xy01 + z0 + 1) & 255, fx, fy - 1.0, fz - 1.0);
        double v111 = identityGradient((xy11 + z0 + 1) & 255, fx - 1.0, fy - 1.0, fz - 1.0);
        double bx = smoothstep(fx), by = smoothstep(fy), bz = smoothstep(fz);
        double x00 = lerp(bx, v000, v100), x10 = lerp(bx, v010, v110);
        double x01 = lerp(bx, v001, v101), x11 = lerp(bx, v011, v111);
        return lerp(bz, lerp(by, x00, x10), lerp(by, x01, x11));
    }

    private static double identityGradient(int hash, double x, double y, double z) {
        int[][] gradients = {{1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},{1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
                {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},{1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}};
        int[] gradient = gradients[hash & 15];
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }

    private static double smoothstep(double value) { return value * value * value * (value * (value * 6.0 - 15.0) + 10.0); }
    private static double lerp(double t, double left, double right) { return left + t * (right - left); }

    private static List<Integer> vectors32(int count, long initialState) {
        var values = new ArrayList<Integer>(count);
        int[] fixed = {0x00000000, 0x80000000, 0x00000001, 0x80000001, 0x007fffff,
                0x00800000, 0x3f000000, 0x3f800000, 0xbf800000, 0x40000000,
                0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000, 0x7fc00001,
                0xffc00001, 0x4b000000, 0xcb000000};
        for (int value : fixed) values.add(value);
        long state = initialState;
        while (values.size() < count) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int bits = (int) (state >>> 32);
            bits = (bits & 0x807fffff) | (((bits >>> 23) & 0xfe) << 23);
            values.add(bits);
        }
        return List.copyOf(values);
    }

    private static List<Long> vectors64(int count, long initialState) {
        var values = new ArrayList<Long>(count);
        long[] fixed = {0x0000000000000000L, 0x8000000000000000L, 0x0000000000000001L,
                0x8000000000000001L, 0x000fffffffffffffL, 0x0010000000000000L,
                0x3fe0000000000000L, 0x3ff0000000000000L, 0xbff0000000000000L,
                0x4000000000000000L, 0x7fefffffffffffffL, 0xffefffffffffffffL,
                0x7ff0000000000000L, 0xfff0000000000000L, 0x7ff8000000000001L,
                0xfff8000000000001L, 0x4330000000000000L, 0xc330000000000000L};
        for (long value : fixed) values.add(value);
        long state = initialState;
        while (values.size() < count) {
            state = state * 2862933555777941757L + 3037000493L;
            long bits = state;
            bits = (bits & 0x800fffffffffffffL) | (((bits >>> 52) & 0x7feL) << 52);
            values.add(bits);
        }
        return List.copyOf(values);
    }

    private static List<RngVector> rngVectors(int count, long initialState) {
        var values = new ArrayList<RngVector>(count);
        long state = initialState;
        for (int i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            long stateLo = state;
            state = state * 6364136223846793005L + 1442695040888963407L;
            long stateHi = state;
            values.add(new RngVector(stateLo, stateHi));
        }
        return List.copyOf(values);
    }

    private static int expected32(int operation, int leftBits, int rightBits) {
        float left = Float.intBitsToFloat(leftBits), right = Float.intBitsToFloat(rightBits);
        float value = switch (operation) {
            case 0 -> left + right;
            case 1 -> left - right;
            case 2 -> left * right;
            case 3 -> left / right;
            case 4 -> (float) Math.floor(left);
            case 5 -> (float) Math.sqrt(left);
            default -> throw new IllegalArgumentException("Unknown binary32 operation " + operation);
        };
        return Float.floatToRawIntBits(value);
    }

    private static long expected64(int operation, long leftBits, long rightBits) {
        double left = Double.longBitsToDouble(leftBits), right = Double.longBitsToDouble(rightBits);
        double value = switch (operation) {
            case 6 -> left + right;
            case 7 -> left * right;
            case 8 -> left / right;
            case 9 -> Math.floor(left);
            case 10 -> Math.sqrt(left);
            default -> throw new IllegalArgumentException("Unknown binary64 operation " + operation);
        };
        return Double.doubleToRawLongBits(value);
    }

    private static long expectedInteger64(int operation, long left, long right) {
        return switch (operation) {
            case 12 -> left + right;
            case 13 -> left - right;
            case 14 -> -left;
            default -> throw new IllegalArgumentException("Unknown integer64 operation " + operation);
        };
    }

    private static int expectedInteger32(int operation, int left, int right) {
        return switch (operation) {
            case 15 -> left + right;
            case 16 -> left - right;
            case 17 -> left * right;
            default -> throw new IllegalArgumentException("Unknown integer32 operation " + operation);
        };
    }

    private static RngResult expectedRng(RngVector vector) {
        long i = vector.stateLo();
        long j = vector.stateHi();
        long result = Long.rotateLeft(i + j, 17) + i;
        j ^= i;
        return new RngResult(result, Long.rotateLeft(i, 49) ^ j ^ (j << 21), Long.rotateLeft(j, 28));
    }

    private static boolean equal32(int expected, int actual) {
        if (Float.isNaN(Float.intBitsToFloat(expected))) return Float.isNaN(Float.intBitsToFloat(actual));
        return expected == actual;
    }

    private static boolean equal64(long expected, long actual) {
        if (Double.isNaN(Double.longBitsToDouble(expected))) return Double.isNaN(Double.longBitsToDouble(actual));
        return expected == actual;
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + status);
    }

    private static final class Session implements AutoCloseable {
        private VkInstance instance;
        private VkDevice device;
        private VkPhysicalDevice physical;
        private VkQueue queue;
        private int queueFamily;
        private String name;
        private final List<VkPhysicalDevice> devices = new ArrayList<>();
        private final List<DeviceCapabilities> capabilities = new ArrayList<>();
        /** Enables {@code shaderInt64} (the fast helpers use 64-bit integers); fails closed when the device lacks it. */
        boolean enableInt64;

        Session() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var application = VkApplicationInfo.calloc(stack).sType$Default()
                        .pApplicationName(stack.UTF8("Tellurium synthetic replay"))
                        .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                        .apiVersion(VK10.VK_MAKE_VERSION(1, 2, 0));
                var portabilityInstance = dev.tellurium.runtime.vulkan.VulkanPortability.instanceExtensionsToEnable(stack);
                var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application)
                        .flags(dev.tellurium.runtime.vulkan.VulkanPortability.instanceFlags(portabilityInstance))
                        .ppEnabledExtensionNames(portabilityInstance);
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateInstance(create, null, out), "vkCreateInstance");
                instance = new VkInstance(out.get(0), create);
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices/count");
                if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices");
                PointerBuffer physicalDevices = stack.mallocPointer(count.get(0));
                check(vkEnumeratePhysicalDevices(instance, count, physicalDevices), "vkEnumeratePhysicalDevices");
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDevice candidate = new VkPhysicalDevice(physicalDevices.get(i), instance);
                    devices.add(candidate);
                    capabilities.add(inspect(candidate, stack));
                }
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        String name() { return name; }

        void selectAndOpen() {
            int selected = -1;
            for (int i = 0; i < capabilities.size(); i++) {
                DeviceCapabilities value = capabilities.get(i);
                if ((value.deviceType() == 1 || value.deviceType() == 2 || value.deviceType() == 3)
                        && Integer.compareUnsigned(value.apiVersion(), VK10.VK_MAKE_VERSION(1, 2, 0)) >= 0
                        && value.computeQueueFamily() >= 0
                        && (selected < 0 || value.deviceType() == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)) selected = i;
            }
            if (selected < 0) throw new IllegalStateException("No physical compute Vulkan device for integer profile: " + capabilities);
            physical = devices.get(selected);
            DeviceCapabilities selectedCapabilities = capabilities.get(selected);
            name = selectedCapabilities.name();
            queueFamily = selectedCapabilities.computeQueueFamily();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var queues = org.lwjgl.vulkan.VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
                var enabledFeatures = VkPhysicalDeviceFeatures.calloc(stack);
                if (enableInt64) {
                    var supported = VkPhysicalDeviceFeatures.calloc(stack);
                    VK10.vkGetPhysicalDeviceFeatures(physical, supported);
                    if (!supported.shaderInt64()) throw new IllegalStateException("shaderInt64 not supported by " + name);
                    enabledFeatures.shaderInt64(true);
                }
                var create = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                        .pEnabledFeatures(enabledFeatures)
                        .ppEnabledExtensionNames(dev.tellurium.runtime.vulkan.VulkanPortability.deviceExtensionsToEnable(physical, stack));
                PointerBuffer out = stack.mallocPointer(1);
                check(vkCreateDevice(physical, create, null, out), "vkCreateDevice(integer profile)");
                device = new VkDevice(out.get(0), physical, create);
                vkGetDeviceQueue(device, queueFamily, 0, out);
                queue = new VkQueue(out.get(0), device);
            }
        }

        int check32(byte[] spirv, int operation, List<Integer> values) {
            int count = values.size() * values.size();
            int[] input = new int[count * 4];
            int[] expected = new int[count];
            int index = 0;
            for (int i = 0; i < values.size(); i++) for (int j = 0; j < values.size(); j++) {
                int left = values.get(i), right = values.get(j);
                input[index * 4] = left;
                input[index * 4 + 1] = right;
                expected[index] = expected32(operation, left, right);
                index++;
            }
            int[] actual = dispatch(spirv, operation, input, count);
            for (int i = 0; i < count; i++) if (!equal32(expected[i], actual[i * 6])) {
                throw new IllegalStateException("binary32 operation=" + operation + " mismatch index=" + i
                        + " expected=0x" + Integer.toHexString(expected[i])
                        + " actual=0x" + Integer.toHexString(actual[i * 6]));
            }
            return count;
        }

        int checkUnary32(byte[] spirv, int operation, List<Integer> values) {
            int count = values.size();
            int[] input = new int[count * 4];
            int[] expected = new int[count];
            for (int i = 0; i < count; i++) {
                int left = values.get(i);
                input[i * 4] = left;
                expected[i] = expected32(operation, left, 0);
            }
            int[] actual = dispatch(spirv, operation, input, count);
            for (int i = 0; i < count; i++) if (!equal32(expected[i], actual[i * 6])) {
                throw new IllegalStateException("unary32 operation=" + operation + " mismatch index=" + i
                        + " expected=0x" + Integer.toHexString(expected[i])
                        + " actual=0x" + Integer.toHexString(actual[i * 6]));
            }
            return count;
        }

        int check64(byte[] spirv, int operation, List<Long> values) {
            int count = values.size() * values.size();
            int[] input = new int[count * 4];
            long[] expected = new long[count];
            int index = 0;
            for (int i = 0; i < values.size(); i++) for (int j = 0; j < values.size(); j++) {
                long left = values.get(i), right = values.get(j);
                input[index * 4] = (int) left;
                input[index * 4 + 1] = (int) (left >>> 32);
                input[index * 4 + 2] = (int) right;
                input[index * 4 + 3] = (int) (right >>> 32);
                expected[index] = operation <= 10 ? expected64(operation, left, right)
                        : expectedInteger64(operation, left, right);
                index++;
            }
            int[] actual = dispatch(spirv, operation, input, count);
            for (int i = 0; i < count; i++) {
                long actualBits = Integer.toUnsignedLong(actual[i * 6])
                        | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
                if (!equal64(expected[i], actualBits)) {
                    throw new IllegalStateException("integer64/binary64 operation=" + operation + " mismatch index=" + i
                            + " expected=0x" + Long.toHexString(expected[i])
                            + " actual=0x" + Long.toHexString(actualBits));
                }
            }
            return count;
        }

        int checkUnary64(byte[] spirv, int operation, List<Long> values) {
            int count = values.size();
            int[] input = new int[count * 4];
            long[] expected = new long[count];
            for (int i = 0; i < count; i++) {
                long left = values.get(i);
                input[i * 4] = (int) left;
                input[i * 4 + 1] = (int) (left >>> 32);
                expected[i] = operation <= 10 ? expected64(operation, left, 0L)
                        : expectedInteger64(operation, left, 0L);
            }
            int[] actual = dispatch(spirv, operation, input, count);
            for (int i = 0; i < count; i++) {
                long actualBits = Integer.toUnsignedLong(actual[i * 6])
                        | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
                if (!equal64(expected[i], actualBits)) {
                    throw new IllegalStateException("unary64 operation=" + operation + " mismatch index=" + i
                            + " expected=0x" + Long.toHexString(expected[i])
                            + " actual=0x" + Long.toHexString(actualBits));
                }
            }
            return count;
        }

        int checkInteger32(byte[] spirv, int operation, List<Integer> values) {
            int count = values.size() * values.size();
            int[] input = new int[count * 4];
            int[] expected = new int[count];
            int index = 0;
            for (int i = 0; i < values.size(); i++) for (int j = 0; j < values.size(); j++) {
                int left = values.get(i), right = values.get(j);
                input[index * 4] = left;
                input[index * 4 + 2] = right;
                expected[index] = expectedInteger32(operation, left, right);
                index++;
            }
            int[] actual = dispatch(spirv, operation, input, count);
            for (int i = 0; i < count; i++) if (expected[i] != actual[i * 6]) {
                throw new IllegalStateException("integer32 operation=" + operation + " mismatch index=" + i
                        + " expected=0x" + Integer.toHexString(expected[i])
                        + " actual=0x" + Integer.toHexString(actual[i * 6]));
            }
            return count;
        }

        int checkIntTo64(byte[] spirv, List<Integer> values) {
            int count = values.size();
            int[] input = new int[count * 4];
            long[] expected = new long[count];
            for (int i = 0; i < count; i++) {
                int value = values.get(i);
                input[i * 4] = value;
                expected[i] = Double.doubleToRawLongBits((double) value);
            }
            int[] actual = dispatch(spirv, 18, input, count);
            for (int i = 0; i < count; i++) {
                long actualBits = Integer.toUnsignedLong(actual[i * 6])
                        | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
                if (expected[i] != actualBits) {
                    throw new IllegalStateException("fp64.from_int mismatch index=" + i
                            + " value=" + values.get(i)
                            + " expected=0x" + Long.toHexString(expected[i])
                            + " actual=0x" + Long.toHexString(actualBits));
                }
            }
            return count;
        }

        int checkRng(byte[] spirv, List<RngVector> vectors) {
            int count = vectors.size();
            int[] input = new int[count * 4];
            RngResult[] expected = new RngResult[count];
            for (int i = 0; i < count; i++) {
                RngVector vector = vectors.get(i);
                input[i * 4] = (int) vector.stateLo();
                input[i * 4 + 1] = (int) (vector.stateLo() >>> 32);
                input[i * 4 + 2] = (int) vector.stateHi();
                input[i * 4 + 3] = (int) (vector.stateHi() >>> 32);
                expected[i] = expectedRng(vector);
            }
            int[] actual = dispatch(spirv, 11, input, count);
            for (int i = 0; i < count; i++) {
                RngResult result = expected[i];
                long actualResult = Integer.toUnsignedLong(actual[i * 6])
                        | (Integer.toUnsignedLong(actual[i * 6 + 1]) << 32);
                long actualStateLo = Integer.toUnsignedLong(actual[i * 6 + 2])
                        | (Integer.toUnsignedLong(actual[i * 6 + 3]) << 32);
                long actualStateHi = Integer.toUnsignedLong(actual[i * 6 + 4])
                        | (Integer.toUnsignedLong(actual[i * 6 + 5]) << 32);
                if (result.result() != actualResult || result.stateLo() != actualStateLo
                        || result.stateHi() != actualStateHi) {
                    throw new IllegalStateException("xoroshiro mismatch index=" + i
                            + " expected=(0x" + Long.toHexString(result.result()) + ",0x"
                            + Long.toHexString(result.stateLo()) + ",0x" + Long.toHexString(result.stateHi())
                            + ") actual=(0x" + Long.toHexString(actualResult) + ",0x"
                            + Long.toHexString(actualStateLo) + ",0x" + Long.toHexString(actualStateHi) + ")");
                }
            }
            return count;
        }

        private static DeviceCapabilities inspect(VkPhysicalDevice candidate, MemoryStack stack) {
            var controls = VkPhysicalDeviceFloatControlsProperties.calloc(stack).sType$Default();
            var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(controls.address());
            vkGetPhysicalDeviceProperties2(candidate, props);
            var features = VkPhysicalDeviceFeatures.calloc(stack);
            VK10.vkGetPhysicalDeviceFeatures(candidate, features);
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
            int family = -1;
            for (int i = 0; i < families.capacity(); i++) {
                if (families.get(i).queueCount() > 0 && (families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    family = i;
                    break;
                }
            }
            return new DeviceCapabilities(props.properties().deviceNameString(), props.properties().apiVersion(),
                    props.properties().driverVersion(), props.properties().deviceType(), family, features.shaderFloat64(),
                    controls.shaderSignedZeroInfNanPreserveFloat64(), controls.shaderDenormPreserveFloat64(),
                    controls.shaderRoundingModeRTEFloat64());
        }

        private int[] dispatch(byte[] spirv, int operation, int[] input, int count) {
            return dispatch(spirv, operation, input, count, 6);
        }

        private int[] dispatch(byte[] spirv, int operation, int[] input, int count, int outputWords) {
            return dispatch(spirv, operation, input, count, outputWords, new int[0][], false);
        }

        /** The pipeline of {@link #cachedSpirv}, kept across dispatches by the campaigns that ask for it. */
        private byte[] cachedSpirv;
        private int cachedBindings;
        private long cachedShader, cachedDescriptorLayout, cachedPipelineLayout, cachedPipeline;

        private void releaseCachedPipeline() {
            if (cachedPipeline != 0) vkDestroyPipeline(device, cachedPipeline, null);
            if (cachedPipelineLayout != 0) vkDestroyPipelineLayout(device, cachedPipelineLayout, null);
            if (cachedDescriptorLayout != 0) vkDestroyDescriptorSetLayout(device, cachedDescriptorLayout, null);
            if (cachedShader != 0) vkDestroyShaderModule(device, cachedShader, null);
            cachedSpirv = null;
            cachedShader = cachedDescriptorLayout = cachedPipelineLayout = cachedPipeline = 0;
        }

        /**
         * One dispatch. Binding 0 is the input words, binding 1 the output words, bindings 2 and up are the
         * {@code extra} word arrays in order (read and written by the shader; the host reads back only the
         * output). With {@code reusePipeline} the shader module and pipeline are created once per SPIR-V array
         * and kept until the next different array or {@link #close()}; the large fused-library shader would
         * otherwise be recompiled by the driver for every chunk.
         */
        private int[] dispatch(byte[] spirv, int operation, int[] input, int count, int outputWords, int[][] extra,
                               boolean reusePipeline) {
            int bindingCount = 2 + extra.length;
            Allocation inputBuffer = allocate((long) input.length * Integer.BYTES);
            Allocation outputBuffer = null;
            Allocation[] extraBuffers = new Allocation[extra.length];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                outputBuffer = allocate((long) count * outputWords * Integer.BYTES);
                for (int i = 0; i < input.length; i++) inputBuffer.mapped.putInt(i * 4, input[i]);
                for (int e = 0; e < extra.length; e++) {
                    extraBuffers[e] = allocate(Math.max(4L, (long) extra[e].length * Integer.BYTES));
                    for (int i = 0; i < extra[e].length; i++) extraBuffers[e].mapped.putInt(i * 4, extra[e][i]);
                }
                LongBuffer handle = stack.mallocLong(1);
                ByteBuffer code = reusePipeline ? null : MemoryUtil.memAlloc(spirv.length).order(ByteOrder.nativeOrder());
                long shader = 0, descriptorLayout = 0, pipelineLayout = 0, pipeline = 0;
                long descriptorPool = 0, commandPool = 0, fence = 0;
                try {
                    if (reusePipeline && (cachedSpirv != spirv || cachedBindings != bindingCount)) {
                        releaseCachedPipeline();
                        ByteBuffer cachedCode = MemoryUtil.memAlloc(spirv.length).order(ByteOrder.nativeOrder());
                        try {
                            cachedCode.put(spirv).flip();
                            cachedShader = createShaderModule(stack, cachedCode, handle);
                        } finally {
                            MemoryUtil.memFree(cachedCode);
                        }
                        cachedDescriptorLayout = createDescriptorLayout(stack, bindingCount, handle);
                        cachedPipelineLayout = createPipelineLayout(stack, cachedDescriptorLayout, handle);
                        cachedPipeline = createPipeline(stack, cachedPipelineLayout, cachedShader, handle);
                        cachedSpirv = spirv;
                        cachedBindings = bindingCount;
                    }
                    if (reusePipeline) {
                        shader = cachedShader;
                        descriptorLayout = cachedDescriptorLayout;
                        pipelineLayout = cachedPipelineLayout;
                        pipeline = cachedPipeline;
                    } else {
                        code.put(spirv).flip();
                        shader = createShaderModule(stack, code, handle);
                        descriptorLayout = createDescriptorLayout(stack, bindingCount, handle);
                        pipelineLayout = createPipelineLayout(stack, descriptorLayout, handle);
                        pipeline = createPipeline(stack, pipelineLayout, shader, handle);
                    }
                    var sizes = VkDescriptorPoolSize.calloc(1, stack);
                    sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(bindingCount);
                    check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
                    descriptorPool = handle.get(0);
                    check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), handle), "vkAllocateDescriptorSets");
                    long descriptor = handle.get(0);
                    var writes = VkWriteDescriptorSet.calloc(bindingCount, stack);
                    Allocation[] buffers = new Allocation[bindingCount];
                    buffers[0] = inputBuffer;
                    buffers[1] = outputBuffer;
                    System.arraycopy(extraBuffers, 0, buffers, 2, extra.length);
                    for (int i = 0; i < bindingCount; i++) {
                        var info = VkDescriptorBufferInfo.calloc(1, stack);
                        info.get(0).buffer(buffers[i].buffer).offset(0).range(buffers[i].mapped.capacity());
                        writes.get(i).sType$Default().dstSet(descriptor).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
                    }
                    vkUpdateDescriptorSets(device, writes, null);
                    check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily), null, handle), "vkCreateCommandPool");
                    commandPool = handle.get(0);
                    PointerBuffer commandOut = stack.mallocPointer(1);
                    check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), commandOut), "vkAllocateCommandBuffers");
                    VkCommandBuffer command = new VkCommandBuffer(commandOut.get(0), device);
                    check(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
                    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptor), null);
                    vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(count, operation));
                    vkCmdDispatch(command, (int) (((long) count + LOCAL_SIZE - 1L) / LOCAL_SIZE), 1, 1);
                    var barriers = VkMemoryBarrier.calloc(1, stack);
                    barriers.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barriers, null, null);
                    check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
                    check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
                    fence = handle.get(0);
                    check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command.address())), fence), "vkQueueSubmit");
                    int wait = vkWaitForFences(device, fence, true, FENCE_TIMEOUT_NANOS);
                    check(wait, "vkWaitForFences(integer conformance)");
                    int[] output = new int[count * outputWords];
                    for (int i = 0; i < output.length; i++) output[i] = outputBuffer.mapped.getInt(i * 4);
                    return output;
                } finally {
                    if (fence != 0) vkDestroyFence(device, fence, null);
                    if (commandPool != 0) vkDestroyCommandPool(device, commandPool, null);
                    if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
                    if (!reusePipeline) {
                        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
                        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
                        if (descriptorLayout != 0) vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                        if (shader != 0) vkDestroyShaderModule(device, shader, null);
                        MemoryUtil.memFree(code);
                    }
                }
            } finally {
                if (outputBuffer != null) free(outputBuffer);
                for (Allocation buffer : extraBuffers) if (buffer != null) free(buffer);
                free(inputBuffer);
            }
        }

        private long createShaderModule(MemoryStack stack, ByteBuffer code, LongBuffer handle) {
            check(vkCreateShaderModule(device, org.lwjgl.vulkan.VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle), "vkCreateShaderModule");
            return handle.get(0);
        }

        private long createDescriptorLayout(MemoryStack stack, int bindingCount, LongBuffer handle) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            for (int i = 0; i < bindingCount; i++) bindings.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
            return handle.get(0);
        }

        private long createPipelineLayout(MemoryStack stack, long descriptorLayout, LongBuffer handle) {
            var push = org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack);
            // The primitive harness uses the first two words; generated
            // worldgen shaders reserve the full four-word dispatch ABI.
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            check(vkCreatePipelineLayout(device, org.lwjgl.vulkan.VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
            return handle.get(0);
        }

        private long createPipeline(MemoryStack stack, long pipelineLayout, long shader, LongBuffer handle) {
            var pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelines.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, 0, pipelines, null, handle), "vkCreateComputePipelines");
            return handle.get(0);
        }

        private void free(Allocation allocation) {
            vkUnmapMemory(device, allocation.memory);
            vkDestroyBuffer(device, allocation.buffer, null);
            vkFreeMemory(device, allocation.memory, null);
        }

        private Allocation allocate(long bytes) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes).usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(device, create, null, out), "vkCreateBuffer");
                long buffer = out.get(0), memory = 0;
                try {
                    var requirements = VkMemoryRequirements.calloc(stack);
                    vkGetBufferMemoryRequirements(device, buffer, requirements);
                    var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
                    vkGetPhysicalDeviceMemoryProperties(physical, properties);
                    int memoryType = -1;
                    int required = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                    for (int i = 0; i < properties.memoryTypeCount(); i++) if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & required) == required) { memoryType = i; break; }
                    if (memoryType < 0) throw new IllegalStateException("Integer conformance requires host-visible coherent storage memory");
                    var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType);
                    check(vkAllocateMemory(device, allocation, null, out), "vkAllocateMemory");
                    memory = out.get(0);
                    check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
                    PointerBuffer mapped = stack.mallocPointer(1);
                    check(vkMapMemory(device, memory, 0, bytes, 0, mapped), "vkMapMemory");
                    return new Allocation(buffer, memory, MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(bytes)).order(ByteOrder.nativeOrder()));
                } catch (Throwable failure) {
                    if (memory != 0) vkFreeMemory(device, memory, null);
                    vkDestroyBuffer(device, buffer, null);
                    throw failure;
                }
            }
        }

        @Override public void close() {
            if (device != null) {
                vkDeviceWaitIdle(device);
                releaseCachedPipeline();
                vkDestroyDevice(device, null);
                device = null;
            }
            if (instance != null) {
                vkDestroyInstance(instance, null);
                instance = null;
            }
        }
    }

    /**
     * Device campaign for the fused-kernel numeric dialect: the four helpers of {@link FusedIeeeHelpers} and the
     * existing helpers the dialect relies on that the primitive campaign does not test directly. It has its own
     * shader, built the way an assembler builds it (the helper closure of the roots, not the whole emitter text),
     * and its own report, so the primitive campaign's source hash is untouched. Non-NaN results must match Java
     * bit for bit; a NaN result passes when Java's result is NaN, whatever its sign or payload.
     */
    private static final class FusedIeee {
        private static final int RANDOM_INPUTS = 1_000_000;
        private static final int CHUNK = 262_144;
        private static final int INPUT_WORDS = 8;
        private static final int OUTPUT_WORDS = 2;
        private static final int MAX_REPORTED = 16;

        private static final int TO_I32 = 0, FROM_I64 = 1, FP32_FROM_I64 = 2, CEIL = 3, SUB = 4, LESS = 5, LESS_EQUAL = 6,
                EQUAL = 7, TO_FP32 = 8, FROM_FP32 = 9, LERP = 10, FP32_FROM_INT = 11, FP32_LESS = 12,
                FP32_LESS_EQUAL = 13, FP32_EQUAL = 14;
        private static final String[] NAMES = {"fused.fp64_to_i32_java", "fused.fp64_from_i64", "fused.fp32_from_i64",
                "fused.fp64_ceil", "fp64.sub", "fp64.less", "fp64.less_equal", "fp64.equal", "fp64.to_fp32",
                "fp64.from_fp32", "fp64.lerp", "fp32.from_int", "fp32.less", "fp32.less_equal", "fp32.equal"};

        /** How a device answer is compared with Java's. */
        private enum Kind { INT32, BITS32, BITS64, BOOL }

        /** The existing helpers the fused kernels call that the primitive campaign never reaches directly. */
        private static final Set<String> EXISTING_ROOTS = Set.of("wg_fp64_sub", "wg_fp64_less", "wg_fp64_less_equal",
                "wg_fp64_equal", "wg_fp64_to_fp32", "wg_fp64_from_fp32", "wg_fp64_lerp", "wg_fp32_from_int",
                "wg_fp32_less", "wg_fp32_less_equal", "wg_fp32_equal");

        /** One entry per operation that disagreed with Java; the campaign reports nothing as passed while any exists. */
        private static final List<String> failures = new ArrayList<>();

        private FusedIeee() {}

        static void run(Session session, String output) throws java.io.IOException {
            failures.clear();
            String source = shaderSource();
            byte[] spirv = new ShadercCompiler().compile(source);
            var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                    .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
            if (!inspection.valid()) {
                throw new IllegalStateException("Fused IEEE conformance module violates GPU_IEEE_BITS: " + inspection.violations());
            }
            int[] counts = new int[NAMES.length];
            var random = new SplittableRandom(0x7e11a1c0f05ed001L);

            // (int) d
            long[] values = doubleInputs(random, listedDoubles(), RANDOM_INPUTS);
            counts[TO_I32] = checkUnary(session, spirv, TO_I32, values, Kind.INT32,
                    value -> (long) (int) Double.longBitsToDouble(value));
            // (double) long and (float) long
            long[] integers = integerInputs(random, 53, RANDOM_INPUTS);
            counts[FROM_I64] = checkUnary(session, spirv, FROM_I64, integers, Kind.BITS64,
                    value -> Double.doubleToRawLongBits((double) value));
            integers = integerInputs(random, 24, RANDOM_INPUTS);
            counts[FP32_FROM_I64] = checkUnary(session, spirv, FP32_FROM_I64, integers, Kind.BITS32,
                    value -> Float.floatToRawIntBits((float) value) & 0xffffffffL);
            // Math.ceil
            values = doubleInputs(random, listedDoubles(), RANDOM_INPUTS);
            counts[CEIL] = checkUnary(session, spirv, CEIL, values, Kind.BITS64,
                    value -> Double.doubleToRawLongBits(Math.ceil(Double.longBitsToDouble(value))));

            long[][] pairs = doublePairs(random, RANDOM_INPUTS);
            counts[SUB] = checkBinary(session, spirv, SUB, pairs, Kind.BITS64,
                    (left, right) -> Double.doubleToRawLongBits(Double.longBitsToDouble(left) - Double.longBitsToDouble(right)));
            counts[LESS] = checkBinary(session, spirv, LESS, pairs, Kind.BOOL,
                    (left, right) -> Double.longBitsToDouble(left) < Double.longBitsToDouble(right) ? 1L : 0L);
            counts[LESS_EQUAL] = checkBinary(session, spirv, LESS_EQUAL, pairs, Kind.BOOL,
                    (left, right) -> Double.longBitsToDouble(left) <= Double.longBitsToDouble(right) ? 1L : 0L);
            counts[EQUAL] = checkBinary(session, spirv, EQUAL, pairs, Kind.BOOL,
                    (left, right) -> Double.longBitsToDouble(left) == Double.longBitsToDouble(right) ? 1L : 0L);

            values = narrowingInputs(random, RANDOM_INPUTS);
            counts[TO_FP32] = checkUnary(session, spirv, TO_FP32, values, Kind.BITS32,
                    value -> Float.floatToRawIntBits((float) Double.longBitsToDouble(value)) & 0xffffffffL);
            values = floatInputs(random, RANDOM_INPUTS);
            counts[FROM_FP32] = checkUnary(session, spirv, FROM_FP32, values, Kind.BITS64,
                    value -> Double.doubleToRawLongBits((double) Float.intBitsToFloat((int) value)));

            long[][] triples = lerpInputs(random, RANDOM_INPUTS);
            counts[LERP] = checkTernary(session, spirv, LERP, triples, Kind.BITS64, (start, end, delta) -> {
                // Mth.lerp(delta, start, end)
                double a = Double.longBitsToDouble(start), b = Double.longBitsToDouble(end), t = Double.longBitsToDouble(delta);
                return Double.doubleToRawLongBits(a + t * (b - a));
            });

            long[] ints = intInputs(random, RANDOM_INPUTS);
            counts[FP32_FROM_INT] = checkUnary(session, spirv, FP32_FROM_INT, ints, Kind.BITS32,
                    value -> Float.floatToRawIntBits((float) (int) value) & 0xffffffffL);
            long[][] floatPairs = floatPairs(random, RANDOM_INPUTS);
            counts[FP32_LESS] = checkBinary(session, spirv, FP32_LESS, floatPairs, Kind.BOOL,
                    (left, right) -> Float.intBitsToFloat((int) left) < Float.intBitsToFloat((int) right) ? 1L : 0L);
            counts[FP32_LESS_EQUAL] = checkBinary(session, spirv, FP32_LESS_EQUAL, floatPairs, Kind.BOOL,
                    (left, right) -> Float.intBitsToFloat((int) left) <= Float.intBitsToFloat((int) right) ? 1L : 0L);
            counts[FP32_EQUAL] = checkBinary(session, spirv, FP32_EQUAL, floatPairs, Kind.BOOL,
                    (left, right) -> Float.intBitsToFloat((int) left) == Float.intBitsToFloat((int) right) ? 1L : 0L);

            if (!failures.isEmpty()) {
                throw new IllegalStateException("fused IEEE campaign FAILED on device " + session.name() + ": "
                        + failures.size() + " of " + NAMES.length + " operations disagree with Java\n"
                        + String.join("\n", failures));
            }
            int comparisons = 0;
            for (int count : counts) comparisons += count;
            String sourceHash = Hashes.sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String spirvHash = Hashes.sha256(spirv);
            StringBuilder json = new StringBuilder("{\n")
                    .append("  \"schemaVersion\": 1,\n")
                    .append("  \"status\": \"PASS\",\n")
                    .append("  \"campaign\": \"fused-ieee\",\n")
                    .append("  \"profile\": \"GPU_IEEE_BITS\",\n")
                    .append("  \"device\": \"").append(jsonEscape(session.name())).append("\",\n")
                    .append("  \"sourceSha256\": \"").append(sourceHash).append("\",\n")
                    .append("  \"spirvSha256\": \"").append(spirvHash).append("\",\n")
                    .append("  \"comparisons\": ").append(comparisons).append(",\n")
                    .append("  \"mismatches\": 0,\n")
                    .append("  \"operations\": {");
            for (int i = 0; i < NAMES.length; i++) {
                if (i > 0) json.append(',');
                json.append("\n    \"").append(NAMES[i]).append("\": ").append(counts[i]);
            }
            json.append("\n  }\n}\n");
            if (output != null) {
                Path reportPath = Path.of(output).toAbsolutePath().normalize();
                Path parent = reportPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(reportPath, json.toString());
            }
            System.out.println("nativeIntegerIeee --fused-ieee DEVICE_PASS device=" + session.name()
                    + " comparisons=" + comparisons + " mismatches=0 sourceSha256=" + sourceHash
                    + (output == null ? "" : " report=" + Path.of(output).toAbsolutePath().normalize()));
        }

        /**
         * The shader an assembler would build: the helper closure of the fused roots plus the existing helpers
         * under test, then the fused text. The closure never declares {@code wg_failed}.
         */
        static String shaderSource() {
            var roots = new LinkedHashSet<String>(FusedIeeeHelpers.HELPERS);
            roots.addAll(EXISTING_ROOTS);
            return """
                    #version 450
                    layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                    layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                    layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                    layout(push_constant) uniform Dispatch { uint count; uint operation; } dispatch;
                    """ + IntegerIeeeEmitter.helperSource(roots) + FusedIeeeHelpers.source() + """
                    void main() {
                        uint index = gl_GlobalInvocationID.x;
                        if (index >= dispatch.count) return;
                        uint inputBase = index * 8u, outputBase = index * 2u;
                        uvec2 a = uvec2(inputBits[inputBase], inputBits[inputBase + 1u]);
                        uvec2 b = uvec2(inputBits[inputBase + 2u], inputBits[inputBase + 3u]);
                        uvec2 c = uvec2(inputBits[inputBase + 4u], inputBits[inputBase + 5u]);
                        uint operation = dispatch.operation;
                        uvec2 value = uvec2(0xffffffffu, 0xffffffffu);
                        if (operation == 0u) value = uvec2(uint(wgf_fp64_to_i32_java(a)), 0u);
                        else if (operation == 1u) value = wgf_fp64_from_i64(a);
                        else if (operation == 2u) value = uvec2(wgf_fp32_from_i64(a), 0u);
                        else if (operation == 3u) value = wgf_fp64_ceil(a);
                        else if (operation == 4u) value = wg_fp64_sub(a, b);
                        else if (operation == 5u) value = uvec2(wg_fp64_less(a, b) ? 1u : 0u, 0u);
                        else if (operation == 6u) value = uvec2(wg_fp64_less_equal(a, b) ? 1u : 0u, 0u);
                        else if (operation == 7u) value = uvec2(wg_fp64_equal(a, b) ? 1u : 0u, 0u);
                        else if (operation == 8u) value = uvec2(wg_fp64_to_fp32(a), 0u);
                        else if (operation == 9u) value = wg_fp64_from_fp32(a.x);
                        else if (operation == 10u) value = wg_fp64_lerp(a, b, c);
                        else if (operation == 11u) value = uvec2(wg_fp32_from_int(int(a.x)), 0u);
                        else if (operation == 12u) value = uvec2(wg_fp32_less(a.x, b.x) ? 1u : 0u, 0u);
                        else if (operation == 13u) value = uvec2(wg_fp32_less_equal(a.x, b.x) ? 1u : 0u, 0u);
                        else if (operation == 14u) value = uvec2(wg_fp32_equal(a.x, b.x) ? 1u : 0u, 0u);
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                    }
                    """;
        }

        private interface Unary { long expected(long value); }
        private interface Binary { long expected(long left, long right); }
        private interface Ternary { long expected(long first, long second, long third); }

        private static int checkUnary(Session session, byte[] spirv, int operation, long[] values, Kind kind, Unary reference) {
            int[] input = new int[values.length * INPUT_WORDS];
            long[] expected = new long[values.length];
            for (int i = 0; i < values.length; i++) {
                put(input, i, 0, values[i]);
                expected[i] = reference.expected(values[i]);
            }
            return check(session, spirv, operation, input, expected, kind);
        }

        private static int checkBinary(Session session, byte[] spirv, int operation, long[][] pairs, Kind kind, Binary reference) {
            int[] input = new int[pairs.length * INPUT_WORDS];
            long[] expected = new long[pairs.length];
            for (int i = 0; i < pairs.length; i++) {
                put(input, i, 0, pairs[i][0]);
                put(input, i, 1, pairs[i][1]);
                expected[i] = reference.expected(pairs[i][0], pairs[i][1]);
            }
            return check(session, spirv, operation, input, expected, kind);
        }

        private static int checkTernary(Session session, byte[] spirv, int operation, long[][] triples, Kind kind, Ternary reference) {
            int[] input = new int[triples.length * INPUT_WORDS];
            long[] expected = new long[triples.length];
            for (int i = 0; i < triples.length; i++) {
                put(input, i, 0, triples[i][0]);
                put(input, i, 1, triples[i][1]);
                put(input, i, 2, triples[i][2]);
                expected[i] = reference.expected(triples[i][0], triples[i][1], triples[i][2]);
            }
            return check(session, spirv, operation, input, expected, kind);
        }

        /** Operand {@code slot} of case {@code index}: low word first, as the shader reads it. */
        private static void put(int[] input, int index, int slot, long bits) {
            input[index * INPUT_WORDS + slot * 2] = (int) bits;
            input[index * INPUT_WORDS + slot * 2 + 1] = (int) (bits >>> 32);
        }

        private static int check(Session session, byte[] spirv, int operation, int[] input, long[] expected, Kind kind) {
            int count = expected.length;
            int mismatches = 0;
            var firstFailures = new StringBuilder();
            for (int start = 0; start < count; start += CHUNK) {
                int size = Math.min(CHUNK, count - start);
                int[] chunk = Arrays.copyOfRange(input, start * INPUT_WORDS, (start + size) * INPUT_WORDS);
                int[] actual = session.dispatch(spirv, operation, chunk, size, OUTPUT_WORDS);
                for (int i = 0; i < size; i++) {
                    long actualBits = Integer.toUnsignedLong(actual[i * OUTPUT_WORDS])
                            | (Integer.toUnsignedLong(actual[i * OUTPUT_WORDS + 1]) << 32);
                    if (matches(kind, expected[start + i], actualBits)) continue;
                    mismatches++;
                    if (mismatches <= MAX_REPORTED) {
                        firstFailures.append("\n  index=").append(start + i).append(" input=");
                        for (int word = 0; word < 6; word++) {
                            firstFailures.append(word == 0 ? "" : ",").append("0x")
                                    .append(Integer.toHexString(input[(start + i) * INPUT_WORDS + word]));
                        }
                        firstFailures.append(" expected=0x").append(Long.toHexString(expected[start + i]))
                                .append(" actual=0x").append(Long.toHexString(actualBits));
                    }
                }
            }
            if (mismatches != 0) {
                // Every operation still runs, so one failing helper does not hide another.
                failures.add(NAMES[operation] + ": " + mismatches + " of " + count + " mismatches (first "
                        + Math.min(mismatches, MAX_REPORTED) + ")" + firstFailures);
                return 0;
            }
            return count;
        }

        private static boolean matches(Kind kind, long expected, long actual) {
            switch (kind) {
                case INT32:
                    return (int) expected == (int) actual && (actual >>> 32) == 0L;
                case BOOL:
                    return expected == actual;
                case BITS32: {
                    if ((actual >>> 32) != 0L) return false;
                    int expectedBits = (int) expected, actualBits = (int) actual;
                    return Float.isNaN(Float.intBitsToFloat(expectedBits))
                            ? Float.isNaN(Float.intBitsToFloat(actualBits)) : expectedBits == actualBits;
                }
                default:
                    return Double.isNaN(Double.longBitsToDouble(expected))
                            ? Double.isNaN(Double.longBitsToDouble(actual)) : expected == actual;
            }
        }

        // ---- inputs: the listed vectors first, then seeded edge-weighted and raw random values -------------

        private static final double TWO31 = 2147483648.0;

        private static long[] listedDoubles() {
            double minSubnormal = Double.longBitsToDouble(1L);
            double[] values = {0.0, -0.0, minSubnormal, -minSubnormal, Double.longBitsToDouble(0x000fffffffffffffL),
                    Double.MIN_NORMAL, 0.5, -0.5, Math.nextDown(0.5), Math.nextDown(1.0), -Math.nextDown(1.0), 1.0, -1.0,
                    Math.nextUp(1.0), 1.5, -1.5, 2147483646.5, -2147483646.5, 2147483647.0, 2147483647.5, -2147483647.5,
                    TWO31, -TWO31, -TWO31 - 0.5, -TWO31 - 1.0, TWO31 + 0.5, 9007199254740992.0, -9007199254740992.0,
                    4503599627370495.5, -4503599627370495.5, 4503599627370496.0, 1e300, -1e300, Double.MAX_VALUE,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            long[] raw = new long[values.length + 4];
            for (int i = 0; i < values.length; i++) raw[i] = Double.doubleToRawLongBits(values[i]);
            raw[values.length] = 0x7ff8000000000000L;
            raw[values.length + 1] = 0x7ff0000000000001L;
            raw[values.length + 2] = 0xfff8000000000000L;
            raw[values.length + 3] = 0xfff0000000000001L;
            return raw;
        }

        private static long[] listedIntegers() {
            long two53 = 1L << 53, two54 = 1L << 54;
            return new long[] {0, 1, -1, 2, 3, 16777215, 16777216, 16777217, 16777218, 16777219, 33554435, 134217740, -134217740,
                    2147483647L, -2147483648L, 2147483648L, 4294967296L, two53 - 1, two53, two53 + 1, two53 + 2, two53 + 3,
                    -two53 - 1, two54 + 1, two54 + 2, two54 + 3, two54 + 5, two54 + 6, -(two54 + 2), (1L << 62) + 1024,
                    (1L << 62) + 1025, Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1024, Long.MAX_VALUE - 1025,
                    Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1024, Long.MIN_VALUE + 1025,
                    Long.MAX_VALUE - (1L << 39) + 1, Long.MAX_VALUE - (1L << 39), Long.MAX_VALUE - (1L << 39) - 1,
                    0x7fffff7fffffffffL, 0x7fffff8000000000L, 0x7fffffbfffffffffL, 0x7fffffc000000000L,
                    0x0000010000800000L, 0x0000010000800001L, 0x00000100007fffffL};
        }

        private static long[] withRandom(long[] listed, int random, java.util.function.LongSupplier next) {
            long[] values = Arrays.copyOf(listed, listed.length + random);
            for (int i = listed.length; i < values.length; i++) values[i] = next.getAsLong();
            return values;
        }

        private static long[] doubleInputs(SplittableRandom random, long[] listed, int count) {
            return withRandom(listed, count, () -> edgeDouble(random));
        }

        private static long[] integerInputs(SplittableRandom random, int width, int count) {
            return withRandom(listedIntegers(), count, () -> edgeInteger(random, width));
        }

        private static long[] intInputs(SplittableRandom random, int count) {
            long[] listed = {0, 1, -1, 16777215, 16777216, 16777217, 16777219, 33554435, 134217740, 2147483647L,
                    -2147483648L, -2147483647L, 2147483520L, 2147483519L, 2147483521L, 2147483584L, -16777217, -16777219};
            return withRandom(listed, count, () -> {
                switch (random.nextInt(4)) {
                    case 0: return random.nextInt();
                    case 1: return random.nextInt() >> random.nextInt(32);
                    case 2: { // a tie or its neighbours at a random magnitude above 2^24
                        int leading = 24 + random.nextInt(7);
                        int dropped = leading - 23;
                        int significand = (1 << 23) | (random.nextInt() & ((1 << 23) - 1));
                        int tail = switch (random.nextInt(4)) {
                            case 0 -> 1 << (dropped - 1);
                            case 1 -> (1 << (dropped - 1)) + 1;
                            case 2 -> (1 << (dropped - 1)) - 1;
                            default -> (1 << dropped) - 1;
                        };
                        int value = (significand << dropped) | tail;
                        return random.nextBoolean() ? -value : value;
                    }
                    default: return (random.nextBoolean() ? Integer.MAX_VALUE : Integer.MIN_VALUE) + random.nextInt(65) - 32;
                }
            });
        }

        private static long edgeDouble(SplittableRandom random) {
            long sign = random.nextBoolean() ? Long.MIN_VALUE : 0L;
            switch (random.nextInt(9)) {
                case 0: return random.nextLong();
                case 1: { // near a power of two at every exponent
                    long[] fractions = {0, 1, 2, 3, 0xfffffffffffffL, 0xffffffffffffeL, 0xffffffffffffdL, 1L << 51, (1L << 51) - 1};
                    return sign | ((long) random.nextInt(2048) << 52) | fractions[random.nextInt(fractions.length)];
                }
                case 2: { // n + 0.5, a tie between two integers
                    long magnitude = random.nextLong(1L << 52) >>> random.nextInt(52);
                    return Double.doubleToRawLongBits((sign == 0 ? 1.0 : -1.0) * (magnitude + 0.5));
                }
                case 3: { // around the int range boundaries
                    double[] deltas = {-2.0, -1.5, -1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0, 1.5, 2.0};
                    double centre = random.nextBoolean() ? TWO31 : (random.nextBoolean() ? 1.0 : 0.0);
                    double value = (sign == 0 ? 1.0 : -1.0) * (centre + deltas[random.nextInt(deltas.length)]);
                    for (int steps = random.nextInt(3); steps > 0; steps--) value = random.nextBoolean() ? Math.nextUp(value) : Math.nextDown(value);
                    return Double.doubleToRawLongBits(value);
                }
                case 4: { // subnormals, sometimes with few bits set
                    long fraction = random.nextLong() & 0xfffffffffffffL;
                    if (random.nextBoolean()) fraction >>>= random.nextInt(52);
                    return sign | fraction;
                }
                case 5: { // infinities and NaNs with several payload shapes
                    long fraction = random.nextBoolean() ? 0L : (random.nextLong() & 0xfffffffffffffL);
                    if (random.nextInt(4) == 0) fraction = 1L << random.nextInt(52);
                    return sign | (2047L << 52) | fraction;
                }
                case 6: return Double.doubleToRawLongBits((random.nextInt(4001) - 2000) / (double) (1 << random.nextInt(6)));
                case 7: return Double.doubleToRawLongBits((random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(41)));
                default: {
                    double value = (double) (random.nextLong() >> random.nextInt(64));
                    for (int steps = random.nextInt(3); steps > 0; steps--) value = random.nextBoolean() ? Math.nextUp(value) : Math.nextDown(value);
                    return Double.doubleToRawLongBits(value);
                }
            }
        }

        /** {@code width} is the significand width of the target format: 53 for binary64, 24 for binary32. */
        private static long edgeInteger(SplittableRandom random, int width) {
            switch (random.nextInt(7)) {
                case 0: return random.nextLong();
                case 1: return random.nextLong() >> random.nextInt(64);
                case 2: {
                    long value = (1L << random.nextInt(63)) + random.nextInt(7) - 3;
                    return random.nextBoolean() ? -value : value;
                }
                case 3: { // a rounding tie, or one step either side of it, at a random magnitude
                    int leading = width + random.nextInt(63 - width);
                    int dropped = leading - (width - 1);
                    long significand = (1L << (width - 1)) | (random.nextLong() & ((1L << (width - 1)) - 1));
                    long half = 1L << (dropped - 1), all = (1L << dropped) - 1;
                    long tail = switch (random.nextInt(5)) {
                        case 0 -> half;
                        case 1 -> (half + 1) & all;
                        case 2 -> half - 1;
                        case 3 -> 0L;
                        default -> all;
                    };
                    long value = (significand << dropped) | tail;
                    return random.nextBoolean() ? -value : value;
                }
                case 4: {
                    long[] ends = {Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1};
                    return ends[random.nextInt(ends.length)] + random.nextInt(2049) - 1024;
                }
                case 5: return random.nextInt(1 << 22) - (1 << 21);
                default: return (random.nextLong() >> (64 - width - random.nextInt(8))) + random.nextInt(3) - 1;
            }
        }

        private static long[][] doublePairs(SplittableRandom random, int count) {
            long[] listed = listedDoubles();
            int fixed = listed.length * listed.length;
            long[][] pairs = new long[fixed + count][];
            int index = 0;
            for (long left : listed) for (long right : listed) pairs[index++] = new long[] {left, right};
            while (index < pairs.length) pairs[index++] = pair64(random);
            return pairs;
        }

        private static long[] pair64(SplittableRandom random) {
            long left = edgeDouble(random);
            return switch (random.nextInt(6)) {
                case 0 -> new long[] {left, edgeDouble(random)};
                case 1 -> new long[] {left, left};
                case 2 -> new long[] {left, left + random.nextInt(5) - 2};
                case 3 -> new long[] {left, left ^ Long.MIN_VALUE};
                case 4 -> new long[] {left, random.nextLong()};
                default -> new long[] {random.nextBoolean() ? 0x7ff8000000000000L : random.nextLong(), left};
            };
        }

        private static long[][] lerpInputs(SplittableRandom random, int count) {
            long[] listed = listedDoubles();
            long[] fractions = {0L, Double.doubleToRawLongBits(0.5), Double.doubleToRawLongBits(1.0),
                    Double.doubleToRawLongBits(0.25), Double.doubleToRawLongBits(-1.0), Double.doubleToRawLongBits(-0.0),
                    0x7ff8000000000000L};
            int fixed = listed.length * listed.length;
            long[][] triples = new long[fixed + count][];
            int index = 0;
            for (long start : listed) for (long end : listed) {
                triples[index] = new long[] {start, end, fractions[index % fractions.length]};
                index++;
            }
            while (index < triples.length) {
                long[] pair = pair64(random);
                long delta = switch (random.nextInt(4)) {
                    case 0 -> Double.doubleToRawLongBits(random.nextDouble());
                    case 1 -> Double.doubleToRawLongBits(random.nextDouble() * 2.0 - 0.5);
                    case 2 -> edgeDouble(random);
                    default -> fractions[random.nextInt(fractions.length)];
                };
                // Most of the weight goes to realistic operands: a finite pair of similar magnitude and a fraction in [0, 1].
                if (random.nextInt(3) == 0) {
                    double base = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(30) - 10);
                    double span = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(30) - 10);
                    pair = new long[] {Double.doubleToRawLongBits(base), Double.doubleToRawLongBits(base + span)};
                    delta = Double.doubleToRawLongBits(random.nextDouble());
                }
                triples[index++] = new long[] {pair[0], pair[1], delta};
            }
            return triples;
        }

        /** Binary64 values around the binary32 range: ties at binary32 precision, overflow, subnormals, underflow. */
        private static long[] narrowingInputs(SplittableRandom random, int count) {
            long[] listed = listedDoubles();
            long[] extra = new long[listed.length + 16];
            System.arraycopy(listed, 0, extra, 0, listed.length);
            double[] floatEdges = {Float.MAX_VALUE, Math.nextUp((double) Float.MAX_VALUE), 3.4028235677973366e38,
                    3.4028235677973362e38, Float.MIN_NORMAL, Float.MIN_NORMAL / 2.0, Float.MIN_VALUE, Float.MIN_VALUE / 2.0,
                    Math.nextUp(Float.MIN_VALUE / 2.0), Math.nextDown(Float.MIN_VALUE / 2.0), 16777217.0, 16777219.0,
                    1.0 + Math.scalb(1.0, -24), Math.nextUp(1.0 + Math.scalb(1.0, -24)), 1.0e-46, 1.0e39};
            for (int i = 0; i < floatEdges.length; i++) extra[listed.length + i] = Double.doubleToRawLongBits(floatEdges[i]);
            return withRandom(extra, count, () -> {
                long sign = random.nextBoolean() ? Long.MIN_VALUE : 0L;
                switch (random.nextInt(5)) {
                    case 0: return edgeDouble(random);
                    case 1: { // normal binary32 range: a tie, one step either side of it, or random low bits
                        long exponent = 1023 + random.nextInt(256) - 127;
                        long significand = random.nextLong() & 0x7fffffL;
                        long tail = switch (random.nextInt(5)) {
                            case 0 -> 0x10000000L;
                            case 1 -> 0x10000001L;
                            case 2 -> 0x0fffffffL;
                            case 3 -> 0L;
                            default -> random.nextLong() & 0x1fffffffL;
                        };
                        return sign | (exponent << 52) | (significand << 29) | tail;
                    }
                    case 2: { // subnormal binary32 range: (k + 0.5) * 2^-149, a tie at the subnormal precision
                        double value = Math.scalb(random.nextLong(1L << 23) + (random.nextBoolean() ? 0.5 : 0.0), -149);
                        for (int steps = random.nextInt(3); steps > 0; steps--) value = random.nextBoolean() ? Math.nextUp(value) : Math.nextDown(value);
                        return Double.doubleToRawLongBits(sign == 0 ? value : -value);
                    }
                    case 3: { // the edge of overflow and of underflow
                        long exponent = random.nextBoolean() ? 1023 + 127 + random.nextInt(3) - 1 : 1023 - 150 + random.nextInt(5) - 2;
                        return sign | (exponent << 52) | (random.nextLong() & 0xfffffffffffffL);
                    }
                    default: return random.nextLong();
                }
            });
        }

        private static long[] floatInputs(SplittableRandom random, int count) {
            long[] listed = {0x00000000L, 0x80000000L, 0x00000001L, 0x80000001L, 0x007fffffL, 0x00800000L, 0x3f800000L,
                    0xbf800000L, 0x7f7fffffL, 0xff7fffffL, 0x7f800000L, 0xff800000L, 0x7fc00000L, 0x7f800001L, 0xffc00001L,
                    0x00400000L, 0x00000002L, 0x007ffffeL};
            return withRandom(listed, count, () -> edgeFloat(random));
        }

        private static long edgeFloat(SplittableRandom random) {
            long sign = random.nextBoolean() ? 0x80000000L : 0L;
            switch (random.nextInt(6)) {
                case 0: return random.nextInt() & 0xffffffffL;
                case 1: return sign | (random.nextInt() & 0x7fffffL);
                case 2: return sign | ((long) random.nextInt(256) << 23) | (random.nextBoolean() ? 0L : (random.nextInt() & 0x7fffffL));
                case 3: return sign | (0xffL << 23) | (random.nextInt() & 0x7fffffL);
                case 4: return sign | ((long) random.nextInt(256) << 23) | (random.nextBoolean() ? 1L : 0x7fffffL);
                default: return Float.floatToRawIntBits((random.nextInt(4001) - 2000) / (float) (1 << random.nextInt(6))) & 0xffffffffL;
            }
        }

        private static long[][] floatPairs(SplittableRandom random, int count) {
            long[] listed = floatInputs(random, 0);
            int fixed = listed.length * listed.length;
            long[][] pairs = new long[fixed + count][];
            int index = 0;
            for (long left : listed) for (long right : listed) pairs[index++] = new long[] {left, right};
            while (index < pairs.length) {
                long left = edgeFloat(random);
                pairs[index++] = switch (random.nextInt(6)) {
                    case 0 -> new long[] {left, edgeFloat(random)};
                    case 1 -> new long[] {left, left};
                    case 2 -> new long[] {left, (left + random.nextInt(5) - 2) & 0xffffffffL};
                    case 3 -> new long[] {left, left ^ 0x80000000L};
                    case 4 -> new long[] {left, random.nextInt() & 0xffffffffL};
                    default -> new long[] {random.nextBoolean() ? 0x7fc00000L : (random.nextInt() & 0xffffffffL), left};
                };
            }
            return pairs;
        }
    }


    /**
     * Device conformance of the six {@code wgx_} helpers of {@link FastIeeeHelpers} against Java's {@code +},
     * {@code -} and {@code *} (binary32 with {@code float}): the listed vectors on every pair, then at least
     * {@link #RANDOM_INPUTS} seeded edge-weighted and raw random pairs per function. A non-NaN result must be
     * bit-exact; a NaN result passes when Java's is NaN.
     */
    private static final class FastIeee {
        private static final int RANDOM_INPUTS = 1_000_000;
        private static final int CHUNK = 262_144;
        private static final int INPUT_WORDS = 8;
        private static final int OUTPUT_WORDS = 2;
        private static final int MAX_REPORTED = 16;
        private static final String[] NAMES = {"wgx_fp64_add", "wgx_fp64_sub", "wgx_fp64_mul", "wgx_fp32_add", "wgx_fp32_sub", "wgx_fp32_mul"};

        private FastIeee() {}

        /** A binary interchange format, so one generator serves both widths. */
        private record Fmt(int fraction, int expMax, int bias) {
            static final Fmt F64 = new Fmt(52, 2047, 1023);
            static final Fmt F32 = new Fmt(23, 255, 127);
            int width() { return fraction == 52 ? 64 : 32; }
            long fractionMask() { return (1L << fraction) - 1L; }
            long mask() { return width() == 64 ? -1L : 0xffffffffL; }
            long make(long sign, long exponent, long bits) { return (sign << (width() - 1)) | (exponent << fraction) | (bits & fractionMask()); }
            long negate(long bits) { return bits ^ (1L << (width() - 1)); }
        }

        private static long java(int operation, long a, long b) {
            double da = Double.longBitsToDouble(a), db = Double.longBitsToDouble(b);
            float fa = Float.intBitsToFloat((int) a), fb = Float.intBitsToFloat((int) b);
            return switch (operation) {
                case 0 -> Double.doubleToRawLongBits(da + db);
                case 1 -> Double.doubleToRawLongBits(da - db);
                case 2 -> Double.doubleToRawLongBits(da * db);
                case 3 -> Float.floatToRawIntBits(fa + fb) & 0xffffffffL;
                case 4 -> Float.floatToRawIntBits(fa - fb) & 0xffffffffL;
                default -> Float.floatToRawIntBits(fa * fb) & 0xffffffffL;
            };
        }

        static String shaderSource() {
            return """
                    #version 450
                    #extension GL_ARB_gpu_shader_int64 : require
                    layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                    layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                    layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                    layout(push_constant) uniform Dispatch { uint count; uint operation; } dispatch;
                    """ + IntegerIeeeEmitter.helperSource(FastIeeeHelpers.HELPERS) + FastIeeeHelpers.source() + """
                    void main() {
                        uint index = gl_GlobalInvocationID.x;
                        if (index >= dispatch.count) return;
                        uint inputBase = index * 8u, outputBase = index * 2u;
                        uvec2 a = uvec2(inputBits[inputBase], inputBits[inputBase + 1u]);
                        uvec2 b = uvec2(inputBits[inputBase + 2u], inputBits[inputBase + 3u]);
                        uint operation = dispatch.operation;
                        uvec2 value = uvec2(0xffffffffu, 0xffffffffu);
                        if (operation == 0u) value = wgx_fp64_add(a, b);
                        else if (operation == 1u) value = wgx_fp64_sub(a, b);
                        else if (operation == 2u) value = wgx_fp64_mul(a, b);
                        else if (operation == 3u) value = uvec2(wgx_fp32_add(a.x, b.x), 0u);
                        else if (operation == 4u) value = uvec2(wgx_fp32_sub(a.x, b.x), 0u);
                        else if (operation == 5u) value = uvec2(wgx_fp32_mul(a.x, b.x), 0u);
                        outputBits[outputBase] = value.x;
                        outputBits[outputBase + 1u] = value.y;
                    }
                    """;
        }

        static void run(Session session, String output) throws java.io.IOException {
            String source = shaderSource();
            byte[] spirv = new ShadercCompiler().compile(source);
            var inspection = new dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract()
                    .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
            if (!inspection.valid()) {
                throw new IllegalStateException("Fast IEEE conformance module violates GPU_IEEE_BITS: " + inspection.violations());
            }
            var random = new SplittableRandom(0x7e11a1c0fa57001L);
            int[] counts = new int[NAMES.length];
            var failures = new ArrayList<String>();
            for (int operation = 0; operation < NAMES.length; operation++) {
                Fmt f = operation < 3 ? Fmt.F64 : Fmt.F32;
                boolean multiply = operation == 2 || operation == 5;
                List<Long> listed = listed(f);
                var pairs = new ArrayList<long[]>(listed.size() * listed.size() + RANDOM_INPUTS);
                for (long a : listed) for (long b : listed) pairs.add(new long[] {a, b});
                for (int i = 0; i < RANDOM_INPUTS; i++) {
                    long[] pair = switch (i % 4) {
                        case 0 -> new long[] {random.nextLong() & f.mask(), random.nextLong() & f.mask()};
                        default -> multiply ? mulPair(f, random) : addPair(f, random);
                    };
                    pairs.add(pair);
                }
                counts[operation] = check(session, spirv, operation, pairs, failures);
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("fast IEEE campaign FAILED on device " + session.name() + ": "
                        + failures.size() + " of " + NAMES.length + " functions disagree with Java\n" + String.join("\n", failures));
            }
            long comparisons = 0;
            for (int count : counts) comparisons += count;
            String sourceHash = Hashes.sha256(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String spirvHash = Hashes.sha256(spirv);
            StringBuilder json = new StringBuilder("{\n")
                    .append("  \"schemaVersion\": 1,\n")
                    .append("  \"status\": \"PASS\",\n")
                    .append("  \"campaign\": \"fast-ieee\",\n")
                    .append("  \"profile\": \"GPU_IEEE_BITS\",\n")
                    .append("  \"device\": \"").append(jsonEscape(session.name())).append("\",\n")
                    .append("  \"sourceSha256\": \"").append(sourceHash).append("\",\n")
                    .append("  \"spirvSha256\": \"").append(spirvHash).append("\",\n")
                    .append("  \"comparisons\": ").append(comparisons).append(",\n")
                    .append("  \"mismatches\": 0,\n")
                    .append("  \"operations\": {");
            for (int i = 0; i < NAMES.length; i++) {
                if (i > 0) json.append(',');
                json.append("\n    \"").append(NAMES[i]).append("\": ").append(counts[i]);
            }
            json.append("\n  }\n}\n");
            if (output != null) {
                Path reportPath = Path.of(output).toAbsolutePath().normalize();
                Path parent = reportPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(reportPath, json.toString());
            }
            System.out.println("nativeIntegerIeee --fast-ieee DEVICE_PASS device=" + session.name()
                    + " comparisons=" + comparisons + " mismatches=0 sourceSha256=" + sourceHash
                    + (output == null ? "" : " report=" + Path.of(output).toAbsolutePath().normalize()));
        }

        private static int check(Session session, byte[] spirv, int operation, List<long[]> pairs, List<String> failures) {
            int count = pairs.size();
            int mismatches = 0;
            var first = new StringBuilder();
            for (int start = 0; start < count; start += CHUNK) {
                int size = Math.min(CHUNK, count - start);
                int[] input = new int[size * INPUT_WORDS];
                for (int i = 0; i < size; i++) {
                    long[] pair = pairs.get(start + i);
                    input[i * INPUT_WORDS] = (int) pair[0];
                    input[i * INPUT_WORDS + 1] = (int) (pair[0] >>> 32);
                    input[i * INPUT_WORDS + 2] = (int) pair[1];
                    input[i * INPUT_WORDS + 3] = (int) (pair[1] >>> 32);
                }
                int[] actual = session.dispatch(spirv, operation, input, size, OUTPUT_WORDS);
                for (int i = 0; i < size; i++) {
                    long[] pair = pairs.get(start + i);
                    long expected = java(operation, pair[0], pair[1]);
                    long actualBits = Integer.toUnsignedLong(actual[i * OUTPUT_WORDS])
                            | (Integer.toUnsignedLong(actual[i * OUTPUT_WORDS + 1]) << 32);
                    if (matches(operation < 3, expected, actualBits)) continue;
                    mismatches++;
                    if (mismatches <= MAX_REPORTED) {
                        first.append("\n  index=").append(start + i).append(" a=0x").append(Long.toHexString(pair[0]))
                                .append(" b=0x").append(Long.toHexString(pair[1]))
                                .append(" expected=0x").append(Long.toHexString(expected))
                                .append(" actual=0x").append(Long.toHexString(actualBits));
                    }
                }
            }
            if (mismatches != 0) {
                failures.add(NAMES[operation] + ": " + mismatches + " of " + count + " mismatches (first "
                        + Math.min(mismatches, MAX_REPORTED) + ")" + first);
                return 0;
            }
            return count;
        }

        private static boolean matches(boolean wide, long expected, long actual) {
            if (wide) {
                return Double.isNaN(Double.longBitsToDouble(expected)) ? Double.isNaN(Double.longBitsToDouble(actual)) : expected == actual;
            }
            if ((actual >>> 32) != 0L) return false;
            return Float.isNaN(Float.intBitsToFloat((int) expected))
                    ? Float.isNaN(Float.intBitsToFloat((int) actual)) : (int) expected == (int) actual;
        }

        // ---- inputs ------------------------------------------------------------------------------------

        private static List<Long> listed(Fmt f) {
            var list = new ArrayList<Long>();
            long maxFraction = f.fractionMask();
            long[] exponents = {1, 2, 3, f.bias - f.fraction - 2, f.bias - f.fraction, f.bias - 2, f.bias - 1, f.bias, f.bias + 1,
                    f.bias + 2, f.bias + f.fraction - 1, f.bias + f.fraction, f.bias + f.fraction + 1, f.expMax - 3, f.expMax - 2,
                    f.expMax - 1};
            long[] fractions = {0, 1, 2, 3, maxFraction, maxFraction - 1, 1L << (f.fraction - 1), (1L << (f.fraction - 1)) - 1,
                    (1L << (f.fraction - 1)) + 1, maxFraction >>> 1, 0x5555555555555555L & maxFraction, 0x2aaaaaaaaaaaaaaaL & maxFraction};
            for (int sign = 0; sign <= 1; sign++) {
                list.add(f.make(sign, 0, 0));
                list.add(f.make(sign, 0, 1));
                list.add(f.make(sign, 0, 2));
                list.add(f.make(sign, 0, maxFraction));
                list.add(f.make(sign, 0, maxFraction - 1));
                list.add(f.make(sign, 0, 1L << (f.fraction - 1)));
                list.add(f.make(sign, f.expMax, 0));
                list.add(f.make(sign, f.expMax, 1L << (f.fraction - 1)));
                list.add(f.make(sign, f.expMax, 1));
                list.add(f.make(sign, f.expMax, maxFraction));
                for (long exponent : exponents) for (long fraction : fractions) list.add(f.make(sign, exponent, fraction));
            }
            return list;
        }

        private static long fraction(Fmt f, SplittableRandom r) {
            return switch (r.nextInt(6)) {
                case 0 -> 0L;
                case 1 -> f.fractionMask();
                case 2 -> r.nextLong() & f.fractionMask() & (f.fractionMask() << r.nextInt(f.fraction));
                case 3 -> (r.nextLong() & f.fractionMask()) >>> r.nextInt(f.fraction);
                case 4 -> (r.nextBoolean() ? 1L : f.fractionMask()) ^ (1L << r.nextInt(f.fraction));
                default -> r.nextLong() & f.fractionMask();
            };
        }

        private static long edge(Fmt f, SplittableRandom r) {
            long sign = r.nextBoolean() ? 1 : 0;
            return switch (r.nextInt(10)) {
                case 0 -> r.nextLong() & f.mask();
                case 1 -> f.make(sign, 0, fraction(f, r));
                case 2 -> f.make(sign, f.expMax, r.nextInt(3) == 0 ? 0 : fraction(f, r));
                case 3 -> f.make(sign, 1 + r.nextInt(3), fraction(f, r));
                case 4 -> f.make(sign, f.expMax - 1 - r.nextInt(3), fraction(f, r));
                case 5 -> f.make(sign, f.bias + r.nextInt(7) - 3, fraction(f, r));
                default -> f.make(sign, 1 + r.nextInt(f.expMax - 1), fraction(f, r));
            };
        }

        private static long[] addPair(Fmt f, SplittableRandom r) {
            long a = edge(f, r);
            long exponent = (a >>> f.fraction) & f.expMax;
            long sign = r.nextBoolean() ? 1 : 0;
            int precision = f.fraction + 1;
            switch (r.nextInt(9)) {
                case 0: return new long[] {a, edge(f, r)};
                case 1: return new long[] {a, r.nextLong() & f.mask()};
                case 2: {
                    long e = Math.max(1, Math.min(f.expMax - 1, exponent + r.nextInt(5) - 2));
                    return new long[] {a, f.make(sign, e, fraction(f, r))};
                }
                case 3: return new long[] {a, (f.negate(a) + r.nextInt(7) - 3) & f.mask()};
                case 4: {
                    long e = exponent - (precision - 3 + r.nextInt(8));
                    if (e < 1) e = 1 + r.nextInt(3);
                    return new long[] {a, f.make(sign, e, fraction(f, r))};
                }
                case 5: {
                    long e = Math.max(1, exponent - precision + r.nextInt(3));
                    return new long[] {a, f.make(sign, e, r.nextInt(3) == 0 ? fraction(f, r) : (r.nextBoolean() ? 0 : 1))};
                }
                case 6: {
                    long e = 1 + r.nextInt(3);
                    return new long[] {f.make(sign, e, fraction(f, r)), f.make(sign ^ 1, e + r.nextInt(2), fraction(f, r))};
                }
                case 7: {
                    long e = f.expMax - 1 - r.nextInt(2);
                    return new long[] {f.make(sign, e, fraction(f, r)), f.make(sign, e - r.nextInt(3), fraction(f, r))};
                }
                default: {
                    long e = Math.max(1, Math.min(f.expMax - 1, exponent - r.nextInt(2 * precision + 8)));
                    return new long[] {a, f.make(sign, e, fraction(f, r))};
                }
            }
        }

        private static long[] mulPair(Fmt f, SplittableRandom r) {
            long a = edge(f, r);
            long sign = r.nextBoolean() ? 1 : 0;
            switch (r.nextInt(7)) {
                case 0: return new long[] {a, edge(f, r)};
                case 1: return new long[] {a, r.nextLong() & f.mask()};
                case 2: {
                    long target = new long[] {-2, -1, 0, 1, 2, f.expMax - 3, f.expMax - 2, f.expMax - 1, f.expMax, f.expMax + 1}[r.nextInt(10)];
                    long e1 = 1 + r.nextInt(f.expMax - 1);
                    long e2 = target - e1 + f.bias;
                    if (e2 < 1 || e2 > f.expMax - 1) return new long[] {a, edge(f, r)};
                    return new long[] {f.make(sign, e1, fraction(f, r)), f.make(r.nextBoolean() ? 1 : 0, e2, fraction(f, r))};
                }
                case 3: {
                    int total = f.fraction - 2 + r.nextInt(5);
                    int t1 = Math.max(0, Math.min(f.fraction, r.nextInt(total + 1)));
                    int t2 = Math.max(0, Math.min(f.fraction, total - t1));
                    long m1 = ((r.nextLong() & f.fractionMask()) | 1L) << t1;
                    long m2 = ((r.nextLong() & f.fractionMask()) | 1L) << t2;
                    return new long[] {f.make(sign, f.bias + r.nextInt(7) - 3, m1), f.make(r.nextBoolean() ? 1 : 0, f.bias + r.nextInt(7) - 3, m2)};
                }
                case 4: {
                    long fa = r.nextBoolean() ? 0 : (r.nextBoolean() ? 1 : f.fractionMask());
                    long fb = r.nextBoolean() ? 0 : (r.nextBoolean() ? 1 : f.fractionMask());
                    return new long[] {f.make(sign, 1 + r.nextInt(f.expMax - 1), fa), f.make(r.nextBoolean() ? 1 : 0, 1 + r.nextInt(f.expMax - 1), fb)};
                }
                case 5: return new long[] {f.make(sign, f.bias + r.nextInt(21) - 10, fraction(f, r)), f.make(r.nextBoolean() ? 1 : 0, f.bias + r.nextInt(21) - 10, fraction(f, r))};
                default: return new long[] {a, a};
            }
        }
    }

    private record RngVector(long stateLo, long stateHi) {}

    private record RngResult(long result, long stateLo, long stateHi) {}

    private record Allocation(long buffer, long memory, ByteBuffer mapped) {}
}
