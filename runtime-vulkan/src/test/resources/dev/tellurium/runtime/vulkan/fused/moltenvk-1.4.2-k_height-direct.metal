#pragma clang diagnostic ignored "-Wmissing-prototypes"
#pragma clang diagnostic ignored "-Wmissing-braces"

#include <metal_stdlib>
#include <simd/simd.h>

using namespace metal;

template<typename T, size_t Num>
struct spvUnsafeArray
{
    T elements[Num ? Num : 1];
    
    thread T& operator [] (size_t pos) thread
    {
        return elements[pos];
    }
    constexpr const thread T& operator [] (size_t pos) const thread
    {
        return elements[pos];
    }
    
    device T& operator [] (size_t pos) device
    {
        return elements[pos];
    }
    constexpr const device T& operator [] (size_t pos) const device
    {
        return elements[pos];
    }
    
    constexpr const constant T& operator [] (size_t pos) const constant
    {
        return elements[pos];
    }
    
    threadgroup T& operator [] (size_t pos) threadgroup
    {
        return elements[pos];
    }
    constexpr const threadgroup T& operator [] (size_t pos) const threadgroup
    {
        return elements[pos];
    }
};

// Implementation of signed integer mod accurate to SPIR-V specification
template<typename Tx, typename Ty>
inline Tx spvSMod(Tx x, Ty y)
{
    Tx remainder = x - y * (x / y);
    return select(Tx(remainder + y), remainder, remainder == 0 || (x >= 0) == (y >= 0));
}

struct Push
{
    uint chunkCount;
    uint prelimCount;
};

struct ChunkInfo
{
    int chunkX;
    int chunkZ;
    int beardOffset;
    int pieceCount;
    int junctionCount;
    int pad0;
    int pad1;
    int pad2;
};

struct Chunks
{
    ChunkInfo chunks[1];
};

struct Output
{
    uint blocks[1];
};

struct Perm
{
    uint perm[1];
};

struct Heights
{
    int heights[1];
};

constant uint3 gl_WorkGroupSize [[maybe_unused]] = uint3(64u, 1u, 1u);

struct DTab
{
    uint2 dtab[1];
};

struct Columns
{
    uint2 columns[1];
};

struct Corners
{
    uint2 corners[1];
};

struct Aquifer
{
    int aquifer[1];
};

struct Beard
{
    int beard[1];
};

struct Flags
{
    uint flags[1];
};

struct XzCache
{
    uint2 xzcache[1];
};

struct PrelimIndex
{
    int prelimIndex[1];
};

struct PrelimCols
{
    int prelimCols[1];
};

struct PrelimOut
{
    int prelimOut[1];
};

struct Biomes
{
    uint biomes[1];
};

struct SurfTmp
{
    int surfTmp[1];
};

constant spvUnsafeArray<int, 10> _143 = spvUnsafeArray<int, 10>({ 0, 6, 5, 5, 6, 6, 6, 6, 6, 6 });

kernel void main0(constant Push& pc [[buffer(0)]], device Chunks& _63 [[buffer(1)]], device Perm& _127 [[buffer(2)]], device Output& _89 [[buffer(7)]], device Heights& _183 [[buffer(10)]], uint3 gl_GlobalInvocationID [[thread_position_in_grid]])
{
    bool gBailed = false;
    bool gShared = false;
    uint id = gl_GlobalInvocationID.x;
    uint slot = id / 256u;
    uint gChunk;
    int gBaseX;
    int gBaseZ;
    int gFirstQX;
    int gFirstQZ;
    if (slot >= pc.chunkCount)
    {
        return;
    }
    int col = int(id % 256u);
    int lz = col / 16;
    int lx = spvSMod(col, 16);
    uint per = 24576u;
    int ocean = -64;
    int surface = -64;
    bool haveOcean = false;
    bool haveSurface = false;
    int surf = _63.chunks[slot].pad0;
    int _121;
    int ly = 383;
    for (;;)
    {
        bool _77 = ly >= 0;
        bool _84;
        if (_77)
        {
            _84 = !(haveOcean && haveSurface);
        }
        else
        {
            _84 = _77;
        }
        if (_84)
        {
            uint word = _89.blocks[(slot * per) + uint(((ly * 64) + (lz * 4)) + (lx / 4))];
            int state = int((word >> uint(8 * spvSMod(lx, 4))) & 127u);
            if (surf != 0)
            {
                _121 = int(_127.perm[int(_127.perm[surf + 1]) + state]);
            }
            else
            {
                _121 = _143[state];
            }
            int f = _121;
            bool _151 = !haveOcean;
            bool _158;
            if (_151)
            {
                _158 = (f & 2) != 0;
            }
            else
            {
                _158 = _151;
            }
            if (_158)
            {
                ocean = ((-64) + ly) + 1;
                haveOcean = true;
            }
            bool _166 = !haveSurface;
            bool _172;
            if (_166)
            {
                _172 = (f & 4) != 0;
            }
            else
            {
                _172 = _166;
            }
            if (_172)
            {
                surface = ((-64) + ly) + 1;
                haveSurface = true;
            }
            ly--;
            continue;
        }
        else
        {
            break;
        }
    }
    _183.heights[((slot * 2u) * 256u) + uint(col)] = ocean;
    _183.heights[(((slot * 2u) + 1u) * 256u) + uint(col)] = surface;
}

