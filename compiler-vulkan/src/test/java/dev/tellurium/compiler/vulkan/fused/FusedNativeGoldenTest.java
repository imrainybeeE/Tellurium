// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native kernel text and tables of {@link FusedNoiseCompiler}, pinned for the fixtures of
 * {@link FusedFixtures}.  The tested-generator lists are keyed on the hash of this text, and an emulated
 * numeric dialect is being built beside it, so a change to any character of what the compiler generates for
 * the native dialect, or to the layout of its tables, fails here instead of silently sending every listed
 * generator back to the original code.
 *
 * <p>The pinned values are the ones of a host whose line separator is LF.  The native column, corner and
 * XZ store lines end in {@code System.lineSeparator()} ({@code FusedKernels.kernels}), so the text, and every
 * hash of it, differs on a CRLF host; the tests are skipped there rather than compared against values that
 * cannot match.  The text also holds {@code Double.toString} literals, which are the shortest round-trip
 * decimals from JDK 19 on; the build compiles and tests with a JDK 21 toolchain.</p>
 *
 * <p>Whoever changes the native output on purpose runs the exactness matrix for every build, replaces the
 * tested lists, and then records the new values here (see {@code KernelTextIdentityTest}).</p>
 */
class FusedNativeGoldenTest {
    /** What is pinned for one fixture: SHA-256 of {@code Compiled.source} and of the two fingerprints, the shape counts, the tables. */
    record Golden(String sourceSha256, String structureFingerprint, String surfaceFingerprint, String shape, String tablesSha256) {}

    @BeforeEach
    void onlyWhereThePinnedValuesApply() {
        assumeTrue("\n".equals(System.lineSeparator()), "the pinned values are those of a host with LF line separators");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> fixtures() {
        return FusedFixtures.all().stream().map(fixture -> org.junit.jupiter.params.provider.Arguments.of(fixture.name(), fixture));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void nativeOutputIsCharacterForCharacterWhatItWasPinnedAs(String name, FusedFixtures.Fixture fixture) {
        Golden expected = GOLDEN.get(name);
        assertTrue(expected != null, "no pinned values for fixture " + name);
        FusedNoiseCompiler.Compiled compiled = new FusedNoiseCompiler().compile(fixture.request());
        Golden actual = observe(compiled);
        assertEquals(expected.sourceSha256(), actual.sourceSha256(), name + ": the generated kernel source changed");
        assertEquals(expected.structureFingerprint(), actual.structureFingerprint(), name + ": the structure fingerprint changed");
        assertEquals(expected.surfaceFingerprint(), actual.surfaceFingerprint(), name + ": the surface fingerprint changed");
        assertEquals(expected.shape(), actual.shape(), name + ": the channel counts or the surface header offset changed");
        assertEquals(expected.tablesSha256(), actual.tablesSha256(), name + ": the double or uint table changed");
    }

    @Test
    void everyFixtureIsPinnedAndNoPinnedRowIsLeftOverFromARemovedFixture() {
        var fixtureNames = new TreeSet<String>();
        for (var fixture : FusedFixtures.all()) assertTrue(fixtureNames.add(fixture.name()), "duplicate fixture name " + fixture.name());
        assertEquals(fixtureNames, new TreeSet<>(GOLDEN.keySet()));
    }

    /** A pinned value only means something if the same request compiles to the same text every time. */
    @Test
    void compilingTheSameFixtureTwiceGivesTheSameText() {
        for (var fixture : FusedFixtures.all()) {
            Golden first = observe(new FusedNoiseCompiler().compile(fixture.request()));
            Golden second = observe(new FusedNoiseCompiler().compile(fixture.request()));
            assertEquals(first, second, fixture.name());
        }
    }

    @Test
    void sourceFingerprintIsTheHashOfTheSourceAndTheStructureFingerprintLeavesOutOnlyTheSeeds() {
        for (var fixture : FusedFixtures.all()) {
            FusedNoiseCompiler.Compiled compiled = new FusedNoiseCompiler().compile(fixture.request());
            assertEquals(FusedNoiseCompiler.sha256(compiled.source()), compiled.fingerprint(), fixture.name());
            long seedLines = compiled.source().lines()
                    .filter(line -> line.startsWith("const int64_t AQ_") || line.startsWith("const int64_t ORE_")).count();
            assertEquals(6, seedLines, fixture.name() + ": three seed constants for each of the two random sources");
        }
    }

    static Golden observe(FusedNoiseCompiler.Compiled compiled) {
        String shape = "flat=" + compiled.flatChannels() + " interpolated=" + compiled.interpolatedChannels()
                + " xz=" + compiled.xzChannels() + " surfaceHeader=" + compiled.surfaceHeader();
        // The fingerprint is already the hash of the source; hashing the source here keeps this test independent of how the compiler names it.
        return new Golden(FusedNoiseCompiler.sha256(compiled.source()), compiled.structureFingerprint(),
                compiled.surfaceFingerprint(), shape, tablesSha256(compiled));
    }

    /** SHA-256 over the raw bits of the double table and the ints of the uint table, big-endian, each with its length. */
    private static String tablesSha256(FusedNoiseCompiler.Compiled compiled) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(compiled.doubleTable().length);
                for (double value : compiled.doubleTable()) out.writeLong(Double.doubleToRawLongBits(value));
                out.writeInt(compiled.permTable().length);
                for (int value : compiled.permTable()) out.writeInt(value);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Recorded from the tree before the emulated dialect was added.  Name, source, structure, surface, shape, tables. */
    private static final Map<String, Golden> GOLDEN = Map.ofEntries(
        Map.entry("overworld-classic", new Golden(
                "086467eda4268a4c1ceac47149b42c7760c2c93680be94cca8612e15ca56fc51",
                "79d8f1ddb8e25beb557722b374ce3069d2ba015a04cca0d90d47d4e8d8960644",
                null,
                "flat=4 interpolated=3 xz=2 surfaceHeader=0",
                "0be4554df219a95aa5c65f6eea104eb910e4379b49777607dfbb53f4286f5458")),
        Map.entry("overworld-fluid-rule-1.21.2-surface", new Golden(
                "3bcc1595c0c78829f7c93b5318134e2c8dc0a5305be8403203fc33c86925c079",
                "74cd165f2ce08d7851b9a7776b3e003ea512b6c586c470af4be837a6ed56845c",
                "d58c4e7e4144bb3c52995e2a4b4785dc9ab3f54bccf9a70bce4309f8f9c22017",
                "flat=4 interpolated=3 xz=2 surfaceHeader=47853",
                "808cc8af974d09328f71cd9b544dbc568c95828b286868da7686d0ad84ab50b5")),
        Map.entry("overworld-find-top-surface", new Golden(
                "564117242d80882541f7c56bd0635c5e4fe274324730e04eb9744dd52b8b15ef",
                "f1325a3e9d08ed0fb37785560078fbfb7ebc0c2aa30dbbc3f3e3a255d8dcaa7a",
                "3bf1dfad93cc63afeafa6f5a577366900fda9aadbdae948d80bf8803f3cd2fa8",
                "flat=4 interpolated=3 xz=2 surfaceHeader=39149",
                "32a9e2980c83e910a0e50512df342bf8b78c70c3a63732dc29ebdca74eed3e17")),
        Map.entry("tall-plain-level-ores-only", new Golden(
                "402af7948a51078816820788690032889572486ab520d9e341f9308218f4e5e3",
                "b0f873b487f88578c3588dd5eeb4681f9b9bf81f6d6d5752bdb47a1f9374ef87",
                null,
                "flat=4 interpolated=3 xz=2 surfaceHeader=0",
                "36bedd4f8a574ed867622f87b52340842913b423bac966cd90dfe130fbfc14ca")),
        Map.entry("nether-like", new Golden(
                "124f011b6085ac49764a55554fc0ca93bf1ac1fdd79620afa8e756511f6c4af9",
                "76af329cc0b91bcbaeb243a97b0b15e86471405d1308c9e15114bdb050c573ff",
                null,
                "flat=1 interpolated=1 xz=0 surfaceHeader=0",
                "68803fc34cbf824c5008dd8cc020a77b2a72214097fa84d1ef0d32a5b4cb7495")),
        Map.entry("nether-like-surface", new Golden(
                "e0cb6abf65bd67e00196083d11083c5049f15dc1c24964a78247703d35f37912",
                "c10112b9ab54fd1d1e31b7ac86c8155f29572d3696cb13ed517ac456072dedf9",
                "a0b0386f3c4bbd698340f002aa9d060a2c57fc771112ecfdad40d6c027776e0c",
                "flat=1 interpolated=1 xz=0 surfaceHeader=28723",
                "5602520c8ba85d41f81c792ab0ce963faaf2491149fd3ef0a7587fe5e3579d02")),
        Map.entry("end-like-surface", new Golden(
                "9942100c9f1b2872eb58cdc262eadfafb917e0308b175ea22ca2456a5d5d066c",
                "52e64ccc7786bd4be7d46fa0d89bcfbccda99032f65443eaba4ec0709f9893df",
                "bc9e8a8732cc97ae5381bca085b0860d7b214cfbc45851b47c2f0b52722b33c2",
                "flat=2 interpolated=1 xz=0 surfaceHeader=25395",
                "cc8cf12b4bc34dacdc8211d8abd0405ca7449e109f11b3a1834177a42f685759")),
        Map.entry("every-operation", new Golden(
                "047fa58581f923699b1dd6d0e1d06b380d7b156673c634ac89d0da037e6fc473",
                "3753da7fe7a3f36b0f49e1462ab4a5fec655278e998d971c67cf84f57dcc5449",
                null,
                "flat=3 interpolated=2 xz=0 surfaceHeader=0",
                "0eef04f4e5bf008b7b6777df720e92f558b2efa11e389f7323d39951e4f6ca4e")),
        Map.entry("spaghetti-and-blend", new Golden(
                "d548b54663d4c224f4ba230222cf34c02034c04e60feccaab377367164d672ed",
                "47132633e684c32252434d1c67900c8c490d8bb8bbb4ce028dd466f27880c494",
                "a9d6f863d2f9dfa001b3a98a88e93bd55fc5ad335870559bbd38ffd01f56791c",
                "flat=0 interpolated=2 xz=2 surfaceHeader=37471",
                "8e68a349a26c084511ac97f89a3c79a2db10196c703834864e6b8ae087fa0a5b")),
        Map.entry("blended-and-end-parameters", new Golden(
                "c1c914217a355fc851484b0e59aff7db254fb989b9d7268a22eccc83ef109474",
                "173e9e5b8ffe0a6648454f7eb79558466493d78bba23bc808a5b5aca2ea4c05c",
                null,
                "flat=1 interpolated=1 xz=0 surfaceHeader=0",
                "881918f234dc5b4433b0b29a223cc60376e21f2caf8692b4f4f06d331496169d")));
}
