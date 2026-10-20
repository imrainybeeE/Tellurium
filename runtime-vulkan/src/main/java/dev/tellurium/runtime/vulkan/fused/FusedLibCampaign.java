// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

import dev.tellurium.compiler.vulkan.fused.FusedIeeeHelpers;
import dev.tellurium.compiler.vulkan.fused.FusedIeeeLibrary;
import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.runtime.vulkan.Hashes;
import dev.tellurium.runtime.vulkan.ShadercCompiler;
import dev.tellurium.semantic.program.NumericProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Device campaign for {@code fused_lib_ieee.glsl}: every function of the library that can be evaluated on its
 * own is run on the device on listed vectors and seeded random inputs, and its result (and the bail flags it
 * raised) is compared bit for bit with {@link FusedLibReference}, the Java transliteration of the native
 * {@code fused_lib.glsl}. The table-driven functions (noise, End island, splines) run against random tables
 * that the device and the reference read alike.
 *
 * <p>The reference runs {@code jdiv} with {@link FusedLibReference#correctlyRoundedZero} on: the integer port
 * returns the correctly rounded quotient, which differs from the native text only in the sign of a quotient
 * that underflows to negative zero. The cases where that happens are counted in the report. A NaN result
 * passes when the reference is NaN, whatever its sign or payload.</p>
 *
 * <p>This class builds the shader, the inputs and the verdicts and has no Vulkan code: the runner hands in a
 * {@link Dispatcher}. {@link #shaderSource()} is also compiled on the host by the runtime tests, so the shader is
 * known to compile and to contain no floating type before it reaches a device.</p>
 */
public final class FusedLibCampaign {
    /** One dispatch: binding 0 is {@code input}, binding 1 the output, bindings 2.. are {@code extra} in order. */
    public interface Dispatcher {
        int[] dispatch(byte[] spirv, int operation, int[] input, int count, int outputWords, int[][] extra);
    }

    static final int INPUT_WORDS = 20;
    static final int OUTPUT_WORDS = 4;
    private static final int MAX_REPORTED = 16;

    /** How a device answer is compared with the reference. */
    enum Kind { INT32, BITS32, BITS64, EXACT64, BOOL }

    interface CaseGen { long[] next(SplittableRandom random, Tables tables); }

    interface Eval { long eval(FusedLibReference lib, long[] c); }

    /** One function under test; {@code glsl} is an expression over the operands a, b, c ... j (uvec2 slots). */
    record Op(String name, Kind kind, String glsl, int count, int chunk, long[][] fixed, CaseGen gen, Eval eval) {}

    private FusedLibCampaign() {}

    // ---- the shader --------------------------------------------------------------------------------------------

    /** The campaign shader: the helper closure, the library, a few wrappers, and a switch over {@link #operations()}. */
    public static String shaderSource() {
        var source = new StringBuilder();
        source.append("""
                #version 460
                #extension GL_ARB_gpu_shader_int64 : require
                #define SPLINE_COORDS 3
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding = 1) writeonly buffer Outputs { uint outputBits[]; };
                layout(std430, binding = 2) buffer Flags { uint flags[]; };
                layout(std430, binding = 3) readonly buffer Perm { uint perm[]; };
                layout(std430, binding = 4) readonly buffer DTab { uvec2 dtab[]; };
                layout(push_constant) uniform Dispatch { uint count; uint operation; } dispatch;
                """);
        source.append(IntegerIeeeEmitter.helperSource(FusedIeeeLibrary.HELPER_ROOTS));
        source.append(FusedIeeeHelpers.source());
        source.append(dev.tellurium.compiler.vulkan.fused.FastIeeeHelpers.source());
        source.append(FusedIeeeLibrary.source());
        source.append("""

                // Cases that need a statement or a local of the library's own types.
                uint splineCase(int root, uint c0, uint c1, uint c2) {
                    uint cs[SPLINE_COORDS] = uint[SPLINE_COORDS](c0, c1, c2);
                    return splineEval(root, cs);
                }
                uint bailCase(uint code) {
                    bail(code);
                    return code;
                }
                uint nextFloatCase(bool legacyAlgorithm, int64_t lo, int64_t hi, int64_t legacyState) {
                    Rng r;
                    r.lo = lo; r.hi = hi; r.legacy = legacyState; r.isLegacy = legacyAlgorithm;
                    return rngNextFloat(r);
                }
                int nextIntCase(bool legacyAlgorithm, int64_t seed, int64_t lo, int64_t hi, int x, int y, int z, int bound) {
                    Rng r = rngAt(legacyAlgorithm, seed, lo, hi, x, y, z);
                    return rngNextInt(r, bound);
                }
                int64_t xoroCase(int64_t lo, int64_t hi) {
                    Rng r;
                    r.lo = lo; r.hi = hi; r.legacy = 0l; r.isLegacy = false;
                    return xoroNext(r);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint ib = index * 20u, ob = index * 4u;
                """);
        String[] names = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};
        for (int slot = 0; slot < names.length; slot++) {
            source.append("    uvec2 ").append(names[slot]).append(" = uvec2(inputBits[ib + ").append(slot * 2)
                    .append("u], inputBits[ib + ").append(slot * 2 + 1).append("u]);\n");
        }
        source.append("""
                    flags[index] = 0u;
                    gChunk = index;
                    uvec2 value = uvec2(0xffffffffu, 0xffffffffu);
                    switch (dispatch.operation) {
                """);
        List<Op> operations = operations();
        for (int id = 0; id < operations.size(); id++) {
            Op op = operations.get(id);
            String value = switch (op.kind()) {
                case INT32 -> "uvec2(uint(" + op.glsl() + "), 0u)";
                case BITS32 -> "uvec2(" + op.glsl() + ", 0u)";
                case BITS64 -> op.glsl();
                case EXACT64 -> "unpackUint2x32(uint64_t(" + op.glsl() + "))";
                case BOOL -> "uvec2((" + op.glsl() + ") ? 1u : 0u, 0u)";
            };
            source.append("        case ").append(id).append("u: value = ").append(value).append("; break;\n");
        }
        source.append("""
                    }
                    outputBits[ob] = value.x;
                    outputBits[ob + 1u] = value.y;
                    outputBits[ob + 2u] = flags[index];
                    outputBits[ob + 3u] = 0u;
                }
                """);
        return source.toString();
    }

    // ---- the run -----------------------------------------------------------------------------------------------

    public static void run(Dispatcher dispatcher, String deviceName, String output) throws IOException {
        System.out.println(execute(dispatcher, deviceName, output, 1.0).summary());
    }

    /** What a finished run reports: the JSON written to the report file and the one-line verdict. */
    record Report(String json, String summary) {}

    /** {@code scale} multiplies every operation's random case count (the listed vectors always run); 1.0 is the campaign. */
    static Report execute(Dispatcher dispatcher, String deviceName, String output, double scale) throws IOException {
        String source = shaderSource();
        byte[] spirv = new ShadercCompiler().compile(source);
        var inspection = new SpirvNumericContract().inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
        if (!inspection.valid()) {
            throw new IllegalStateException("Fused library module violates GPU_IEEE_BITS: " + inspection.violations());
        }
        var random = new SplittableRandom(0x5eed11b0f05ed002L);
        Tables tables = Tables.build(random);
        var lib = new FusedLibReference(tables.perm, tables.dtab);
        lib.correctlyRoundedZero = true;
        int[] permWords = tables.perm;
        int[] dtabWords = new int[tables.dtab.length * 2];
        for (int i = 0; i < tables.dtab.length; i++) {
            long bits = Double.doubleToRawLongBits(tables.dtab[i]);
            dtabWords[i * 2] = (int) bits;
            dtabWords[i * 2 + 1] = (int) (bits >>> 32);
        }
        List<Op> operations = operations();
        int[] comparisons = new int[operations.size()];
        int[] bailed = new int[operations.size()];
        var failures = new ArrayList<String>();
        for (int id = 0; id < operations.size(); id++) {
            Op op = operations.get(id);
            long[][] cases = cases(op, random, tables, scale);
            int mismatches = 0;
            var firstFailures = new StringBuilder();
            for (int start = 0; start < cases.length; start += op.chunk()) {
                int size = Math.min(op.chunk(), cases.length - start);
                int[] input = new int[size * INPUT_WORDS];
                for (int i = 0; i < size; i++) {
                    long[] c = cases[start + i];
                    for (int slot = 0; slot < c.length; slot++) {
                        input[i * INPUT_WORDS + slot * 2] = (int) c[slot];
                        input[i * INPUT_WORDS + slot * 2 + 1] = (int) (c[slot] >>> 32);
                    }
                }
                int[] actual = dispatcher.dispatch(spirv, id, input, size, OUTPUT_WORDS,
                        new int[][] {new int[size], permWords, dtabWords});
                for (int i = 0; i < size; i++) {
                    long[] c = cases[start + i];
                    lib.clearBails();
                    long expected = op.eval().eval(lib, c);
                    int expectedBail = lib.bailCode;
                    long actualBits = Integer.toUnsignedLong(actual[i * OUTPUT_WORDS])
                            | (Integer.toUnsignedLong(actual[i * OUTPUT_WORDS + 1]) << 32);
                    int actualBail = actual[i * OUTPUT_WORDS + 2];
                    if (expectedBail != 0) bailed[id]++;
                    if (matches(op.kind(), expected, actualBits) && expectedBail == actualBail) continue;
                    mismatches++;
                    if (mismatches <= MAX_REPORTED) {
                        firstFailures.append("\n  index=").append(start + i).append(" input=");
                        for (int slot = 0; slot < c.length; slot++) {
                            firstFailures.append(slot == 0 ? "" : ",").append("0x").append(Long.toHexString(c[slot]));
                        }
                        firstFailures.append(" expected=0x").append(Long.toHexString(expected)).append(" bail=").append(expectedBail)
                                .append(" actual=0x").append(Long.toHexString(actualBits)).append(" bail=").append(actualBail);
                    }
                }
            }
            if (mismatches != 0) {
                // Every operation still runs, so one failing function does not hide another.
                failures.add(op.name() + ": " + mismatches + " of " + cases.length + " mismatches (first "
                        + Math.min(mismatches, MAX_REPORTED) + ")" + firstFailures);
            } else {
                comparisons[id] = cases.length;
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("fused library campaign FAILED on device " + deviceName + ": " + failures.size()
                    + " of " + operations.size() + " functions disagree with the reference\n" + String.join("\n", failures));
        }
        int total = 0;
        for (int count : comparisons) total += count;
        String sourceHash = Hashes.sha256(source.getBytes(StandardCharsets.UTF_8));
        String spirvHash = Hashes.sha256(spirv);
        StringBuilder json = new StringBuilder("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"status\": \"PASS\",\n")
                .append("  \"campaign\": \"fused-lib\",\n")
                .append("  \"profile\": \"GPU_IEEE_BITS\",\n")
                .append("  \"device\": \"").append(deviceName.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n")
                .append("  \"sourceSha256\": \"").append(sourceHash).append("\",\n")
                .append("  \"spirvSha256\": \"").append(spirvHash).append("\",\n")
                .append("  \"comparisons\": ").append(total).append(",\n")
                .append("  \"mismatches\": 0,\n")
                .append("  \"zeroSignDeviationsFromNative\": ").append(lib.zeroSignDeviations).append(",\n")
                .append("  \"operations\": {");
        for (int id = 0; id < operations.size(); id++) {
            if (id > 0) json.append(',');
            json.append("\n    \"").append(operations.get(id).name()).append("\": {\"comparisons\": ").append(comparisons[id])
                    .append(", \"bailed\": ").append(bailed[id]).append('}');
        }
        json.append("\n  }\n}\n");
        if (output != null) {
            Path reportPath = Path.of(output).toAbsolutePath().normalize();
            Path parent = reportPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(reportPath, json.toString());
        }
        return new Report(json.toString(), "nativeIntegerIeee --fused-lib DEVICE_PASS device=" + deviceName + " functions="
                + operations.size() + " comparisons=" + total + " mismatches=0 zeroSignDeviationsFromNative=" + lib.zeroSignDeviations
                + " sourceSha256=" + sourceHash + (output == null ? "" : " report=" + Path.of(output).toAbsolutePath().normalize()));
    }

    static long[][] cases(Op op, SplittableRandom random, Tables tables, double scale) {
        long[][] result = Arrays.copyOf(op.fixed(), op.fixed().length + Math.max(1, (int) (op.count() * scale)));
        for (int i = op.fixed().length; i < result.length; i++) result[i] = op.gen().next(random, tables);
        return result;
    }

    static boolean matches(Kind kind, long expected, long actual) {
        switch (kind) {
            case INT32:
                return (int) expected == (int) actual && (actual >>> 32) == 0L;
            case BOOL:
            case EXACT64:
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

    // ---- the functions under test -----------------------------------------------------------------------------

    private static final List<Op> OPERATIONS = buildOperations();

    static List<Op> operations() { return OPERATIONS; }

    private static double d(long bits) { return Double.longBitsToDouble(bits); }
    private static float f(long bits) { return Float.intBitsToFloat((int) bits); }
    private static int n(long bits) { return (int) bits; }
    private static long b(double value) { return Double.doubleToRawLongBits(value); }
    private static long b(float value) { return Float.floatToRawIntBits(value) & 0xffffffffL; }

    private static List<Op> buildOperations() {
        var ops = new ArrayList<Op>();
        long[] doubles = listedDoubles();
        long[] floats = listedFloats();
        int cheap = 300_000, division = 200_000;

        // narrowing and rounding
        ops.add(unary("jd2i", Kind.INT32, "jd2i(a)", cheap, doubles, FusedLibCampaign::anyDouble, (l, c) -> FusedLibReference.jd2i(d(c[0]))));
        ops.add(unary("mfloor", Kind.INT32, "mfloor(a)", cheap, doubles, FusedLibCampaign::anyDouble, (l, c) -> FusedLibReference.mfloor(d(c[0]))));
        ops.add(binary("jmin", Kind.BITS64, "jmin(a, b)", cheap, doubles, pair(FusedLibCampaign::anyDouble), (l, c) -> b(FusedLibReference.jmin(d(c[0]), d(c[1])))));
        ops.add(binary("jmax", Kind.BITS64, "jmax(a, b)", cheap, doubles, pair(FusedLibCampaign::anyDouble), (l, c) -> b(FusedLibReference.jmax(d(c[0]), d(c[1])))));
        ops.add(binary("jminf", Kind.BITS32, "jminf(a.x, b.x)", cheap, floats, pair(FusedLibCampaign::anyFloat), (l, c) -> b(FusedLibReference.jminf(f(c[0]), f(c[1])))));
        ops.add(binary("jmaxf", Kind.BITS32, "jmaxf(a.x, b.x)", cheap, floats, pair(FusedLibCampaign::anyFloat), (l, c) -> b(FusedLibReference.jmaxf(f(c[0]), f(c[1])))));
        ops.add(unary("nextUpPos", Kind.BITS64, "nextUpPos(a)", cheap, doubles, FusedLibCampaign::anyDouble, (l, c) -> b(FusedLibReference.nextUpPos(d(c[0])))));
        ops.add(unary("nextDownPos", Kind.BITS64, "nextDownPos(a)", cheap, doubles, FusedLibCampaign::anyDouble, (l, c) -> b(FusedLibReference.nextDownPos(d(c[0])))));
        ops.add(unary("safeMagnitude", Kind.BOOL, "safeMagnitude(a)", cheap, doubles, FusedLibCampaign::anyDouble, (l, c) -> FusedLibReference.safeMagnitude(d(c[0])) ? 1L : 0L));

        // division and square root with their guards and bails
        ops.add(binary("jdiv", Kind.BITS64, "jdiv(a, b)", division, doubles, FusedLibCampaign::divisionPair, (l, c) -> b(l.jdiv(d(c[0]), d(c[1])))));
        ops.add(unary("jsqrt", Kind.BITS64, "jsqrt(a)", division, doubles, FusedLibCampaign::sqrtOperand, (l, c) -> b(l.jsqrt(d(c[0])))));
        ops.add(binary("jdivf", Kind.BITS32, "jdivf(a.x, b.x)", division, floats, FusedLibCampaign::floatDivisionPair, (l, c) -> b(l.jdivf(f(c[0]), f(c[1])))));
        ops.add(unary("jsqrtf", Kind.BITS32, "jsqrtf(a.x)", division, floats, FusedLibCampaign::anyFloat, (l, c) -> b(l.jsqrtf(f(c[0])))));
        ops.add(binary("jremf", Kind.BITS32, "jremf(a.x, b.x)", division, floats, FusedLibCampaign::remainderPair, (l, c) -> b(l.jremf(f(c[0]), f(c[1])))));

        // interpolation
        ops.add(random("lerpd", Kind.BITS64, "lerpd(a, b, c)", cheap, FusedLibCampaign::lerpTriple, (l, c) -> b(FusedLibReference.lerpd(d(c[0]), d(c[1]), d(c[2])))));
        ops.add(random("lerpf", Kind.BITS32, "lerpf(a.x, b.x, c.x)", cheap, FusedLibCampaign::floatTriple, (l, c) -> b(FusedLibReference.lerpf(f(c[0]), f(c[1]), f(c[2])))));
        ops.add(random("clampedLerp", Kind.BITS64, "clampedLerp(a, b, c)", cheap, FusedLibCampaign::lerpTriple, (l, c) -> b(FusedLibReference.clampedLerp(d(c[0]), d(c[1]), d(c[2])))));
        ops.add(random("inverseLerp", Kind.BITS64, "inverseLerp(a, b, c)", division, FusedLibCampaign::inverseLerpTriple, (l, c) -> b(l.inverseLerp(d(c[0]), d(c[1]), d(c[2])))));
        ops.add(random("clampedMap", Kind.BITS64, "clampedMap(a, b, c, d, e)", division, FusedLibCampaign::mapOperands, (l, c) -> b(l.clampedMap(d(c[0]), d(c[1]), d(c[2]), d(c[3]), d(c[4])))));
        ops.add(random("mapd", Kind.BITS64, "mapd(a, b, c, d, e)", division, FusedLibCampaign::mapOperands, (l, c) -> b(l.mapd(d(c[0]), d(c[1]), d(c[2]), d(c[3]), d(c[4])))));
        ops.add(random("mclamp", Kind.BITS64, "mclamp(a, b, c)", cheap, FusedLibCampaign::clampTriple, (l, c) -> b(FusedLibReference.mclamp(d(c[0]), d(c[1]), d(c[2])))));

        // noise building blocks
        ops.add(unary("smoothstepd", Kind.BITS64, "smoothstepd(a)", cheap, doubles, FusedLibCampaign::unitDouble, (l, c) -> b(FusedLibReference.smoothstepd(d(c[0])))));
        ops.add(unary("wrapd", Kind.BITS64, "wrapd(a)", cheap, doubles, FusedLibCampaign::wrapOperand, (l, c) -> b(FusedLibReference.wrapd(d(c[0])))));
        ops.add(random("gradDot", Kind.BITS64, "gradDot(int(a.x), b, c, d)", cheap, (r, t) -> new long[] {r.nextInt(), anyDouble(r), anyDouble(r), anyDouble(r)},
                (l, c) -> b(FusedLibReference.gradDot(n(c[0]), d(c[1]), d(c[2]), d(c[3])))));
        ops.add(random("simplexCorner", Kind.BITS64, "simplexCorner(int(a.x), b, c)", cheap, (r, t) -> new long[] {r.nextInt(256), cornerOffset(r), cornerOffset(r)},
                (l, c) -> b(FusedLibReference.simplexCorner(n(c[0]), d(c[1]), d(c[2])))));

        // the bail flag itself
        ops.add(random("bail", Kind.INT32, "bailCase(a.x)", cheap, (r, t) -> new long[] {r.nextInt(64)}, (l, c) -> { l.bail(n(c[0])); return c[0]; }));

        // integer-only functions, copied byte for byte, run to prove they still compile and behave
        ops.add(random("jrem", Kind.INT32, "jrem(int(a.x), int(b.x))", cheap, FusedLibCampaign::divisionInts, (l, c) -> FusedLibReference.jrem(n(c[0]), n(c[1]))));
        ops.add(random("jfloorDiv", Kind.INT32, "jfloorDiv(int(a.x), int(b.x))", cheap, FusedLibCampaign::divisionInts, (l, c) -> FusedLibReference.jfloorDiv(n(c[0]), n(c[1]))));
        ops.add(random("jfloorMod", Kind.INT32, "jfloorMod(int(a.x), int(b.x))", cheap, FusedLibCampaign::divisionInts, (l, c) -> FusedLibReference.jfloorMod(n(c[0]), n(c[1]))));
        ops.add(random("positionSeed", Kind.EXACT64, "positionSeed(int(a.x), int(b.x), int(c.x))", cheap, (r, t) -> new long[] {r.nextInt(), r.nextInt(), r.nextInt()},
                (l, c) -> FusedLibReference.positionSeed(n(c[0]), n(c[1]), n(c[2]))));
        ops.add(random("mixStafford", Kind.EXACT64, "mixStafford(dbits(a))", cheap, (r, t) -> new long[] {r.nextLong()}, (l, c) -> FusedLibReference.mixStafford(c[0])));
        ops.add(random("xoroNext", Kind.EXACT64, "xoroCase(dbits(a), dbits(b))", cheap, (r, t) -> new long[] {r.nextLong(), r.nextLong()},
                (l, c) -> FusedLibReference.xoroNext(rng(c[0], c[1], 0L, false))));
        ops.add(random("rngNextInt", Kind.INT32, "nextIntCase(d.x != 0u, dbits(a), dbits(b), dbits(c), int(e.x), int(f.x), int(g.x), int(h.x))", cheap,
                FusedLibCampaign::nextIntOperands, (l, c) -> {
                    var r = FusedLibReference.rngAt(c[3] != 0L, c[0], c[1], c[2], n(c[4]), n(c[5]), n(c[6]));
                    return FusedLibReference.rngNextInt(r, n(c[7]));
                }));
        ops.add(random("rngNextFloat", Kind.BITS32, "nextFloatCase(d.x != 0u, dbits(a), dbits(b), dbits(c))", cheap,
                (r, t) -> new long[] {r.nextLong(), r.nextLong(), r.nextLong() & 0xffffffffffffL, r.nextInt(2)},
                (l, c) -> b(FusedLibReference.rngNextFloat(rng(c[0], c[1], c[2], c[3] != 0L)))));

        // table-driven functions: improved noise, Perlin and normal noise, blended noise, End island, splines
        ops.add(tabled("improvedNoise", Kind.BITS64, "improvedNoise(int(a.x), b, c, d, e, f, g, h, i)", 20_000, 2048, (r, t) -> new long[] {
                t.permBase(r), offset(r), offset(r), offset(r), noiseCoordinate(r), noiseCoordinate(r), noiseCoordinate(r), yScale(r), yMax(r)},
                (l, c) -> b(l.improvedNoise(n(c[0]), d(c[1]), d(c[2]), d(c[3]), d(c[4]), d(c[5]), d(c[6]), d(c[7]), d(c[8])))));
        ops.add(tabled("perlinValue", Kind.BITS64, "perlinValue(int(a.x), b, c, d)", 10_000, 1024, (r, t) -> new long[] {
                t.perlin[r.nextInt(t.perlin.length)], worldCoordinate(r), worldCoordinate(r), worldCoordinate(r)},
                (l, c) -> b(l.perlinValue(n(c[0]), d(c[1]), d(c[2]), d(c[3])))));
        ops.add(tabled("normalNoise", Kind.BITS64, "normalNoise(int(a.x), b, c, d)", 4_000, 512, (r, t) -> new long[] {
                t.normal[r.nextInt(t.normal.length)], worldCoordinate(r), worldCoordinate(r), worldCoordinate(r)},
                (l, c) -> b(l.normalNoise(n(c[0]), d(c[1]), d(c[2]), d(c[3])))));
        ops.add(tabled("legacyLevel", Kind.BITS64, "legacyLevel(int(a.x), int(b.x), c, d, e, f, g)", 10_000, 2048, (r, t) -> new long[] {
                t.perlin[r.nextInt(t.perlin.length)], r.nextInt(-1, 20), noiseCoordinate(r), noiseCoordinate(r), noiseCoordinate(r), yScale(r), yMax(r)},
                (l, c) -> b(l.legacyLevel(n(c[0]), n(c[1]), d(c[2]), d(c[3]), d(c[4]), d(c[5]), d(c[6])))));
        ops.add(tabled("blendedNoise", Kind.BITS64, "blendedNoise(int(a.x), int(b.x), int(c.x), int(d.x))", 640, 128, (r, t) -> new long[] {
                t.blended[r.nextInt(t.blended.length)], blockCoordinate(r), r.nextInt(-64, 320), blockCoordinate(r)},
                (l, c) -> b(l.blendedNoise(n(c[0]), n(c[1]), n(c[2]), n(c[3])))));
        ops.add(tabled("simplex2D", Kind.BITS64, "simplex2D(int(a.x), b, c)", 10_000, 2048, (r, t) -> new long[] {
                t.permBase(r), simplexCoordinate(r), simplexCoordinate(r)},
                (l, c) -> b(l.simplex2D(n(c[0]), d(c[1]), d(c[2])))));
        ops.add(tabled("endIsland", Kind.BITS64, "endIsland(int(a.x), int(b.x), int(c.x))", 96, 32, (r, t) -> new long[] {
                t.endIsland, endCoordinate(r), endCoordinate(r)},
                (l, c) -> b(l.endIsland(n(c[0]), n(c[1]), n(c[2])))));
        ops.add(tabled("splineEval", Kind.BITS32, "splineCase(int(a.x), b.x, c.x, d.x)", 20_000, 2048, (r, t) -> new long[] {
                t.splines[r.nextInt(t.splines.length)], splineCoordinate(r), splineCoordinate(r), splineCoordinate(r)},
                (l, c) -> b(l.splineEval(n(c[0]), new float[] {f(c[1]), f(c[2]), f(c[3])}))));
        return List.copyOf(ops);
    }

    private static FusedLibReference.Rng rng(long lo, long hi, long legacy, boolean isLegacy) {
        var r = new FusedLibReference.Rng();
        r.lo = lo;
        r.hi = hi;
        r.legacy = legacy;
        r.isLegacy = isLegacy;
        return r;
    }

    private interface Unary { long next(SplittableRandom random); }

    private static Op unary(String name, Kind kind, String glsl, int count, long[] listed, Unary gen, Eval eval) {
        long[][] fixed = new long[listed.length][];
        for (int i = 0; i < listed.length; i++) fixed[i] = new long[] {listed[i]};
        return new Op(name, kind, glsl, count, 262_144, fixed, (r, t) -> new long[] {gen.next(r)}, eval);
    }

    private static CaseGen pair(Unary gen) { return (r, t) -> new long[] {gen.next(r), gen.next(r)}; }

    private static Op binary(String name, Kind kind, String glsl, int count, long[] listed, CaseGen gen, Eval eval) {
        long[][] fixed = new long[listed.length * listed.length][];
        int index = 0;
        for (long left : listed) for (long right : listed) fixed[index++] = new long[] {left, right};
        return new Op(name, kind, glsl, count, 262_144, fixed, gen, eval);
    }

    private static Op random(String name, Kind kind, String glsl, int count, CaseGen gen, Eval eval) {
        return new Op(name, kind, glsl, count, 262_144, new long[0][], gen, eval);
    }

    private static Op tabled(String name, Kind kind, String glsl, int count, int chunk, CaseGen gen, Eval eval) {
        return new Op(name, kind, glsl, count, chunk, new long[0][], gen, eval);
    }

    // ---- tables -------------------------------------------------------------------------------------------------

    /** The {@code perm} words and {@code dtab} values the table-driven functions read, and where the records start. */
    static final class Tables {
        int[] perm;
        double[] dtab;
        int[] perlin, normal, blended, splines;
        int endIsland;

        long permBase(SplittableRandom random) { return random.nextBoolean() ? 0L : 256L; }

        static Tables build(SplittableRandom r) {
            var tables = new Tables();
            var perm = new ArrayList<Integer>();
            for (int copy = 0; copy < 2; copy++) {
                int[] permutation = new int[256];
                for (int i = 0; i < 256; i++) permutation[i] = i;
                for (int i = 255; i > 0; i--) {
                    int j = r.nextInt(i + 1);
                    int swap = permutation[i];
                    permutation[i] = permutation[j];
                    permutation[j] = swap;
                }
                for (int value : permutation) perm.add(value);
            }
            var dtab = new ArrayList<Double>();
            var perlin = new ArrayList<Integer>();
            for (int count : new int[] {1, 2, 3, 4, 5, 8, 8, 12, 16, 16, 16}) perlin.add(perlin(dtab, count, r));
            tables.perlin = perlin.stream().mapToInt(Integer::intValue).toArray();
            var normal = new ArrayList<Integer>();
            for (int k = 0; k < 6; k++) {
                normal.add(dtab.size());
                dtab.add(k == 0 ? 0.3333333333333333 : (r.nextInt(3) == 0 ? 1.0 : r.nextDouble() * 2.0));
                dtab.add((double) tables.perlin[r.nextInt(tables.perlin.length)]);
                dtab.add((double) tables.perlin[r.nextInt(tables.perlin.length)]);
            }
            tables.normal = normal.stream().mapToInt(Integer::intValue).toArray();
            var blended = new ArrayList<Integer>();
            for (int k = 0; k < 4; k++) {
                int minP = perlin(dtab, 16, r), maxP = perlin(dtab, 16, r), mainP = perlin(dtab, k == 3 ? 5 : 8, r);
                blended.add(dtab.size());
                // the overworld constants first, then random scales
                double[] record = k == 0 ? new double[] {1.0, 1.0, 80.0, 160.0, 8.0}
                        : new double[] {r.nextDouble() * 4.0 + 0.01, r.nextDouble() * 4.0 + 0.01, 10.0 + r.nextDouble() * 200.0,
                                10.0 + r.nextDouble() * 200.0, 1.0 + r.nextDouble() * 15.0};
                for (double value : record) dtab.add(value);
                dtab.add((double) minP);
                dtab.add((double) maxP);
                dtab.add((double) mainP);
            }
            tables.blended = blended.stream().mapToInt(Integer::intValue).toArray();
            tables.endIsland = dtab.size();
            dtab.add(256.0);
            var splines = new ArrayList<Integer>();
            for (int k = 0; k < 24; k++) splines.add(spline(perm, 0, r));
            splines.add(wide(perm, 2, r));
            splines.add(wide(perm, 3, r));
            splines.add(chain(perm, 15, r));
            splines.add(chain(perm, 17, r));
            tables.splines = splines.stream().mapToInt(Integer::intValue).toArray();
            tables.perm = perm.stream().mapToInt(Integer::intValue).toArray();
            tables.dtab = dtab.stream().mapToDouble(Double::doubleValue).toArray();
            return tables;
        }

        private static int perlin(List<Double> dtab, int levels, SplittableRandom r) {
            int index = dtab.size();
            dtab.add((double) levels);
            for (int level = 0; level < levels; level++) {
                dtab.add(switch (r.nextInt(6)) { case 0 -> 0.5; case 1 -> 2.0; case 2 -> r.nextDouble() * 3.0; case 3 -> r.nextInt(12) == 0 ? 0.0 : 0.25; default -> 1.0; });
                dtab.add(r.nextInt(4) == 0 ? r.nextDouble() * 4.0 : Math.scalb(1.0, r.nextInt(9) - 4 + level));
                dtab.add(r.nextInt(4) == 0 ? r.nextDouble() * 2.0 : Math.scalb(1.0, -level));
                dtab.add(r.nextDouble() * 256.0);
                dtab.add(r.nextDouble() * 256.0);
                dtab.add(r.nextDouble() * 256.0);
                dtab.add(r.nextInt(6) == 0 ? -1.0 : (r.nextBoolean() ? 0.0 : 256.0));
            }
            return index;
        }

        /** A spline record at the end of {@code perm}: n, coordinate, then per point location, derivative, kind, payload. */
        private static int spline(List<Integer> perm, int depth, SplittableRandom r) {
            int points = 1 + r.nextInt(6);
            float[] locations = new float[points];
            for (int i = 0; i < points; i++) locations[i] = r.nextInt(4) == 0 ? (r.nextInt(5) - 2) * 0.5f : (r.nextFloat() * 3.0f - 1.5f);
            if (r.nextInt(4) != 0) Arrays.sort(locations);
            int[] words = new int[2 + points * 4];
            words[0] = points;
            words[1] = r.nextInt(3);
            for (int i = 0; i < points; i++) {
                words[2 + i * 4] = Float.floatToRawIntBits(locations[i]);
                words[3 + i * 4] = r.nextInt(3) == 0 ? 0 : Float.floatToRawIntBits(r.nextFloat() * 4.0f - 2.0f);
                boolean child = depth < 2 && r.nextInt(3) == 0;
                words[4 + i * 4] = child ? 1 : 0;
                words[5 + i * 4] = child ? spline(perm, depth + 1, r) : Float.floatToRawIntBits(r.nextFloat() * 2.0f - 1.0f);
            }
            int index = perm.size();
            for (int word : words) perm.add(word);
            return index;
        }

        /**
         * Points 3e38 apart: an interval whose width overflows the binary32 range, so the division of the cubic
         * step meets an infinity and bails, next to the ordinary intervals of the extra middle point.
         */
        private static int wide(List<Integer> perm, int points, SplittableRandom r) {
            int index = perm.size();
            perm.add(points);
            perm.add(r.nextInt(3));
            float[] locations = points == 2 ? new float[] {-3.0e38f, 3.0e38f} : new float[] {-3.0e38f, 0.5f, 3.0e38f};
            for (int i = 0; i < points; i++) {
                perm.add(Float.floatToRawIntBits(locations[i]));
                perm.add(Float.floatToRawIntBits(r.nextFloat() - 0.5f));
                perm.add(0);
                perm.add(Float.floatToRawIntBits(r.nextFloat() * 2.0f - 1.0f));
            }
            return index;
        }

        /** {@code length} records, each with one point whose value is the next record: a stack {@code length} deep. */
        private static int chain(List<Integer> perm, int length, SplittableRandom r) {
            int child = -1;
            for (int level = 0; level < length; level++) {
                int index = perm.size();
                perm.add(1);
                perm.add(0);
                perm.add(Float.floatToRawIntBits(0.0f));
                perm.add(Float.floatToRawIntBits(r.nextFloat()));
                perm.add(child < 0 ? 0 : 1);
                perm.add(child < 0 ? Float.floatToRawIntBits(0.75f) : child);
                child = index;
            }
            return child;
        }
    }

    // ---- inputs ---------------------------------------------------------------------------------------------------

    private static long[] listedDoubles() {
        double minSubnormal = Double.longBitsToDouble(1L);
        double[] values = {0.0, -0.0, minSubnormal, -minSubnormal, Double.longBitsToDouble(0x000fffffffffffffL),
                Double.MIN_NORMAL, 0.5, -0.5, Math.nextDown(1.0), 1.0, -1.0, Math.nextUp(1.0), 2.0, 3.0, 10.0, -10.0, 1.5,
                2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0, 33554432.0, 1e300, -1e300, Double.MAX_VALUE,
                Math.scalb(1.0, 898), Math.scalb(1.0, 899), Math.scalb(1.0, -900), Math.scalb(1.0, -901),
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        long[] raw = new long[values.length + 3];
        for (int i = 0; i < values.length; i++) raw[i] = b(values[i]);
        raw[values.length] = 0x7ff8000000000000L;
        raw[values.length + 1] = 0x7ff0000000000001L;
        raw[values.length + 2] = 0xfff8000000000000L;
        return raw;
    }

    private static long[] listedFloats() {
        return new long[] {0x00000000L, 0x80000000L, 0x00000001L, 0x80000001L, 0x007fffffL, 0x00800000L, 0x3f800000L, 0xbf800000L,
                0x40000000L, 0x40400000L, 0x41500000L, 0x7f7fffffL, 0xff7fffffL, 0x7f800000L, 0xff800000L, 0x7fc00000L, 0x7f800001L,
                0xffc00001L, 0x3f000000L, 0x4b800000L};
    }

    private static long sign(SplittableRandom r) { return r.nextBoolean() ? Long.MIN_VALUE : 0L; }

    /** A mixture that reaches every branch of the library: edges, ordinary magnitudes, the safe-magnitude boundary, raw bits. */
    static long anyDouble(SplittableRandom random) {
        switch (random.nextInt(10)) {
            case 0: case 1: return edgeDouble(random);
            case 2: return boundaryDouble(random);
            case 3: return random.nextLong();
            default: return ordinaryDouble(random);
        }
    }

    private static long ordinaryDouble(SplittableRandom random) {
        double mantissa = 1.0 + random.nextDouble();
        return b((random.nextBoolean() ? mantissa : -mantissa) * Math.scalb(1.0, random.nextInt(81) - 40));
    }

    /** Magnitudes next to 2^-900 and 2^900, where safeMagnitude changes its answer. */
    private static long boundaryDouble(SplittableRandom random) {
        int exponent = random.nextBoolean() ? 895 + random.nextInt(7) : -903 + random.nextInt(7);
        return sign(random) | ((long) (exponent + 1023) << 52) | (random.nextLong() & 0xfffffffffffffL);
    }

    private static long edgeDouble(SplittableRandom random) {
        long sign = sign(random);
        switch (random.nextInt(8)) {
            case 0: return random.nextLong();
            case 1: {
                long[] fractions = {0, 1, 2, 0xfffffffffffffL, 0xffffffffffffeL, 1L << 51};
                return sign | ((long) random.nextInt(2048) << 52) | fractions[random.nextInt(fractions.length)];
            }
            case 2: return b((sign == 0 ? 1.0 : -1.0) * ((random.nextLong(1L << 52) >>> random.nextInt(52)) + 0.5));
            case 3: {
                double[] deltas = {-2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0};
                double centre = random.nextBoolean() ? 2147483648.0 : (random.nextBoolean() ? 1.0 : 0.0);
                return b((sign == 0 ? 1.0 : -1.0) * (centre + deltas[random.nextInt(deltas.length)]));
            }
            case 4: return sign | (random.nextLong() & 0xfffffffffffffL) >>> random.nextInt(52);
            case 5: return sign | (2047L << 52) | (random.nextBoolean() ? 0L : (random.nextLong() & 0xfffffffffffffL));
            case 6: return b((random.nextInt(4001) - 2000) / (double) (1 << random.nextInt(6)));
            default: return b((double) (random.nextLong() >> random.nextInt(64)));
        }
    }

    static long anyFloat(SplittableRandom random) {
        switch (random.nextInt(8)) {
            case 0: return random.nextInt() & 0xffffffffL;
            case 1: return (sign(random) >>> 32) | (random.nextInt() & 0x7fffffL);
            case 2: return (sign(random) >>> 32) | ((long) random.nextInt(256) << 23) | (random.nextInt() & 0x7fffffL);
            case 3: return (sign(random) >>> 32) | (0xffL << 23) | (random.nextBoolean() ? 0L : (random.nextInt() & 0x7fffffL));
            default: {
                float mantissa = 1.0f + random.nextFloat();
                return b((random.nextBoolean() ? mantissa : -mantissa) * (float) Math.scalb(1.0, random.nextInt(61) - 30));
            }
        }
    }

    private static long[] divisionPair(SplittableRandom random, Tables tables) {
        switch (random.nextInt(8)) {
            case 0: return new long[] {anyDouble(random), anyDouble(random)};
            case 1: { // an exact quotient and its neighbours
                double divisor = d(ordinaryDouble(random)), quotient = d(ordinaryDouble(random));
                double product = divisor * quotient;
                for (int steps = random.nextInt(3); steps > 0; steps--) product = random.nextBoolean() ? Math.nextUp(product) : Math.nextDown(product);
                return new long[] {b(product), b(divisor)};
            }
            case 2: { // operands whose ratio is below 2^-1075: the quotient underflows to a signed zero
                long a = sign(random) | ((long) (1023 - 899 + random.nextInt(20)) << 52) | (random.nextLong() & 0xfffffffffffffL);
                long divisor = sign(random) | ((long) (1023 + 880 + random.nextInt(19)) << 52) | (random.nextLong() & 0xfffffffffffffL);
                return new long[] {a, divisor};
            }
            case 3: { // the reverse: a quotient that overflows
                long a = sign(random) | ((long) (1023 + 880 + random.nextInt(19)) << 52) | (random.nextLong() & 0xfffffffffffffL);
                long divisor = sign(random) | ((long) (1023 - 899 + random.nextInt(20)) << 52) | (random.nextLong() & 0xfffffffffffffL);
                return new long[] {a, divisor};
            }
            case 4: return new long[] {boundaryDouble(random), boundaryDouble(random)};
            case 5: { long v = ordinaryDouble(random); return new long[] {v, random.nextBoolean() ? v : v ^ Long.MIN_VALUE}; }
            default: return new long[] {ordinaryDouble(random), ordinaryDouble(random)};
        }
    }

    private static long[] floatDivisionPair(SplittableRandom random, Tables tables) {
        switch (random.nextInt(4)) {
            case 0: return new long[] {anyFloat(random), anyFloat(random)};
            case 1: { // an exact quotient and its neighbours
                float divisor = f(anyFloat(random)), quotient = f(anyFloat(random));
                float product = divisor * quotient;
                for (int steps = random.nextInt(3); steps > 0; steps--) product = random.nextBoolean() ? Math.nextUp(product) : Math.nextDown(product);
                return new long[] {b(product), b(divisor)};
            }
            default: {
                long a = anyFloat(random);
                return new long[] {a, random.nextBoolean() ? a : a ^ 0x80000000L};
            }
        }
    }

    private static long[] remainderPair(SplittableRandom random, Tables tables) {
        switch (random.nextInt(4)) {
            case 0: return new long[] {anyFloat(random), anyFloat(random)};
            case 1: { // the End island use: a sum of two products of integers, modulo 13
                float scale = (float) random.nextInt(1 << 22) * 3439.0f + (float) random.nextInt(1 << 22) * 147.0f;
                return new long[] {b(random.nextBoolean() ? scale : -scale), b(13.0f)};
            }
            case 2: return new long[] {b((random.nextFloat() * 2.0f - 1.0f) * (float) Math.scalb(1.0, random.nextInt(40))), b(random.nextFloat() * 100.0f + 0.001f)};
            default: return new long[] {anyFloat(random), b(random.nextFloat() * 1000.0f)};
        }
    }

    private static long sqrtOperand(SplittableRandom random) {
        return switch (random.nextInt(5)) {
            case 0 -> anyDouble(random);
            case 1 -> boundaryDouble(random) & Long.MAX_VALUE;
            case 2 -> { double root = d(ordinaryDouble(random)); yield b(root * root); }
            default -> ordinaryDouble(random) & Long.MAX_VALUE;
        };
    }

    private static long[] lerpTriple(SplittableRandom random, Tables tables) {
        long t = switch (random.nextInt(4)) {
            case 0 -> b(random.nextDouble());
            case 1 -> b(random.nextDouble() * 3.0 - 1.0);
            case 2 -> anyDouble(random);
            default -> b(random.nextInt(5) / 4.0);
        };
        if (random.nextInt(3) != 0) {
            double base = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(30) - 10);
            double span = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(30) - 10);
            return new long[] {t, b(base), b(base + span)};
        }
        return new long[] {t, anyDouble(random), anyDouble(random)};
    }

    private static long[] floatTriple(SplittableRandom random, Tables tables) {
        long t = random.nextBoolean() ? b(random.nextFloat()) : anyFloat(random);
        if (random.nextBoolean()) {
            float base = (random.nextFloat() * 2.0f - 1.0f) * (float) Math.scalb(1.0, random.nextInt(20) - 6);
            float span = (random.nextFloat() * 2.0f - 1.0f) * (float) Math.scalb(1.0, random.nextInt(20) - 6);
            return new long[] {t, b(base), b(base + span)};
        }
        return new long[] {t, anyFloat(random), anyFloat(random)};
    }

    private static long[] inverseLerpTriple(SplittableRandom random, Tables tables) {
        if (random.nextInt(4) == 0) return new long[] {anyDouble(random), anyDouble(random), anyDouble(random)};
        double low = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(20) - 6);
        double span = (random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(20) - 6);
        double value = low + span * (random.nextDouble() * 1.6 - 0.3);
        long high = random.nextInt(16) == 0 ? b(low) : b(low + span);
        return new long[] {b(value), b(low), high};
    }

    private static long[] mapOperands(SplittableRandom random, Tables tables) {
        if (random.nextInt(5) == 0) return new long[] {anyDouble(random), anyDouble(random), anyDouble(random), anyDouble(random), anyDouble(random)};
        long[] range = inverseLerpTriple(random, tables);
        double low = (random.nextDouble() * 2.0 - 1.0) * 100.0;
        return new long[] {range[0], range[1], range[2], b(low), b(low + (random.nextDouble() * 2.0 - 1.0) * 100.0)};
    }

    private static long[] clampTriple(SplittableRandom random, Tables tables) {
        if (random.nextBoolean()) {
            double low = (random.nextDouble() * 2.0 - 1.0) * 10.0;
            return new long[] {b((random.nextDouble() * 2.0 - 1.0) * 20.0), b(low), b(low + random.nextDouble() * 10.0)};
        }
        return new long[] {anyDouble(random), anyDouble(random), anyDouble(random)};
    }

    private static long unitDouble(SplittableRandom random) {
        return random.nextInt(3) == 0 ? anyDouble(random) : b(random.nextDouble() * 1.4 - 0.2);
    }

    private static long wrapOperand(SplittableRandom random) {
        return random.nextInt(3) == 0 ? anyDouble(random) : b((random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(40)));
    }

    private static long cornerOffset(SplittableRandom random) {
        return random.nextInt(5) == 0 ? anyDouble(random) : b(random.nextDouble() * 2.0 - 1.0);
    }

    private static long[] divisionInts(SplittableRandom random, Tables tables) {
        int divisor;
        do { divisor = random.nextBoolean() ? random.nextInt() >> random.nextInt(31) : random.nextInt(-20, 21); } while (divisor == 0);
        int value = random.nextBoolean() ? random.nextInt() : random.nextInt(-1000, 1001);
        if (value == Integer.MIN_VALUE && divisor == -1) value++;
        return new long[] {value, divisor};
    }

    private static long[] nextIntOperands(SplittableRandom random, Tables tables) {
        boolean legacy = random.nextBoolean();
        int bound = random.nextInt(3) == 0 ? 1 << random.nextInt(1, 30) : random.nextInt(1, 100_000);
        return new long[] {random.nextLong() & 0xffffffffffffL, random.nextLong(), random.nextLong(), legacy ? 1 : 0,
                random.nextInt(-30_000_000, 30_000_000), random.nextInt(-64, 320), random.nextInt(-30_000_000, 30_000_000), bound};
    }

    private static long offset(SplittableRandom random) { return b(random.nextDouble() * 256.0); }

    private static long yScale(SplittableRandom random) {
        return random.nextInt(3) == 0 ? b(0.0) : (random.nextInt(8) == 0 ? anyDouble(random) : b(random.nextDouble() * 4.0 + 0.001));
    }

    private static long yMax(SplittableRandom random) {
        return random.nextInt(8) == 0 ? anyDouble(random) : b(random.nextDouble() * 6.0 - 2.0);
    }

    /** What reaches a Perlin level after wrapping: any value in about +-2^24. */
    private static long noiseCoordinate(SplittableRandom random) {
        return switch (random.nextInt(8)) {
            case 0 -> anyDouble(random);
            case 1, 2 -> b(random.nextDouble() * 33554432.0 - 16777216.0);
            default -> b((random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(12)));
        };
    }

    /** A world coordinate times a noise scale: block positions, fractional quart positions, big values. */
    private static long worldCoordinate(SplittableRandom random) {
        return switch (random.nextInt(6)) {
            case 0 -> b((double) random.nextInt(-30_000_000, 30_000_000));
            case 1 -> anyDouble(random);
            case 2 -> b(random.nextInt(-2000, 2000) / 4.0);
            default -> b((random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(25)));
        };
    }

    private static long blockCoordinate(SplittableRandom random) {
        return random.nextBoolean() ? random.nextInt(-2000, 2000) : random.nextInt(-30_000_000, 30_000_000);
    }

    private static long simplexCoordinate(SplittableRandom random) {
        return switch (random.nextInt(6)) {
            case 0 -> anyDouble(random);
            case 1 -> b((double) random.nextInt(-200, 200));
            default -> b((random.nextDouble() * 2.0 - 1.0) * Math.scalb(1.0, random.nextInt(10)));
        };
    }

    private static long endCoordinate(SplittableRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextInt(-64, 64);
            case 1 -> random.nextInt(-4000, 4000);
            case 2 -> random.nextInt(-800_000, 800_000);
            default -> random.nextInt(-30_000_000, 30_000_000);
        };
    }

    private static long splineCoordinate(SplittableRandom random) {
        return switch (random.nextInt(8)) {
            case 0 -> anyFloat(random);
            case 6, 7 -> b((random.nextFloat() * 2.0f - 1.0f) * 3.0e38f);
            case 1 -> b(random.nextInt(13) / 4.0f - 1.5f);
            default -> b(random.nextFloat() * 4.0f - 2.0f);
        };
    }
}
