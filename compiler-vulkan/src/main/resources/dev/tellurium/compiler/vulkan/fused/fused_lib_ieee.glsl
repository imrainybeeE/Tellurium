// SPDX-License-Identifier: MIT
// WorldgenNext fused NOISE library, integer-carrier port of fused_lib.glsl.  Same functions in the same
// order with the same names; every binary64 value is a uvec2 (x = low word) and every binary32 value is a
// uint holding its raw bits.  Each arithmetic operation is a call into the exact integer helpers of
// IntegerIeeeEmitter (wg_) and FusedIeeeHelpers (wgf_), so each result has the bits Java produces.  The
// functions without arithmetic on those values are copied from fused_lib.glsl byte for byte
// (FusedIeeeLibraryDriftTest).  No floating type, literal or builtin may appear here.
// Every constant states its decimal value in the trailing comment (FusedIeeeLibraryConstantsTest).

const uvec2 D64_ZERO = uvec2(0x00000000u, 0x00000000u); // 0.0
const uvec2 D64_HALF = uvec2(0x00000000u, 0x3fe00000u); // 0.5
const uvec2 D64_ONE = uvec2(0x00000000u, 0x3ff00000u); // 1.0
const uvec2 D64_TWO = uvec2(0x00000000u, 0x40000000u); // 2.0
const uvec2 D64_SIX = uvec2(0x00000000u, 0x40180000u); // 6.0
const uvec2 D64_EIGHT = uvec2(0x00000000u, 0x40200000u); // 8.0
const uvec2 D64_TEN = uvec2(0x00000000u, 0x40240000u); // 10.0
const uvec2 D64_FIFTEEN = uvec2(0x00000000u, 0x402e0000u); // 15.0
const uvec2 D64_SEVENTY = uvec2(0x00000000u, 0x40518000u); // 70.0
const uvec2 D64_128 = uvec2(0x00000000u, 0x40600000u); // 128.0
const uvec2 D64_WRAP = uvec2(0x00000000u, 0x41800000u); // 33554432.0
const uvec2 D64_SMEAR_EPSILON = uvec2(0xa0000000u, 0x3e7ad7f2u); // 1.0000000116860974E-7
const uvec2 D64_NOISE_FACTOR = uvec2(0x6b8128feu, 0x3ff04a3fu); // 1.0181268882175227
const uvec2 D64_BLEND_SCALE = uvec2(0xc6a7ef9eu, 0x4085634bu); // 684.412
const uvec2 D64_INV_512 = uvec2(0x00000000u, 0x3f600000u); // 0.001953125
const uvec2 D64_INV_128 = uvec2(0x00000000u, 0x3f800000u); // 0.0078125
const uvec2 D64_ISLAND_LIMIT = uvec2(0xc0000000u, 0xbfecccccu); // -0.8999999761581421

const uint F32_ZERO = 0x00000000u; // 0.0
const uint F32_ONE = 0x3f800000u; // 1.0
const uint F32_EIGHT = 0x41000000u; // 8.0
const uint F32_NINE = 0x41100000u; // 9.0
const uint F32_THIRTEEN = 0x41500000u; // 13.0
const uint F32_EIGHTY = 0x42a00000u; // 80.0
const uint F32_HUNDRED = 0x42c80000u; // 100.0
const uint F32_NEG_HUNDRED = 0xc2c80000u; // -100.0
const uint F32_147 = 0x43130000u; // 147.0
const uint F32_3439 = 0x4556f000u; // 3439.0
const uint F32_RNG_SCALE = 0x33800000u; // 5.9604645E-8

uint gChunk;      // batch slot of the chunk being evaluated
int gBaseX;       // chunk min block X
int gBaseZ;       // chunk min block Z
int gFirstQX;     // NoiseChunk FlatCache firstNoiseX (quart)
int gFirstQZ;

bool gBailed = false;  // set by any bail in this invocation
bool gShared = false;  // true in kernels whose work is shared by several chunks
void bail(uint code) { gBailed = true; if (!gShared) atomicOr(flags[gChunk], code); }

const uint BAIL_DIV = 1u, BAIL_SQRT = 2u, BAIL_CAST = 4u, BAIL_NONFINITE = 8u, BAIL_RANGE = 16u, BAIL_FLOAT = 32u;

int64_t dbits(uvec2 d) { return int64_t(packUint2x32(d)); }
uvec2 bitsd(int64_t b) { return unpackUint2x32(uint64_t(b)); }

// Java (int) narrowing of a binary64 value: NaN -> 0, saturating, truncation toward zero.  The helper has
// exactly these semantics for every input, so the guards of the native version are not repeated.
int jd2i(uvec2 d) { return wgf_fp64_to_i32_java(d); }
// Mth.floor(binary64)
int mfloor(uvec2 d) {
    int i = jd2i(d);
    return wg_fp64_less(d, wg_fp64_from_int(i)) ? i - 1 : i;
}
int jrem(int a, int b) { return a - (a / b) * b; }
int jfloorDiv(int a, int b) {
    int q = a / b;
    if ((jrem(a, b) != 0) && ((a < 0) != (b < 0))) q = q - 1;
    return q;
}
int jfloorMod(int a, int b) { return a - jfloorDiv(a, b) * b; }

// A NaN operand passes through unchanged, as in the native version (the wg_ min and max helpers return a quiet NaN).
uvec2 jmin(uvec2 a, uvec2 b) {
    if (wg_fp64_nan(a)) return a;
    if (wg_fp64_nan(b)) return b;
    if (wg_fp64_equal(a, D64_ZERO) && wg_fp64_equal(b, D64_ZERO)) return dbits(a) < 0l ? a : b;
    return wg_fp64_less_equal(a, b) ? a : b;
}
uvec2 jmax(uvec2 a, uvec2 b) {
    if (wg_fp64_nan(a)) return a;
    if (wg_fp64_nan(b)) return b;
    if (wg_fp64_equal(a, D64_ZERO) && wg_fp64_equal(b, D64_ZERO)) return dbits(a) < 0l ? b : a;
    return wg_fp64_less_equal(b, a) ? a : b;
}
uint jminf(uint a, uint b) {
    if (wg_fp32_nan(a)) return a;
    if (wg_fp32_nan(b)) return b;
    if (wg_fp32_equal(a, F32_ZERO) && wg_fp32_equal(b, F32_ZERO)) return (a >> 31) != 0u ? a : b;
    return wg_fp32_less_equal(a, b) ? a : b;
}
uint jmaxf(uint a, uint b) {
    if (wg_fp32_nan(a)) return a;
    if (wg_fp32_nan(b)) return b;
    if (wg_fp32_equal(a, F32_ZERO) && wg_fp32_equal(b, F32_ZERO)) return (a >> 31) != 0u ? b : a;
    return wg_fp32_less_equal(b, a) ? a : b;
}

uvec2 nextUpPos(uvec2 q) { return bitsd(dbits(q) + 1l); }   // q > 0 finite
uvec2 nextDownPos(uvec2 q) { return bitsd(dbits(q) - 1l); } // q > 0 finite, not min subnormal

// Zero, or a biased exponent field E with 122 < E < 1922, which is the native test -900 < frexp exponent < 900
// (the native exponent is E - 1022 for a normal number).  Subnormal, infinite and NaN values have E outside
// that range, so they are not safe: the native version is false for a subnormal and undefined for the others.
bool safeMagnitude(uvec2 v) {
    if (wg_fp64_zero(v)) return true;
    uint e = wg_fp64_exp(v);
    return e > 122u && e < 1922u;
}

// Correctly rounded IEEE division (round to nearest even) for the guarded domain.  Every guard and bail is
// the native one; the quotient wg_fp64_div returns is the correctly rounded result that the native
// correction loop establishes.
uvec2 jdiv(uvec2 a, uvec2 b) {
    if (wg_fp64_nan(a) || wg_fp64_nan(b) || wg_fp64_inf(a) || wg_fp64_inf(b) || wg_fp64_equal(b, D64_ZERO) || !safeMagnitude(a) || !safeMagnitude(b)) {
        bail(BAIL_DIV);
        return wg_fp64_div(a, b);
    }
    if (wg_fp64_equal(a, D64_ZERO)) return wg_fp64_div(a, b);
    uvec2 q = wg_fp64_div(a, b);
    if (!safeMagnitude(q)) { bail(BAIL_DIV); return q; }
    return q;
}

// Correctly rounded square root for the guarded domain: the guards are the native ones and wg_fp64_sqrt is
// the correctly rounded root that the native correction loop establishes.
uvec2 jsqrt(uvec2 x) {
    if (wg_fp64_nan(x) || wg_fp64_inf(x) || wg_fp64_less(x, D64_ZERO) || !safeMagnitude(x)) { bail(BAIL_SQRT); return wg_fp64_sqrt(x); }
    if (wg_fp64_equal(x, D64_ZERO)) return x;
    return wg_fp64_sqrt(x);
}

// The quotient of the bail path is formed in binary64 and narrowed once.  Rounding a binary32 quotient
// through binary64 first never changes it (53 >= 2 * 24 + 2), so the bits are those of a binary32 division.
uint jdivf(uint a, uint b) {
    if (wg_fp32_equal(b, F32_ZERO) || wg_fp32_nan(a) || wg_fp32_nan(b) || wg_fp32_inf(a) || wg_fp32_inf(b)) {
        bail(BAIL_FLOAT);
        return wg_fp64_to_fp32(wg_fp64_div(wg_fp64_from_fp32(a), wg_fp64_from_fp32(b)));
    }
    return wg_fp64_to_fp32(jdiv(wg_fp64_from_fp32(a), wg_fp64_from_fp32(b)));
}
uint jsqrtf(uint x) { return wg_fp64_to_fp32(jsqrt(wg_fp64_from_fp32(x))); }

// Java binary32 remainder (fmod, exact) for finite a and b > 0 used by End islands.
uint jremf(uint a, uint b) {
    if (wg_fp32_nan(a) || wg_fp32_inf(a) || !wg_fp32_less(F32_ZERO, b)) { bail(BAIL_FLOAT); return F32_ZERO; }
    uvec2 da = wg_fp64_abs(wg_fp64_from_fp32(a));
    uvec2 db = wg_fp64_from_fp32(b);
    uvec2 q = wg_fp64_floor(wg_fp64_div(da, db));
    uvec2 r = wg_fp64_sub(da, wg_fp64_mul(q, db));
    if (wg_fp64_less(r, D64_ZERO)) r = wg_fp64_add(r, db);
    if (wg_fp64_less_equal(db, r)) r = wg_fp64_sub(r, db);
    uint res = wg_fp64_to_fp32(r);
    return wg_fp32_less(a, F32_ZERO) ? wg_fp32_negate(res) : res;
}

uvec2 lerpd(uvec2 t, uvec2 a, uvec2 b) { return wg_fp64_lerp(a, b, t); }
uint lerpf(uint t, uint a, uint b) { return wg_fp32_add(a, wg_fp32_mul(t, wg_fp32_sub(b, a))); }
uvec2 clampedLerp(uvec2 a, uvec2 b, uvec2 t) {
    if (wg_fp64_less(t, D64_ZERO)) return a;
    if (wg_fp64_less(D64_ONE, t)) return b;
    return lerpd(t, a, b);
}
uvec2 inverseLerp(uvec2 v, uvec2 a, uvec2 b) { return jdiv(wg_fp64_sub(v, a), wg_fp64_sub(b, a)); }
uvec2 clampedMap(uvec2 v, uvec2 a, uvec2 b, uvec2 c, uvec2 d) { return clampedLerp(c, d, inverseLerp(v, a, b)); }
uvec2 mapd(uvec2 v, uvec2 a, uvec2 b, uvec2 c, uvec2 d) { return lerpd(inverseLerp(v, a, b), c, d); }
uvec2 mclamp(uvec2 v, uvec2 lo, uvec2 hi) { return wg_fp64_less(v, lo) ? lo : jmin(v, hi); }

// ---------------------------------------------------------------- noise ---
// dtab Perlin record at index P: [levelCount, then per level 7 values:
//   amplitude, inputFactor, valueFactor, xOffset, yOffset, zOffset, permBase (-1 = null level)]
const int PERLIN_STRIDE = 7;

uvec2 smoothstepd(uvec2 v) {
    return wg_fp64_mul(wg_fp64_mul(wg_fp64_mul(v, v), v),
            wg_fp64_add(wg_fp64_mul(v, wg_fp64_sub(wg_fp64_mul(v, D64_SIX), D64_FIFTEEN)), D64_TEN));
}
uvec2 wrapd(uvec2 v) {
    return wg_fp64_sub(v, wg_fp64_mul(wg_fp64_floor(wg_fp64_add(wg_fp64_div(v, D64_WRAP), D64_HALF)), D64_WRAP));
}

int permAt(int base, int index) { return int(perm[base + (index & 255)]); }

uvec2 gradDot(int hash, uvec2 x, uvec2 y, uvec2 z) {
    switch (hash & 15) {
        case 0: return wg_fp64_add(x, y);
        case 1: return wg_fp64_add(wg_fp64_negate(x), y);
        case 2: return wg_fp64_sub(x, y);
        case 3: return wg_fp64_sub(wg_fp64_negate(x), y);
        case 4: return wg_fp64_add(x, z);
        case 5: return wg_fp64_add(wg_fp64_negate(x), z);
        case 6: return wg_fp64_sub(x, z);
        case 7: return wg_fp64_sub(wg_fp64_negate(x), z);
        case 8: return wg_fp64_add(y, z);
        case 9: return wg_fp64_add(wg_fp64_negate(y), z);
        case 10: return wg_fp64_sub(y, z);
        case 11: return wg_fp64_sub(wg_fp64_negate(y), z);
        case 12: return wg_fp64_add(x, y);
        case 13: return wg_fp64_add(wg_fp64_negate(y), z);
        case 14: return wg_fp64_add(wg_fp64_negate(x), y);
        default: return wg_fp64_sub(wg_fp64_negate(y), z);
    }
}

uvec2 improvedNoise(int permBase, uvec2 xo, uvec2 yo, uvec2 zo,
                    uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {
    uvec2 sx = wg_fp64_add(x, xo), sy = wg_fp64_add(y, yo), sz = wg_fp64_add(z, zo);
    int x0 = mfloor(sx), y0 = mfloor(sy), z0 = mfloor(sz);
    uvec2 fx = wg_fp64_sub(sx, wg_fp64_from_int(x0)), fy = wg_fp64_sub(sy, wg_fp64_from_int(y0)), fz = wg_fp64_sub(sz, wg_fp64_from_int(z0));
    uvec2 smear = D64_ZERO;
    if (!wg_fp64_equal(yScale, D64_ZERO)) {
        uvec2 clamped = (wg_fp64_less_equal(D64_ZERO, yMax) && wg_fp64_less(yMax, fy)) ? yMax : fy;
        smear = wg_fp64_mul(wg_fp64_floor(wg_fp64_add(jdiv(clamped, yScale), D64_SMEAR_EPSILON)), yScale);
    }
    uvec2 afy = wg_fp64_sub(fy, smear);
    int xh = permAt(permBase, x0), xhn = permAt(permBase, x0 + 1);
    int xy00 = permAt(permBase, xh + y0), xy01 = permAt(permBase, xh + y0 + 1);
    int xy10 = permAt(permBase, xhn + y0), xy11 = permAt(permBase, xhn + y0 + 1);
    uvec2 v000 = gradDot(permAt(permBase, xy00 + z0), fx, afy, fz);
    uvec2 v100 = gradDot(permAt(permBase, xy10 + z0), wg_fp64_sub(fx, D64_ONE), afy, fz);
    uvec2 v010 = gradDot(permAt(permBase, xy01 + z0), fx, wg_fp64_sub(afy, D64_ONE), fz);
    uvec2 v110 = gradDot(permAt(permBase, xy11 + z0), wg_fp64_sub(fx, D64_ONE), wg_fp64_sub(afy, D64_ONE), fz);
    uvec2 v001 = gradDot(permAt(permBase, xy00 + z0 + 1), fx, afy, wg_fp64_sub(fz, D64_ONE));
    uvec2 v101 = gradDot(permAt(permBase, xy10 + z0 + 1), wg_fp64_sub(fx, D64_ONE), afy, wg_fp64_sub(fz, D64_ONE));
    uvec2 v011 = gradDot(permAt(permBase, xy01 + z0 + 1), fx, wg_fp64_sub(afy, D64_ONE), wg_fp64_sub(fz, D64_ONE));
    uvec2 v111 = gradDot(permAt(permBase, xy11 + z0 + 1), wg_fp64_sub(fx, D64_ONE), wg_fp64_sub(afy, D64_ONE), wg_fp64_sub(fz, D64_ONE));
    uvec2 tx = smoothstepd(fx), ty = smoothstepd(fy), tz = smoothstepd(fz);
    uvec2 x00 = lerpd(tx, v000, v100), x10 = lerpd(tx, v010, v110);
    uvec2 x01 = lerpd(tx, v001, v101), x11 = lerpd(tx, v011, v111);
    return lerpd(tz, lerpd(ty, x00, x10), lerpd(ty, x01, x11));
}

uvec2 perlinValue(int P, uvec2 x, uvec2 y, uvec2 z) {
    int n = wgf_fp64_to_i32_java(dtab[P]);
    uvec2 result = D64_ZERO;
    for (int i = 0; i < n; i++) {
        int L = P + 1 + i * PERLIN_STRIDE;
        int permBase = wgf_fp64_to_i32_java(dtab[L + 6]);
        if (permBase >= 0) {
            uvec2 f = dtab[L + 1];
            uvec2 noise = improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5],
                    wrapd(wg_fp64_mul(x, f)), wrapd(wg_fp64_mul(y, f)), wrapd(wg_fp64_mul(z, f)), D64_ZERO, D64_ZERO);
            result = wg_fp64_add(result, wg_fp64_mul(wg_fp64_mul(dtab[L], noise), dtab[L + 2]));
        }
    }
    return result;
}

// NormalNoise record at index N: [valueFactor, firstPerlinIndex, secondPerlinIndex]
uvec2 normalNoise(int N, uvec2 x, uvec2 y, uvec2 z) {
    uvec2 first = perlinValue(wgf_fp64_to_i32_java(dtab[N + 1]), x, y, z);
    uvec2 factor = D64_NOISE_FACTOR;
    uvec2 second = perlinValue(wgf_fp64_to_i32_java(dtab[N + 2]), wg_fp64_mul(x, factor), wg_fp64_mul(y, factor), wg_fp64_mul(z, factor));
    return wg_fp64_mul(wg_fp64_add(first, second), dtab[N]);
}

// One BlendedNoise legacy octave: levels indexed from the end of the octave array.
uvec2 legacyLevel(int P, int octave, uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {
    int n = wgf_fp64_to_i32_java(dtab[P]);
    int levelIndex = n - 1 - octave;
    if (levelIndex < 0 || levelIndex >= n) return D64_ZERO;
    int L = P + 1 + levelIndex * PERLIN_STRIDE;
    int permBase = wgf_fp64_to_i32_java(dtab[L + 6]);
    if (permBase < 0) return D64_ZERO;
    return wg_fp64_mul(dtab[L], improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5], x, y, z, yScale, yMax));
}

// BlendedNoise record at index B: [xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier, minP, maxP, mainP]
uvec2 blendedNoise(int B, int bx, int by, int bz) {
    uvec2 xzMultiplier = wg_fp64_mul(D64_BLEND_SCALE, dtab[B]);
    uvec2 yMultiplier = wg_fp64_mul(D64_BLEND_SCALE, dtab[B + 1]);
    uvec2 xValue = wg_fp64_mul(wg_fp64_from_int(bx), xzMultiplier);
    uvec2 yValue = wg_fp64_mul(wg_fp64_from_int(by), yMultiplier);
    uvec2 zValue = wg_fp64_mul(wg_fp64_from_int(bz), xzMultiplier);
    uvec2 mainX = jdiv(xValue, dtab[B + 2]);
    uvec2 mainY = jdiv(yValue, dtab[B + 3]);
    uvec2 mainZ = jdiv(zValue, dtab[B + 2]);
    uvec2 smearScale = jdiv(wg_fp64_mul(yMultiplier, dtab[B + 4]), dtab[B + 3]);
    int minP = wgf_fp64_to_i32_java(dtab[B + 5]), maxP = wgf_fp64_to_i32_java(dtab[B + 6]), mainP = wgf_fp64_to_i32_java(dtab[B + 7]);
    uvec2 main = D64_ZERO;
    uvec2 mainScale = D64_ONE;
    uvec2 inverse = D64_ONE;
    for (int i = 0; i < 8; i++) {
        main = wg_fp64_add(main, wg_fp64_mul(legacyLevel(mainP, i, wrapd(wg_fp64_mul(mainX, mainScale)), wrapd(wg_fp64_mul(mainY, mainScale)), wrapd(wg_fp64_mul(mainZ, mainScale)),
                wg_fp64_mul(smearScale, mainScale), wg_fp64_mul(mainY, mainScale)), inverse));
        mainScale = wg_fp64_mul(mainScale, D64_HALF);
        inverse = wg_fp64_mul(inverse, D64_TWO);
    }
    uvec2 blend = wg_fp64_mul(wg_fp64_add(jdiv(main, D64_TEN), D64_ONE), D64_HALF);
    bool skipMin = wg_fp64_less_equal(D64_ONE, blend);
    bool skipMax = wg_fp64_less_equal(blend, D64_ZERO);
    uvec2 minV = D64_ZERO, maxV = D64_ZERO;
    uvec2 limitScale = D64_ONE;
    uvec2 limitInverse = D64_ONE;
    uvec2 limitSmear = wg_fp64_mul(yMultiplier, dtab[B + 4]);
    for (int i = 0; i < 16; i++) {
        uvec2 wx = wrapd(wg_fp64_mul(xValue, limitScale)), wy = wrapd(wg_fp64_mul(yValue, limitScale)), wz = wrapd(wg_fp64_mul(zValue, limitScale));
        if (!skipMin) minV = wg_fp64_add(minV, wg_fp64_mul(legacyLevel(minP, i, wx, wy, wz, wg_fp64_mul(limitSmear, limitScale), wg_fp64_mul(yValue, limitScale)), limitInverse));
        if (!skipMax) maxV = wg_fp64_add(maxV, wg_fp64_mul(legacyLevel(maxP, i, wx, wy, wz, wg_fp64_mul(limitSmear, limitScale), wg_fp64_mul(yValue, limitScale)), limitInverse));
        limitScale = wg_fp64_mul(limitScale, D64_HALF);
        limitInverse = wg_fp64_mul(limitInverse, D64_TWO);
    }
    uvec2 lower = wg_fp64_mul(minV, D64_INV_512);
    uvec2 upper = wg_fp64_mul(maxV, D64_INV_512);
    uvec2 clampedBlend = jmax(D64_ZERO, jmin(D64_ONE, blend));
    return wg_fp64_mul(wg_fp64_add(lower, wg_fp64_mul(clampedBlend, wg_fp64_sub(upper, lower))), D64_INV_128);
}

// ------------------------------------------------------------ End island ---
// dtab record E: [permBase]
const uvec2 SIMPLEX_F2 = uvec2(0xd0b09954u, 0x3fd76cf5u); // 0.3660254037844386
const uvec2 SIMPLEX_G2 = uvec2(0x74df99c8u, 0x3fcb0cb1u); // 0.21132486540518713
const uvec2 SIMPLEX_2G2 = uvec2(0x74df99c8u, 0x3fdb0cb1u); // 0.42264973081037427

uvec2 simplexCorner(int hash, uvec2 x, uvec2 y) {
    uvec2 att = wg_fp64_sub(wg_fp64_sub(D64_HALF, wg_fp64_mul(x, x)), wg_fp64_mul(y, y));
    if (wg_fp64_less(att, D64_ZERO)) return D64_ZERO;
    att = wg_fp64_mul(att, att);
    int h = hash % 12;
    uvec2 g;
    switch (h) {
        case 0: g = wg_fp64_add(x, y); break;
        case 1: g = wg_fp64_add(wg_fp64_negate(x), y); break;
        case 2: g = wg_fp64_sub(x, y); break;
        case 3: g = wg_fp64_sub(wg_fp64_negate(x), y); break;
        case 4: g = x; break;
        case 5: g = wg_fp64_negate(x); break;
        case 6: g = x; break;
        case 7: g = wg_fp64_negate(x); break;
        case 8: g = y; break;
        case 9: g = wg_fp64_negate(y); break;
        case 10: g = y; break;
        default: g = wg_fp64_negate(y); break;
    }
    return wg_fp64_mul(wg_fp64_mul(att, att), g);
}

uvec2 simplex2D(int permBase, uvec2 x, uvec2 y) {
    uvec2 skew = wg_fp64_mul(wg_fp64_add(x, y), SIMPLEX_F2);
    int lx = mfloor(wg_fp64_add(x, skew));
    int ly = mfloor(wg_fp64_add(y, skew));
    uvec2 unskew = wg_fp64_mul(wg_fp64_from_int(lx + ly), SIMPLEX_G2);
    uvec2 x0 = wg_fp64_sub(x, wg_fp64_sub(wg_fp64_from_int(lx), unskew));
    uvec2 y0 = wg_fp64_sub(y, wg_fp64_sub(wg_fp64_from_int(ly), unskew));
    int ox = wg_fp64_less(y0, x0) ? 1 : 0;
    int oy = wg_fp64_less(y0, x0) ? 0 : 1;
    uvec2 x1 = wg_fp64_add(wg_fp64_sub(x0, wg_fp64_from_int(ox)), SIMPLEX_G2);
    uvec2 y1 = wg_fp64_add(wg_fp64_sub(y0, wg_fp64_from_int(oy)), SIMPLEX_G2);
    uvec2 x2 = wg_fp64_add(wg_fp64_sub(x0, D64_ONE), SIMPLEX_2G2);
    uvec2 y2 = wg_fp64_add(wg_fp64_sub(y0, D64_ONE), SIMPLEX_2G2);
    int h0 = permAt(permBase, lx + permAt(permBase, ly)) % 12;
    int h1 = permAt(permBase, lx + ox + permAt(permBase, ly + oy)) % 12;
    int h2 = permAt(permBase, lx + 1 + permAt(permBase, ly + 1)) % 12;
    uvec2 n0 = simplexCorner(h0, x0, y0);
    uvec2 n1 = simplexCorner(h1, x1, y1);
    uvec2 n2 = simplexCorner(h2, x2, y2);
    return wg_fp64_mul(D64_SEVENTY, wg_fp64_add(wg_fp64_add(n0, n1), n2));
}

uvec2 endIsland(int E, int bx, int bz) {
    int permBase = wgf_fp64_to_i32_java(dtab[E]);
    int x = bx / 8;
    int z = bz / 8;
    int gridX = x / 2;
    int gridZ = z / 2;
    int remX = jrem(x, 2);
    int remZ = jrem(z, 2);
    // Java's int arithmetic wraps here far from the origin (vanilla's rings beyond about 370,000 blocks), but
    // Metal may assume signed arithmetic does not overflow and drop the negative-argument check below, so the
    // sum is formed in unsigned arithmetic, which wraps by definition, and reinterpreted.
    uint height = wg_fp32_sub(F32_HUNDRED, wg_fp32_mul(jsqrtf(wg_fp32_from_int(int(uint(x) * uint(x) + uint(z) * uint(z)))), F32_EIGHT));
    height = jmaxf(F32_NEG_HUNDRED, jminf(F32_EIGHTY, height));
    for (int i = -12; i <= 12; i++) {
        for (int j = -12; j <= 12; j++) {
            int64_t ix = int64_t(gridX) + int64_t(i);
            int64_t iz = int64_t(gridZ) + int64_t(j);
            if (ix * ix + iz * iz > 4096l && wg_fp64_less(simplex2D(permBase, wgf_fp64_from_i64(unpackUint2x32(uint64_t(ix))), wgf_fp64_from_i64(unpackUint2x32(uint64_t(iz)))), D64_ISLAND_LIMIT)) {
                uint scale = wg_fp32_add(wg_fp32_mul(wgf_fp32_from_i64(unpackUint2x32(uint64_t(abs(ix)))), F32_3439), wg_fp32_mul(wgf_fp32_from_i64(unpackUint2x32(uint64_t(abs(iz)))), F32_147));
                scale = jremf(scale, F32_THIRTEEN);
                scale = wg_fp32_add(scale, F32_NINE);
                uint lx = wg_fp32_from_int(remX - i * 2);
                uint lz = wg_fp32_from_int(remZ - j * 2);
                uint h = wg_fp32_sub(F32_HUNDRED, wg_fp32_mul(jsqrtf(wg_fp32_add(wg_fp32_mul(lx, lx), wg_fp32_mul(lz, lz))), scale));
                h = jmaxf(F32_NEG_HUNDRED, jminf(F32_EIGHTY, h));
                height = jmaxf(height, h);
            }
        }
    }
    return jdiv(wg_fp64_sub(wg_fp64_from_fp32(height), D64_EIGHT), D64_128);
}

// ------------------------------------------------------------- random -----
int64_t mixStafford(int64_t v) {
    v = (v ^ int64_t(uint64_t(v) >> 30)) * -4658895280553007687l;  // 0xBF58476D1CE4E5B9
    v = (v ^ int64_t(uint64_t(v) >> 27)) * -7723592293110705685l;  // 0x94D049BB133111EB
    return v ^ int64_t(uint64_t(v) >> 31);
}
int64_t rotl64(int64_t v, int s) { return int64_t((uint64_t(v) << s) | (uint64_t(v) >> (64 - s))); }

// Mth.getSeed(x, y, z)
int64_t positionSeed(int x, int y, int z) {
    int64_t v = int64_t(x * 3129871) ^ int64_t(z) * 116129781l ^ int64_t(y);
    v = v * v * 42317861l + v * 11l;
    return v >> 16;
}

struct Rng { int64_t lo; int64_t hi; int64_t legacy; bool isLegacy; };

Rng rngAt(bool legacyAlgorithm, int64_t seed, int64_t seedLo, int64_t seedHi, int x, int y, int z) {
    Rng r;
    int64_t ps = positionSeed(x, y, z);
    r.isLegacy = legacyAlgorithm;
    if (legacyAlgorithm) {
        r.legacy = ((ps ^ seed) ^ 25214903917l) & 281474976710655l;
        r.lo = 0l; r.hi = 0l;
    } else {
        int64_t lo = ps ^ seedLo;
        int64_t hi = seedHi;
        if ((lo | hi) == 0l) { lo = -7046029254386353131l; hi = 7640891576956012809l; }
        r.lo = lo; r.hi = hi; r.legacy = 0l;
    }
    return r;
}
int64_t xoroNext(inout Rng r) {
    int64_t result = rotl64(r.lo + r.hi, 17) + r.lo;
    int64_t nh = r.hi ^ r.lo;
    r.lo = rotl64(r.lo, 49) ^ nh ^ (nh << 21);
    r.hi = rotl64(nh, 28);
    return result;
}
int legacyBits(inout Rng r, int bits) {
    r.legacy = (r.legacy * 25214903917l + 11l) & 281474976710655l;
    return int(r.legacy >> (48 - bits));
}
int rngNextInt(inout Rng r, int bound) {
    if (r.isLegacy) {
        if ((bound & -bound) == bound) return int((int64_t(bound) * int64_t(legacyBits(r, 31))) >> 31);
        int bits, value;
        do { bits = legacyBits(r, 31); value = bits % bound; } while (bits - value + (bound - 1) < 0);
        return value;
    }
    int64_t bits = xoroNext(r) & 4294967295l;
    int64_t product = bits * int64_t(bound);
    int64_t low = product & 4294967295l;
    if (low < int64_t(bound)) {
        int64_t threshold = int64_t(uint(-bound) % uint(bound));
        while (low < threshold) {
            bits = xoroNext(r) & 4294967295l;
            product = bits * int64_t(bound);
            low = product & 4294967295l;
        }
    }
    return int(product >> 32);
}
uint rngNextFloat(inout Rng r) {
    if (r.isLegacy) return wg_fp32_mul(wg_fp32_from_int(legacyBits(r, 24)), F32_RNG_SCALE);
    return wg_fp32_mul(wg_fp32_from_int(int(uint64_t(xoroNext(r)) >> 40)), F32_RNG_SCALE);
}

// ------------------------------------------------------------- splines ---
// Table-driven CubicSpline.Multipoint.apply (binary32 arithmetic, same operation
// order).  Record at perm[N]: n, coordinate index, then per point
// [locationBits, derivativeBits, kind (0 constant, 1 child record), payload].
const int SPLINE_STACK = 16;
uint sLoc(int N, int i) { return perm[N + 2 + i * 4]; }
uint sDer(int N, int i) { return perm[N + 3 + i * 4]; }
uint sKind(int N, int i) { return perm[N + 4 + i * 4]; }
uint sPayload(int N, int i) { return perm[N + 5 + i * 4]; }

uint splineEval(int root, uint cs[SPLINE_COORDS]) {
    int sN[SPLINE_STACK];
    int sState[SPLINE_STACK];
    int sIv[SPLINE_STACK];
    uint sV0[SPLINE_STACK];
    uint sV1[SPLINE_STACK];
    int sp = 0;
    sN[0] = root;
    sState[0] = 0;
    uint result = F32_ZERO;
    for (int guard = 0; guard < 4096; guard++) {
        int N = sN[sp];
        int n = int(perm[N]);
        uint c = cs[int(perm[N + 1])];
        int st = sState[sp];
        bool ready = false;
        if (st == 0) {
            // Mth.binarySearch(0, n, i -> c < locations[i]) - 1, probe for probe:
            // datapack splines are not required to be sorted.
            int lo = 0;
            int remaining = n;
            while (remaining > 0) {
                int step = remaining / 2;
                int k = lo + step;
                if (wg_fp32_less(c, sLoc(N, k))) { remaining = step; } else { lo = k + 1; remaining -= step + 1; }
            }
            int iv = lo - 1;
            sIv[sp] = iv;
            int first = iv < 0 ? 0 : iv;
            sState[sp] = 1;
            if (sKind(N, first) == 0u) {
                sV0[sp] = sPayload(N, first);
                st = 1;
            } else {
                if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return F32_ZERO; }
                sp++;
                sN[sp] = int(sPayload(N, first));
                sState[sp] = 0;
                continue;
            }
        }
        if (st == 1) {
            int iv = sIv[sp];
            if (iv < 0 || iv == n - 1) {
                int at = iv < 0 ? 0 : n - 1;
                uint d = sDer(N, at);
                result = wg_fp32_equal(d, F32_ZERO) ? sV0[sp] : wg_fp32_add(sV0[sp], wg_fp32_mul(d, wg_fp32_sub(c, sLoc(N, at))));
                ready = true;
            } else {
                sState[sp] = 2;
                if (sKind(N, iv + 1) == 0u) {
                    sV1[sp] = sPayload(N, iv + 1);
                    st = 2;
                } else {
                    if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return F32_ZERO; }
                    sp++;
                    sN[sp] = int(sPayload(N, iv + 1));
                    sState[sp] = 0;
                    continue;
                }
            }
        }
        if (!ready) {
            int iv = sIv[sp];
            uint l0 = sLoc(N, iv);
            uint l1 = sLoc(N, iv + 1);
            uint span = wg_fp32_sub(l1, l0);
            uint t = jdivf(wg_fp32_sub(c, l0), span);
            uint v0 = sV0[sp];
            uint v1 = sV1[sp];
            uint delta = wg_fp32_sub(v1, v0);
            uint a = wg_fp32_sub(wg_fp32_mul(sDer(N, iv), span), delta);
            uint b = wg_fp32_add(wg_fp32_mul(wg_fp32_negate(sDer(N, iv + 1)), span), delta);
            result = wg_fp32_add(lerpf(t, v0, v1), wg_fp32_mul(wg_fp32_mul(t, wg_fp32_sub(F32_ONE, t)), lerpf(t, a, b)));
        }
        sp--;
        if (sp < 0) return result;
        if (sState[sp] == 1) sV0[sp] = result; else sV1[sp] = result;
    }
    bail(BAIL_RANGE);
    return F32_ZERO;
}
