// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.fused;

/**
 * Java transliteration, line by line, of the scalar functions of the native {@code fused_lib.glsl}. GLSL
 * {@code double} and {@code float} arithmetic on an IEEE device is Java's, so these are the answers
 * {@code fused_lib_ieee.glsl} must reproduce bit for bit. Where the native text corrects a hardware result
 * with a fused multiply-add ({@code jdiv}, {@code jsqrt}) the transliteration runs that same loop with
 * {@link Math#fma}, so the meaning-based port (return the correctly rounded helper) is checked against the
 * algorithm it replaces and not against itself.
 *
 * <p>A bail is recorded in {@link #bailCode} (the bits {@code atomicOr}ed into the chunk flags natively).
 * Native {@code frexp} of an infinity or a NaN is undefined; {@code safeMagnitude} treats it as unsafe, which
 * is what the port does. The tables are the {@code perm} words and the {@code dtab} values the shader reads.</p>
 */
public final class FusedLibReference {
    public static final int BAIL_DIV = 1, BAIL_SQRT = 2, BAIL_CAST = 4, BAIL_NONFINITE = 8, BAIL_RANGE = 16, BAIL_FLOAT = 32;
    public static final int PERLIN_STRIDE = 7;
    public static final int SPLINE_STACK = 16;
    public static final int SPLINE_COORDS = 3;

    private final int[] perm;
    private final double[] dtab;
    /** Every bail since the last {@link #clearBails()}, or-ed together. */
    public int bailCode;
    /**
     * Off: the text of {@code jdiv} exactly as the native library has it. On: a quotient that underflows to a
     * negative zero keeps its sign, which is what the correctly rounded helper of the integer port returns.
     * The native text returns {@code -mq} only when {@code q < 0.0}, so a quotient of {@code -0.0} (a ratio
     * below 2^-1075, reachable only with operands of very different magnitude) comes back as {@code +0.0}.
     */
    public boolean correctlyRoundedZero;
    /** Cases in which the two settings of {@link #correctlyRoundedZero} would differ, counted when it is on. */
    public int zeroSignDeviations;

    public FusedLibReference(int[] perm, double[] dtab) {
        this.perm = perm;
        this.dtab = dtab;
    }

    public void clearBails() { bailCode = 0; }

    public void bail(int code) { bailCode |= code; }

    /** The mutable generator state of the native {@code struct Rng}. */
    public static final class Rng {
        public long lo, hi, legacy;
        public boolean isLegacy;
    }

    // ---- conversions and rounding --------------------------------------------------------------------

    public static long dbits(double d) { return Double.doubleToRawLongBits(d); }
    public static double bitsd(long b) { return Double.longBitsToDouble(b); }

    /** Java (int) narrowing of a double, written as the native guards and cast. */
    public static int jd2i(double d) {
        if (Double.isNaN(d)) return 0;
        if (d >= 2147483647.0) return 2147483647;
        if (d <= -2147483648.0) return -2147483647 - 1;
        return (int) d;
    }

    /** Mth.floor(double) */
    public static int mfloor(double d) {
        int i = jd2i(d);
        return d < (double) i ? i - 1 : i;
    }

    public static int jrem(int a, int b) { return a - (a / b) * b; }

    public static int jfloorDiv(int a, int b) {
        int q = a / b;
        if ((jrem(a, b) != 0) && ((a < 0) != (b < 0))) q = q - 1;
        return q;
    }

    public static int jfloorMod(int a, int b) { return a - jfloorDiv(a, b) * b; }

    // ---- min and max ---------------------------------------------------------------------------------

    public static double jmin(double a, double b) {
        if (Double.isNaN(a)) return a;
        if (Double.isNaN(b)) return b;
        if (a == 0.0 && b == 0.0) return dbits(a) < 0L ? a : b;
        return a <= b ? a : b;
    }

    public static double jmax(double a, double b) {
        if (Double.isNaN(a)) return a;
        if (Double.isNaN(b)) return b;
        if (a == 0.0 && b == 0.0) return dbits(a) < 0L ? b : a;
        return a >= b ? a : b;
    }

    public static float jminf(float a, float b) {
        if (Float.isNaN(a)) return a;
        if (Float.isNaN(b)) return b;
        if (a == 0.0f && b == 0.0f) return Float.floatToRawIntBits(a) < 0 ? a : b;
        return a <= b ? a : b;
    }

    public static float jmaxf(float a, float b) {
        if (Float.isNaN(a)) return a;
        if (Float.isNaN(b)) return b;
        if (a == 0.0f && b == 0.0f) return Float.floatToRawIntBits(a) < 0 ? b : a;
        return a >= b ? a : b;
    }

    public static double nextUpPos(double q) { return bitsd(dbits(q) + 1L); }   // q > 0 finite
    public static double nextDownPos(double q) { return bitsd(dbits(q) - 1L); } // q > 0 finite, not min subnormal

    // ---- division, square root ------------------------------------------------------------------------

    /** The exponent {@code e} of GLSL {@code frexp} (value = m * 2^e with 0.5 <= |m| < 1) of a finite non-zero value. */
    static int frexpExponent(double v) {
        int unbiased = Math.getExponent(v);
        if (unbiased < Double.MIN_EXPONENT) unbiased = Math.getExponent(v * 0x1p54) - 54;
        return unbiased + 1;
    }

    public static boolean safeMagnitude(double v) {
        if (v == 0.0) return true;
        if (Double.isNaN(v) || Double.isInfinite(v)) return false; // frexp is undefined; the port bails here
        int e = frexpExponent(v);
        return e > -900 && e < 900;
    }

    public double jdiv(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b) || b == 0.0
                || !safeMagnitude(a) || !safeMagnitude(b)) {
            bail(BAIL_DIV);
            return a / b;
        }
        if (a == 0.0) return a / b;
        double q = a / b;
        if (!safeMagnitude(q)) { bail(BAIL_DIV); return q; }
        boolean negative = q < 0.0;
        if (correctlyRoundedZero && q == 0.0 && dbits(q) < 0L) { zeroSignDeviations++; return q; }
        double mq = Math.abs(q);
        double ma = Math.abs(a);
        double mb = Math.abs(b);
        double r = Math.fma(-mq, mb, ma);
        for (int i = 0; i < 4; i++) {
            if (r == 0.0) break;
            double cand = r > 0.0 ? nextUpPos(mq) : nextDownPos(mq);
            double rc = Math.fma(-cand, mb, ma);
            if (Math.abs(rc) < Math.abs(r)) { mq = cand; r = rc; continue; }
            if (Math.abs(rc) == Math.abs(r)) {
                // exact midpoint: choose the even significand
                if ((dbits(mq) & 1L) != 0L) mq = cand;
            }
            break;
        }
        return negative ? -mq : mq;
    }

    public double jsqrt(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x) || x < 0.0 || !safeMagnitude(x)) { bail(BAIL_SQRT); return Math.sqrt(x); }
        if (x == 0.0) return x;
        double c = Math.sqrt(x);
        for (int i = 0; i < 4; i++) {
            double r = Math.fma(-c, c, x);
            double up = nextUpPos(c);
            double down = nextDownPos(c);
            double uUp = up - c;
            double uDown = c - down;
            if (r > 0.0 && r > c * uUp) { c = up; continue; }
            if (r < 0.0 && -r > c * uDown) { c = down; continue; }
            break;
        }
        return c;
    }

    public float jdivf(float a, float b) {
        if (b == 0.0f || Float.isNaN(a) || Float.isNaN(b) || Float.isInfinite(a) || Float.isInfinite(b)) { bail(BAIL_FLOAT); return a / b; }
        return (float) jdiv((double) a, (double) b);
    }

    public float jsqrtf(float x) { return (float) jsqrt((double) x); }

    /** Java float remainder (fmod, exact) for finite a and b > 0 used by End islands. */
    public float jremf(float a, float b) {
        if (Float.isNaN(a) || Float.isInfinite(a) || !(b > 0.0f)) { bail(BAIL_FLOAT); return 0.0f; }
        double da = Math.abs((double) a);
        double db = (double) b;
        double q = Math.floor(da / db);
        double r = da - q * db;
        if (r < 0.0) r += db;
        if (r >= db) r -= db;
        float res = (float) r;
        return a < 0.0f ? -res : res;
    }

    // ---- interpolation ---------------------------------------------------------------------------------

    public static double lerpd(double t, double a, double b) { return a + t * (b - a); }
    public static float lerpf(float t, float a, float b) { return a + t * (b - a); }

    public static double clampedLerp(double a, double b, double t) {
        if (t < 0.0) return a;
        if (t > 1.0) return b;
        return lerpd(t, a, b);
    }

    public double inverseLerp(double v, double a, double b) { return jdiv(v - a, b - a); }
    public double clampedMap(double v, double a, double b, double c, double d) { return clampedLerp(c, d, inverseLerp(v, a, b)); }
    public double mapd(double v, double a, double b, double c, double d) { return lerpd(inverseLerp(v, a, b), c, d); }
    public static double mclamp(double v, double lo, double hi) { return v < lo ? lo : jmin(v, hi); }

    // ---- noise ----------------------------------------------------------------------------------------

    public static double smoothstepd(double v) { return v * v * v * (v * (v * 6.0 - 15.0) + 10.0); }
    public static double wrapd(double v) { return v - Math.floor(v / 33554432.0 + 0.5) * 33554432.0; }

    public int permAt(int base, int index) { return perm[base + (index & 255)]; }

    public static double gradDot(int hash, double x, double y, double z) {
        switch (hash & 15) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: return -y + z;
            case 10: return y - z;
            case 11: return -y - z;
            case 12: return x + y;
            case 13: return -y + z;
            case 14: return -x + y;
            default: return -y - z;
        }
    }

    public double improvedNoise(int permBase, double xo, double yo, double zo,
                                double x, double y, double z, double yScale, double yMax) {
        double sx = x + xo, sy = y + yo, sz = z + zo;
        int x0 = mfloor(sx), y0 = mfloor(sy), z0 = mfloor(sz);
        double fx = sx - (double) x0, fy = sy - (double) y0, fz = sz - (double) z0;
        double smear = 0.0;
        if (yScale != 0.0) {
            double clamped = (yMax >= 0.0 && yMax < fy) ? yMax : fy;
            smear = Math.floor(jdiv(clamped, yScale) + 1.0000000116860974E-7) * yScale;
        }
        double afy = fy - smear;
        int xh = permAt(permBase, x0), xhn = permAt(permBase, x0 + 1);
        int xy00 = permAt(permBase, xh + y0), xy01 = permAt(permBase, xh + y0 + 1);
        int xy10 = permAt(permBase, xhn + y0), xy11 = permAt(permBase, xhn + y0 + 1);
        double v000 = gradDot(permAt(permBase, xy00 + z0), fx, afy, fz);
        double v100 = gradDot(permAt(permBase, xy10 + z0), fx - 1.0, afy, fz);
        double v010 = gradDot(permAt(permBase, xy01 + z0), fx, afy - 1.0, fz);
        double v110 = gradDot(permAt(permBase, xy11 + z0), fx - 1.0, afy - 1.0, fz);
        double v001 = gradDot(permAt(permBase, xy00 + z0 + 1), fx, afy, fz - 1.0);
        double v101 = gradDot(permAt(permBase, xy10 + z0 + 1), fx - 1.0, afy, fz - 1.0);
        double v011 = gradDot(permAt(permBase, xy01 + z0 + 1), fx, afy - 1.0, fz - 1.0);
        double v111 = gradDot(permAt(permBase, xy11 + z0 + 1), fx - 1.0, afy - 1.0, fz - 1.0);
        double tx = smoothstepd(fx), ty = smoothstepd(fy), tz = smoothstepd(fz);
        double x00 = lerpd(tx, v000, v100), x10 = lerpd(tx, v010, v110);
        double x01 = lerpd(tx, v001, v101), x11 = lerpd(tx, v011, v111);
        return lerpd(tz, lerpd(ty, x00, x10), lerpd(ty, x01, x11));
    }

    public double perlinValue(int P, double x, double y, double z) {
        int n = (int) dtab[P];
        double result = 0.0;
        for (int i = 0; i < n; i++) {
            int L = P + 1 + i * PERLIN_STRIDE;
            int permBase = (int) dtab[L + 6];
            if (permBase >= 0) {
                double f = dtab[L + 1];
                double noise = improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5],
                        wrapd(x * f), wrapd(y * f), wrapd(z * f), 0.0, 0.0);
                result += dtab[L] * noise * dtab[L + 2];
            }
        }
        return result;
    }

    public double normalNoise(int N, double x, double y, double z) {
        double first = perlinValue((int) dtab[N + 1], x, y, z);
        double factor = 1.0181268882175227;
        double second = perlinValue((int) dtab[N + 2], x * factor, y * factor, z * factor);
        return (first + second) * dtab[N];
    }

    public double legacyLevel(int P, int octave, double x, double y, double z, double yScale, double yMax) {
        int n = (int) dtab[P];
        int levelIndex = n - 1 - octave;
        if (levelIndex < 0 || levelIndex >= n) return 0.0;
        int L = P + 1 + levelIndex * PERLIN_STRIDE;
        int permBase = (int) dtab[L + 6];
        if (permBase < 0) return 0.0;
        return dtab[L] * improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5], x, y, z, yScale, yMax);
    }

    public double blendedNoise(int B, int bx, int by, int bz) {
        double xzMultiplier = 684.412 * dtab[B];
        double yMultiplier = 684.412 * dtab[B + 1];
        double xValue = (double) bx * xzMultiplier;
        double yValue = (double) by * yMultiplier;
        double zValue = (double) bz * xzMultiplier;
        double mainX = jdiv(xValue, dtab[B + 2]);
        double mainY = jdiv(yValue, dtab[B + 3]);
        double mainZ = jdiv(zValue, dtab[B + 2]);
        double smearScale = jdiv(yMultiplier * dtab[B + 4], dtab[B + 3]);
        int minP = (int) dtab[B + 5], maxP = (int) dtab[B + 6], mainP = (int) dtab[B + 7];
        double main = 0.0;
        double mainScale = 1.0;
        double inverse = 1.0;
        for (int i = 0; i < 8; i++) {
            main += legacyLevel(mainP, i, wrapd(mainX * mainScale), wrapd(mainY * mainScale), wrapd(mainZ * mainScale),
                    smearScale * mainScale, mainY * mainScale) * inverse;
            mainScale *= 0.5;
            inverse *= 2.0;
        }
        double blend = (jdiv(main, 10.0) + 1.0) * 0.5;
        boolean skipMin = blend >= 1.0;
        boolean skipMax = blend <= 0.0;
        double minV = 0.0, maxV = 0.0;
        double limitScale = 1.0;
        double limitInverse = 1.0;
        double limitSmear = yMultiplier * dtab[B + 4];
        for (int i = 0; i < 16; i++) {
            double wx = wrapd(xValue * limitScale), wy = wrapd(yValue * limitScale), wz = wrapd(zValue * limitScale);
            if (!skipMin) minV += legacyLevel(minP, i, wx, wy, wz, limitSmear * limitScale, yValue * limitScale) * limitInverse;
            if (!skipMax) maxV += legacyLevel(maxP, i, wx, wy, wz, limitSmear * limitScale, yValue * limitScale) * limitInverse;
            limitScale *= 0.5;
            limitInverse *= 2.0;
        }
        double lower = minV * (1.0 / 512.0);
        double upper = maxV * (1.0 / 512.0);
        double clampedBlend = jmax(0.0, jmin(1.0, blend));
        return (lower + clampedBlend * (upper - lower)) * (1.0 / 128.0);
    }

    // ---- End island -------------------------------------------------------------------------------------

    private static final double SIMPLEX_F2 = 0.3660254037844386;
    private static final double SIMPLEX_G2 = 0.21132486540518713;

    public static double simplexCorner(int hash, double x, double y) {
        double att = 0.5 - x * x - y * y;
        if (att < 0.0) return 0.0;
        att *= att;
        int h = hash % 12;
        double g;
        switch (h) {
            case 0: g = x + y; break;
            case 1: g = -x + y; break;
            case 2: g = x - y; break;
            case 3: g = -x - y; break;
            case 4: g = x; break;
            case 5: g = -x; break;
            case 6: g = x; break;
            case 7: g = -x; break;
            case 8: g = y; break;
            case 9: g = -y; break;
            case 10: g = y; break;
            default: g = -y; break;
        }
        return att * att * g;
    }

    public double simplex2D(int permBase, double x, double y) {
        double skew = (x + y) * SIMPLEX_F2;
        int lx = mfloor(x + skew);
        int ly = mfloor(y + skew);
        double unskew = (double) (lx + ly) * SIMPLEX_G2;
        double x0 = x - ((double) lx - unskew);
        double y0 = y - ((double) ly - unskew);
        int ox = x0 > y0 ? 1 : 0;
        int oy = x0 > y0 ? 0 : 1;
        double x1 = x0 - (double) ox + SIMPLEX_G2;
        double y1 = y0 - (double) oy + SIMPLEX_G2;
        double x2 = x0 - 1.0 + 2.0 * SIMPLEX_G2;
        double y2 = y0 - 1.0 + 2.0 * SIMPLEX_G2;
        int h0 = permAt(permBase, lx + permAt(permBase, ly)) % 12;
        int h1 = permAt(permBase, lx + ox + permAt(permBase, ly + oy)) % 12;
        int h2 = permAt(permBase, lx + 1 + permAt(permBase, ly + 1)) % 12;
        double n0 = simplexCorner(h0, x0, y0);
        double n1 = simplexCorner(h1, x1, y1);
        double n2 = simplexCorner(h2, x2, y2);
        return 70.0 * (n0 + n1 + n2);
    }

    public double endIsland(int E, int bx, int bz) {
        int permBase = (int) dtab[E];
        int x = bx / 8;
        int z = bz / 8;
        int gridX = x / 2;
        int gridZ = z / 2;
        int remX = jrem(x, 2);
        int remZ = jrem(z, 2);
        float height = 100.0f - jsqrtf((float) (x * x + z * z)) * 8.0f;
        height = jmaxf(-100.0f, jminf(80.0f, height));
        for (int i = -12; i <= 12; i++) {
            for (int j = -12; j <= 12; j++) {
                long ix = (long) gridX + (long) i;
                long iz = (long) gridZ + (long) j;
                if (ix * ix + iz * iz > 4096L && simplex2D(permBase, (double) ix, (double) iz) < -0.8999999761581421) {
                    float scale = (float) Math.abs(ix) * 3439.0f + (float) Math.abs(iz) * 147.0f;
                    scale = jremf(scale, 13.0f);
                    scale += 9.0f;
                    float lx = (float) (remX - i * 2);
                    float lz = (float) (remZ - j * 2);
                    float h = 100.0f - jsqrtf(lx * lx + lz * lz) * scale;
                    h = jmaxf(-100.0f, jminf(80.0f, h));
                    height = jmaxf(height, h);
                }
            }
        }
        return jdiv((double) height - 8.0, 128.0);
    }

    // ---- random -----------------------------------------------------------------------------------------

    public static long mixStafford(long v) {
        v = (v ^ (v >>> 30)) * -4658895280553007687L;  // 0xBF58476D1CE4E5B9
        v = (v ^ (v >>> 27)) * -7723592293110705685L;  // 0x94D049BB133111EB
        return v ^ (v >>> 31);
    }

    public static long rotl64(long v, int s) { return (v << s) | (v >>> (64 - s)); }

    /** Mth.getSeed(x, y, z) */
    public static long positionSeed(int x, int y, int z) {
        long v = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        v = v * v * 42317861L + v * 11L;
        return v >> 16;
    }

    public static Rng rngAt(boolean legacyAlgorithm, long seed, long seedLo, long seedHi, int x, int y, int z) {
        Rng r = new Rng();
        long ps = positionSeed(x, y, z);
        r.isLegacy = legacyAlgorithm;
        if (legacyAlgorithm) {
            r.legacy = ((ps ^ seed) ^ 25214903917L) & 281474976710655L;
        } else {
            long lo = ps ^ seedLo;
            long hi = seedHi;
            if ((lo | hi) == 0L) { lo = -7046029254386353131L; hi = 7640891576956012809L; }
            r.lo = lo;
            r.hi = hi;
        }
        return r;
    }

    public static long xoroNext(Rng r) {
        long result = rotl64(r.lo + r.hi, 17) + r.lo;
        long nh = r.hi ^ r.lo;
        r.lo = rotl64(r.lo, 49) ^ nh ^ (nh << 21);
        r.hi = rotl64(nh, 28);
        return result;
    }

    public static int legacyBits(Rng r, int bits) {
        r.legacy = (r.legacy * 25214903917L + 11L) & 281474976710655L;
        return (int) (r.legacy >> (48 - bits));
    }

    public static int rngNextInt(Rng r, int bound) {
        if (r.isLegacy) {
            if ((bound & -bound) == bound) return (int) (((long) bound * (long) legacyBits(r, 31)) >> 31);
            int bits, value;
            do { bits = legacyBits(r, 31); value = bits % bound; } while (bits - value + (bound - 1) < 0);
            return value;
        }
        long bits = xoroNext(r) & 4294967295L;
        long product = bits * (long) bound;
        long low = product & 4294967295L;
        if (low < (long) bound) {
            long threshold = (long) (Integer.remainderUnsigned(-bound, bound));
            while (low < threshold) {
                bits = xoroNext(r) & 4294967295L;
                product = bits * (long) bound;
                low = product & 4294967295L;
            }
        }
        return (int) (product >> 32);
    }

    public static float rngNextFloat(Rng r) {
        if (r.isLegacy) return (float) legacyBits(r, 24) * 5.9604645E-8f;
        return (float) (int) (xoroNext(r) >>> 40) * 5.9604645E-8f;
    }

    // ---- splines ----------------------------------------------------------------------------------------

    private float sLoc(int N, int i) { return Float.intBitsToFloat(perm[N + 2 + i * 4]); }
    private float sDer(int N, int i) { return Float.intBitsToFloat(perm[N + 3 + i * 4]); }
    private int sKind(int N, int i) { return perm[N + 4 + i * 4]; }
    private int sPayload(int N, int i) { return perm[N + 5 + i * 4]; }

    public float splineEval(int root, float[] cs) {
        int[] sN = new int[SPLINE_STACK];
        int[] sState = new int[SPLINE_STACK];
        int[] sIv = new int[SPLINE_STACK];
        float[] sV0 = new float[SPLINE_STACK];
        float[] sV1 = new float[SPLINE_STACK];
        int sp = 0;
        sN[0] = root;
        sState[0] = 0;
        float result = 0.0f;
        for (int guard = 0; guard < 4096; guard++) {
            int N = sN[sp];
            int n = perm[N];
            float c = cs[perm[N + 1]];
            int st = sState[sp];
            boolean ready = false;
            if (st == 0) {
                // Mth.binarySearch(0, n, i -> c < locations[i]) - 1, probe for probe
                int lo = 0;
                int remaining = n;
                while (remaining > 0) {
                    int step = remaining / 2;
                    int k = lo + step;
                    if (c < sLoc(N, k)) { remaining = step; } else { lo = k + 1; remaining -= step + 1; }
                }
                int iv = lo - 1;
                sIv[sp] = iv;
                int first = iv < 0 ? 0 : iv;
                sState[sp] = 1;
                if (sKind(N, first) == 0) {
                    sV0[sp] = Float.intBitsToFloat(sPayload(N, first));
                    st = 1;
                } else {
                    if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return 0.0f; }
                    sp++;
                    sN[sp] = sPayload(N, first);
                    sState[sp] = 0;
                    continue;
                }
            }
            if (st == 1) {
                int iv = sIv[sp];
                if (iv < 0 || iv == n - 1) {
                    int at = iv < 0 ? 0 : n - 1;
                    float d = sDer(N, at);
                    result = d == 0.0f ? sV0[sp] : sV0[sp] + d * (c - sLoc(N, at));
                    ready = true;
                } else {
                    sState[sp] = 2;
                    if (sKind(N, iv + 1) == 0) {
                        sV1[sp] = Float.intBitsToFloat(sPayload(N, iv + 1));
                        st = 2;
                    } else {
                        if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return 0.0f; }
                        sp++;
                        sN[sp] = sPayload(N, iv + 1);
                        sState[sp] = 0;
                        continue;
                    }
                }
            }
            if (!ready) {
                int iv = sIv[sp];
                float l0 = sLoc(N, iv);
                float l1 = sLoc(N, iv + 1);
                float span = l1 - l0;
                float t = jdivf(c - l0, span);
                float v0 = sV0[sp];
                float v1 = sV1[sp];
                float delta = v1 - v0;
                float a = sDer(N, iv) * span - delta;
                float b = -sDer(N, iv + 1) * span + delta;
                result = lerpf(t, v0, v1) + t * (1.0f - t) * lerpf(t, a, b);
            }
            sp--;
            if (sp < 0) return result;
            if (sState[sp] == 1) sV0[sp] = result; else sV1[sp] = result;
        }
        bail(BAIL_RANGE);
        return 0.0f;
    }
}
