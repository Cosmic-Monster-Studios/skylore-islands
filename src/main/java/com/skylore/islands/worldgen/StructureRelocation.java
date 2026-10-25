package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Random-spread structures get one candidate chunk per spacing region. On sky islands that chunk is
 * usually open void, so most structures never spawn. Move the candidate to the chunk in the same
 * region that sits deepest inside an island, so starts land inland rather than on the rim. Every caller of
 * {@code getPotentialStructureChunk} (generation, /locate, compasses) sees the same answer.
 */
public final class StructureRelocation {
    private StructureRelocation() {}

    private static final int MODE_OFF = 0;
    private static final int MODE_OVERWORLD = 1;
    private static final int MODE_NETHER = 2;

    /** Largest grid step, in chunks, when scanning a region for land. */
    private static final int MAX_STEP = 4;
    /** Chunks from the island edge that count as fully inland for relocated starts. */
    private static final int INTERIOR_CHUNKS = 4;
    /** Region scans stay affordable: at most this many samples per axis. */
    private static final int MAX_SAMPLES_PER_AXIS = 40;

    private static final ThreadLocal<int[]> MODE = ThreadLocal.withInitial(() -> new int[]{MODE_OFF});
    private static final ThreadLocal<LongKeyedCache<Long>> CACHE = ThreadLocal.withInitial(() -> new LongKeyedCache<>(16384));
    private static final Long NO_MOVE = Long.MIN_VALUE;

    /** Remembers which dimension's island layout placement queries on this thread belong to. */
    public static void bind(ChunkGenerator generator) {
        MODE.get()[0] = modeOf(generator);
    }

    /** Whether this generator builds sky islands (the Overworld or Nether noise settings). */
    public static boolean isIslandGenerator(ChunkGenerator generator) {
        return modeOf(generator) != MODE_OFF;
    }

    private static int modeOf(ChunkGenerator generator) {
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            if (noise.generatorSettings().is(NoiseGeneratorSettings.OVERWORLD)) {
                return MODE_OVERWORLD;
            }
            if (noise.generatorSettings().is(NoiseGeneratorSettings.NETHER)) {
                return MODE_NETHER;
            }
        }
        return MODE_OFF;
    }

    public static ChunkPos relocate(ChunkPos candidate, int spacing, int placementSalt) {
        int mode = MODE.get()[0];
        if (mode == MODE_OFF || !SkyloreConfig.relocateStructures() || spacing < 2) {
            return candidate;
        }
        long layoutSalt = mode == MODE_NETHER ? CellularIslandDensityFunction.NETHER_SALT : CellularIslandDensityFunction.OVERWORLD_SALT;
        long previousSalt = CellularIslandDensityFunction.layoutSalt();
        CellularIslandDensityFunction.setLayoutSalt(layoutSalt);
        try {
            long stamp = IslandLayout.columnStamp();
            LongKeyedCache<Long> cache = CACHE.get();
            cache.validate(stamp);
            long key = candidate.toLong() * 31 + placementSalt * 0x9E3779B97F4A7C15L + spacing;
            Long cached = cache.get(key);
            if (cached == null) {
                cached = pickInterior(regionMap(stamp, layoutSalt, candidate, spacing), candidate);
                cache.put(key, cached);
            }
            return cached == NO_MOVE ? candidate : new ChunkPos(cached);
        } finally {
            CellularIslandDensityFunction.setLayoutSalt(previousSalt);
        }
    }

    /**
     * Island depth across one spacing region (plus a margin), shared by every placement with that
     * spacing and by every worker thread. The map does not depend on the placement, only on the
     * region and grid, so it is built once instead of once per structure set per thread.
     */
    private record RegionMap(int regionX, int regionZ, int step, int maxDepth, int cells, int n, byte[] depth, int deepest) {
        int depthAt(int i, int j) {
            return depth[i * n + j];
        }
    }

    private record RegionKey(long layoutSalt, int spacing, int regionX, int regionZ) {}

    private static final ConcurrentHashMap<RegionKey, RegionMap> REGION_MAPS = new ConcurrentHashMap<>();
    private static volatile long regionMapStamp;
    /** Region maps kept before the shared cache is dropped wholesale (about 2 KB each). */
    private static final int MAX_REGION_MAPS = 16384;

    private static RegionMap regionMap(long stamp, long layoutSalt, ChunkPos candidate, int spacing) {
        if (regionMapStamp != stamp) {
            REGION_MAPS.clear();
            regionMapStamp = stamp;
        } else if (REGION_MAPS.size() > MAX_REGION_MAPS) {
            REGION_MAPS.clear();
        }
        int regionX = Math.floorDiv(candidate.x, spacing) * spacing;
        int regionZ = Math.floorDiv(candidate.z, spacing) * spacing;
        return REGION_MAPS.computeIfAbsent(new RegionKey(layoutSalt, spacing, regionX, regionZ),
                key -> buildRegionMap(key.regionX(), key.regionZ(), key.spacing()));
    }

    private static RegionMap buildRegionMap(int regionX, int regionZ, int spacing) {
        int step = Math.max(1, Math.min(MAX_STEP, (spacing + MAX_SAMPLES_PER_AXIS - 1) / MAX_SAMPLES_PER_AXIS));
        int maxDepth = (INTERIOR_CHUNKS + step - 1) / step;
        int cells = (spacing + step - 1) / step;
        int n = cells + 2 * maxDepth;
        // depth: grid steps to the nearest void sample, capped at maxDepth. The margin lets samples
        // near the region edge see void that lies just outside it.
        byte[] depth = new byte[n * n];
        int[] queue = new int[n * n];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (isLand(regionX + (i - maxDepth) * step, regionZ + (j - maxDepth) * step)) {
                    depth[i * n + j] = (byte) maxDepth;
                } else {
                    queue[tail++] = i * n + j;
                }
            }
        }
        while (head < tail) {
            int c = queue[head++];
            int next = depth[c] + 1;
            if (next >= maxDepth) {
                continue;
            }
            int ci = c / n;
            int cj = c % n;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    int i = ci + di;
                    int j = cj + dj;
                    if (i >= 0 && j >= 0 && i < n && j < n && depth[i * n + j] > next) {
                        depth[i * n + j] = (byte) next;
                        queue[tail++] = i * n + j;
                    }
                }
            }
        }
        int deepest = 0;
        for (int i = maxDepth; i < maxDepth + cells; i++) {
            for (int j = maxDepth; j < maxDepth + cells; j++) {
                deepest = Math.max(deepest, depth[i * n + j]);
            }
        }
        return new RegionMap(regionX, regionZ, step, maxDepth, cells, n, depth, deepest);
    }

    /**
     * The chunk in the candidate's region that sits deepest inside an island (capped at
     * {@link #INTERIOR_CHUNKS} from the edge), nearest to the candidate among equals. Nearest land
     * alone is almost always the rim, where big structures hang off into the void. Keeps the
     * candidate when nothing in the region is deeper than it already is.
     */
    private static Long pickInterior(RegionMap map, ChunkPos candidate) {
        if (map.deepest() == 0) {
            return NO_MOVE;
        }
        int step = map.step();
        int ci = Math.floorDiv(candidate.x - map.regionX(), step) + map.maxDepth();
        int cj = Math.floorDiv(candidate.z - map.regionZ(), step) + map.maxDepth();
        int candidateDepth = isLand(candidate.x, candidate.z) ? map.depthAt(ci, cj) : 0;
        if (candidateDepth >= map.deepest()) {
            return NO_MOVE;
        }
        long best = NO_MOVE;
        long bestDist = Long.MAX_VALUE;
        int target = map.deepest();
        int lo = map.maxDepth();
        int hi = map.maxDepth() + map.cells();
        for (int i = lo; i < hi; i++) {
            for (int j = lo; j < hi; j++) {
                if (map.depthAt(i, j) != target) {
                    continue;
                }
                int cx = map.regionX() + (i - lo) * step;
                int cz = map.regionZ() + (j - lo) * step;
                long ox = cx - candidate.x;
                long oz = cz - candidate.z;
                long dist = ox * ox + oz * oz;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ChunkPos.asLong(cx, cz);
                }
            }
        }
        return best;
    }

    private static boolean isLand(int chunkX, int chunkZ) {
        return CellularIslandDensityFunction.hasIslandColumn((chunkX << 4) + 8, (chunkZ << 4) + 8);
    }
}
