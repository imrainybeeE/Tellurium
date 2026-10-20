// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The tested lists, the dialect and the words about arithmetic: all decided without a GPU or a running game. */
class GpuNumericsTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);

    private static InputStream list(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)); }

    /** The loader as it was before the emulated profile existed, for the lists made for the GPU's own fp64. */
    private static Map<String, String> legacyLoad(String text, String minecraft) throws IOException {
        Map<String, String> out = new HashMap<>();
        var properties = new Properties();
        properties.load(list(text));
        if (!minecraft.equals(properties.remove("minecraft"))) return Map.of();
        for (String key : properties.stringPropertyNames()) out.put(key, properties.getProperty(key));
        return Map.copyOf(out);
    }

    @Test void aNativeListWithoutAProfileLoadsAsItAlwaysDid() throws IOException {
        String text = "# comment\nminecraft=26.1.2\n" + A + "=vanilla-overworld\nsurface." + A + "." + B + "=vanilla-overworld\n";
        assertEquals(Map.of(A, "vanilla-overworld", "surface." + A + "." + B, "vanilla-overworld"),
                GpuNumerics.parseQualified(list(text), "26.1.2", NumericProfile.NATIVE_QUALIFIED));
        for (String variant : new String[]{text, "minecraft=1.21.1\n" + A + "=x\n", A + "=x\n", "minecraft=26.1.2\n", ""}) {
            for (String version : new String[]{"26.1.2", "1.21.1"}) {
                assertEquals(legacyLoad(variant, version), GpuNumerics.parseQualified(list(variant), version, NumericProfile.NATIVE_QUALIFIED), variant);
            }
        }
    }

    @Test void aListHoldsForItsMinecraftVersionOnly() throws IOException {
        String text = "minecraft=26.1.2\nprofile=GPU_IEEE_BITS\n" + A + "=vanilla-overworld\n";
        assertEquals(Map.of(A, "vanilla-overworld"), GpuNumerics.parseQualified(list(text), "26.1.2", NumericProfile.GPU_IEEE_BITS));
        assertEquals(Map.of(), GpuNumerics.parseQualified(list(text), "26.1.1", NumericProfile.GPU_IEEE_BITS));
        assertEquals(Map.of(), GpuNumerics.parseQualified(list(A + "=x\nprofile=GPU_IEEE_BITS\n"), "26.1.2", NumericProfile.GPU_IEEE_BITS));
    }

    @Test void aDeliberatelyEmptyListCarriesItsMeasuredReasonAndAdmitsNothing() throws IOException {
        Map<String, String> loaded = GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\n"
                + "auto.reason=  slower than the CPU here  \n"), "26.1.2", NumericProfile.GPU_IEEE_BITS);
        assertEquals("slower than the CPU here", GpuNumerics.autoReason(loaded));
        assertEquals(null, loaded.get(A), "the reason is not a fingerprint");
        // The reason holds for its own version and profile only, like the list.
        assertEquals(null, GpuNumerics.autoReason(GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\nauto.reason=x\n"),
                "26.1.1", NumericProfile.GPU_IEEE_BITS)));
        assertEquals(null, GpuNumerics.autoReason(GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\nauto.reason=x\n"),
                "26.1.2", NumericProfile.NATIVE_QUALIFIED)));
        assertEquals(null, GpuNumerics.autoReason(Map.of(GpuNumerics.AUTO_REASON, "  ")));
        assertEquals(null, GpuNumerics.autoReason(Map.of()));
    }

    @Test void theProfileKeyIsNeverAnEntry() throws IOException {
        Map<String, String> loaded = GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\n" + A + "=x\n" + B + "=y\n"),
                "26.1.2", NumericProfile.GPU_IEEE_BITS);
        assertEquals(Map.of(A, "x", B, "y"), loaded);
        assertFalse(loaded.containsKey("profile") || loaded.containsKey("minecraft"));
    }

    @Test void theEmulatedProfilesListMustNameItsProfile() throws IOException {
        // No profile line, or another profile's: the list is not for these kernels and admits nothing.
        for (String profileLine : new String[]{"", "profile=NATIVE_QUALIFIED\n", "profile=gpu_ieee_bits\n", "profile=\n", "profile=JAVA_REFERENCE\n"}) {
            assertEquals(Map.of(), GpuNumerics.parseQualified(list("minecraft=26.1.2\n" + profileLine + A + "=x\n"), "26.1.2", NumericProfile.GPU_IEEE_BITS), profileLine);
        }
    }

    @Test void aNativeListNeverAdmitsAnotherProfilesFingerprints() throws IOException {
        assertEquals(Map.of(), GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\n" + A + "=x\n"), "26.1.2", NumericProfile.NATIVE_QUALIFIED));
        assertEquals(Map.of(A, "x"), GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=NATIVE_QUALIFIED\n" + A + "=x\n"), "26.1.2", NumericProfile.NATIVE_QUALIFIED));
    }

    @Test void aFingerprintOfOneListIsNotInTheOther() throws IOException {
        var nativeList = GpuNumerics.parseQualified(list("minecraft=26.1.2\n" + A + "=n\n"), "26.1.2", NumericProfile.NATIVE_QUALIFIED);
        var ieeeList = GpuNumerics.parseQualified(list("minecraft=26.1.2\nprofile=GPU_IEEE_BITS\n" + B + "=i\n"), "26.1.2", NumericProfile.GPU_IEEE_BITS);
        assertNull(nativeList.get(B));
        assertNull(ieeeList.get(A));
    }

    @Test void everyProfileRunsTheDialectThatMatchesItAndNothingElseDoes() {
        assertEquals(FusedNoiseCompiler.Dialect.NATIVE_FP64, GpuNumerics.dialectFor(NumericProfile.NATIVE_QUALIFIED));
        assertEquals(FusedNoiseCompiler.Dialect.IEEE_BITS, GpuNumerics.dialectFor(NumericProfile.GPU_IEEE_BITS));
        for (FusedNoiseCompiler.Dialect dialect : FusedNoiseCompiler.Dialect.values()) {
            assertEquals(dialect, GpuNumerics.dialectFor(dialect.profile()));
        }
        assertThrows(IllegalStateException.class, () -> GpuNumerics.dialectFor(NumericProfile.JAVA_REFERENCE));
        assertThrows(IllegalStateException.class, () -> GpuNumerics.dialectFor(NumericProfile.GPU_NATIVE_DRAFT));
    }

    @Test void theArithmeticIsNamedWithItsReason() {
        assertEquals("native fp64", GpuNumerics.describeArithmetic(NumericProfile.NATIVE_QUALIFIED, null));
        assertEquals("native fp64", GpuNumerics.describeArithmetic(NumericProfile.NATIVE_QUALIFIED, "auto"));
        assertEquals("emulated in integers, no fp64 on this GPU", GpuNumerics.describeArithmetic(NumericProfile.GPU_IEEE_BITS, null));
        assertEquals("emulated in integers, no fp64 on this GPU", GpuNumerics.describeArithmetic(NumericProfile.GPU_IEEE_BITS, "auto"));
        // An fp64 GPU made to emulate must not be described as lacking fp64.
        assertEquals("emulated in integers, as set by tellurium.fast.numeric=ieee", GpuNumerics.describeArithmetic(NumericProfile.GPU_IEEE_BITS, " IEEE "));
    }

    @Test void aGeneratorTheEmulatedKernelsRefuseStaysOnVanillaWithTheReason() {
        assertEquals("The IEEE_BITS dialect is not implemented", GpuNumerics.refusalReason(NumericProfile.NATIVE_QUALIFIED, "The IEEE_BITS dialect is not implemented"));
        String text = GpuNumerics.refusalReason(NumericProfile.GPU_IEEE_BITS, "Unsupported operation foo");
        assertTrue(text.contains("emulated in integers") && text.endsWith("Unsupported operation foo"), text);
    }

    // ---- the list shipped for 26.1.2

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}|surface\\.[0-9a-f]{64}\\.[0-9a-f]{64}");

    private static Path repositoryFile(String relative) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(relative);
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    @Test void the2612ListIsForThatVersionAndTheEmulatedProfile() throws IOException {
        Path file = repositoryFile("mod/versions/26.1.2/src/main/resources/tellurium/fused-qualified-ieee.properties");
        assertNotNull(file, "the 26.1.2 emulated-profile list is missing");
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) { properties.load(in); }
        assertEquals("26.1.2", properties.get("minecraft"));
        assertEquals("GPU_IEEE_BITS", properties.get("profile"));
        // Every other entry is a kernel-structure or surface fingerprint with a name, or the measured reason the
        // list admits nothing in auto mode (it is deliberately empty: on the Apple M3 the path was slower than the CPU).
        int fingerprints = 0;
        for (String key : properties.stringPropertyNames()) {
            if (key.equals("minecraft") || key.equals("profile") || key.equals(GpuNumerics.AUTO_REASON)) continue;
            assertTrue(FINGERPRINT.matcher(key).matches(), "not a fingerprint: " + key);
            assertFalse(properties.getProperty(key).isBlank(), key);
            fingerprints++;
        }
        try (InputStream in = Files.newInputStream(file)) {
            var loaded = GpuNumerics.parseQualified(in, "26.1.2", NumericProfile.GPU_IEEE_BITS);
            assertEquals(properties.size() - 2, loaded.size());
            if (fingerprints == 0) assertNotNull(GpuNumerics.autoReason(loaded), "an empty list says why auto mode stays on the CPU");
        }
        // The native list of the same version never carries a profile of its own, so it cannot be mistaken for this one.
        Path nativeFile = repositoryFile("mod/versions/26.1.2/src/main/resources/tellurium/fused-qualified.properties");
        assertNotNull(nativeFile);
        var nativeProperties = new Properties();
        try (InputStream in = Files.newInputStream(nativeFile)) { nativeProperties.load(in); }
        assertNull(nativeProperties.get("profile"));
        for (String key : properties.stringPropertyNames()) {
            if (FINGERPRINT.matcher(key).matches()) assertFalse(nativeProperties.containsKey(key), "in both lists: " + key);
        }
    }
}
