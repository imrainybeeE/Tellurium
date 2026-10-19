// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedIeeeLibrary;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.runtime.vulkan.ShadercCompiler;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-side shaderc compilation of {@code fused_lib_ieee.glsl} as a whole: the helper closure of
 * {@link FusedIeeeLibrary#HELPER_ROOTS}, the fused helpers, the library and the campaign's main, which reaches
 * every library function. It needs the shaderc natives and no Vulkan device. The module must hold no
 * {@code OpTypeFloat}, which is what makes the library safe on a GPU without binary64.
 */
class FusedIeeeLibraryCompileTest {
    private static final Pattern HEADER = Pattern.compile(
            "(?m)^(?:precise\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*\\{");
    private static final Pattern CALL = Pattern.compile("(?<![A-Za-z0-9_.])([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    private static final String SHADER = FusedLibCampaign.shaderSource();
    private static final byte[] SPIRV = new ShadercCompiler().compile(SHADER);

    @Test
    void theLibraryWithItsHelperClosureCompilesToAModuleWithoutAFloatingType() {
        assertTrue(SPIRV.length > 1000 && SPIRV.length % 4 == 0, "SPIR-V bytes: " + SPIRV.length);
        var inspection = new SpirvNumericContract().inspectSpirv(SPIRV, NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
    }

    @Test
    void theModuleCheckIsNotVacuous() {
        // the same contract rejects a module that does declare a floating type
        String floating = """
                #version 460
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Out { uint outBits[]; };
                void main() { outBits[0] = uint(1.5 * float(outBits[0])); }
                """;
        byte[] module = new ShadercCompiler().compile(floating);
        var inspection = new SpirvNumericContract().inspectSpirv(module, NumericProfile.GPU_IEEE_BITS);
        assertFalse(inspection.valid());
        assertTrue(inspection.violations().stream().anyMatch(v -> v.contains("OpTypeFloat")), inspection.violations().toString());
        assertThrows(IllegalArgumentException.class, () -> SpirvNumericContract.requireSpirv(module, NumericProfile.GPU_IEEE_BITS));
    }

    @Test
    void aLibraryFunctionTheMainCannotReachWouldBeCaughtHere() {
        // every function of fused_lib_ieee.glsl is reachable from main(), directly or through another function
        String library = FusedIeeeLibrary.source();
        Map<String, String> functions = functions(SHADER);
        var libraryNames = new TreeSet<String>();
        var header = HEADER.matcher(library);
        while (header.find()) libraryNames.add(header.group(1));
        assertTrue(libraryNames.size() >= 50, "library functions: " + libraryNames.size());
        var reached = new TreeSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add("main");
        while (!pending.isEmpty()) {
            String body = functions.get(pending.removeFirst());
            assertNotNull(body);
            var call = CALL.matcher(body.replaceAll("//[^\n]*", ""));
            while (call.find()) {
                String callee = call.group(1);
                if (functions.containsKey(callee) && reached.add(callee)) pending.addLast(callee);
            }
        }
        var unreached = new TreeSet<>(libraryNames);
        unreached.removeAll(reached);
        assertEquals(new TreeSet<String>(), unreached, "library functions that the compiled main never reaches");
    }

    @Test
    void theShaderDeclaresNoFloatingTypeWordAndTheLibraryTextIsPartOfIt() {
        assertTrue(SHADER.contains(FusedIeeeLibrary.source()));
        assertFalse(SHADER.matches("(?s).*\\b(float|double|fvec[234]|dvec[234])\\b.*"));
        assertEquals(SHADER, FusedLibCampaign.shaderSource());
        var unique = new LinkedHashMap<String, Integer>();
        var header = HEADER.matcher(SHADER);
        while (header.find()) unique.merge(header.group(1), 1, Integer::sum);
        unique.forEach((name, count) -> assertEquals(1, count, "function " + name + " is declared more than once"));
    }

    /** Every function of the shader text by name, header to matching brace. */
    private static Map<String, String> functions(String source) {
        var result = new LinkedHashMap<String, String>();
        var header = HEADER.matcher(source);
        int from = 0;
        while (header.find(from)) {
            int depth = 1, index = header.end();
            while (index < source.length() && depth > 0) {
                char value = source.charAt(index++);
                if (value == '{') depth++;
                else if (value == '}') depth--;
            }
            result.put(header.group(1), source.substring(header.start(), index));
            from = index;
        }
        return result;
    }
}
