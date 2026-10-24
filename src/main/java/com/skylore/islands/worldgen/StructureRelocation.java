package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * Random-spread structures get one candidate chunk per spacing region. On sky islands that chunk is
 * usually open void, so most structures never spawn. When it misses land, move the candidate to the
 * island chunk in the same region that is nearest to it. Every caller of
 * {@code getPotentialStructureChunk} (generation, /locate, compasses) sees the same answer.
 */
public final class StructureRelocation {
    private StructureRelocation() {}

    private static final int MODE_OFF = 0;
    private static final int MODE_OVERWORLD = 1;
    private static final int MODE_NETHER = 2;

    /** Largest grid step, in chunks, when scanning a region for land. */
    private static final int MAX_STEP = 4;
    /** Region scans stay affordable: at most this many samples per axis. */
    private static final int MAX_SAMPLES_PER_AXIS = 40;

    private static final ThreadLocal<int[]> MODE = ThreadLocal.withInitial(() -> new int[]{MODE_OFF});
    private static final ThreadLocal<LongKeyedCache<Long>> CACHE = ThreadLocal.withInitial(() -> new LongKeyedCache<>(4096));
    private static final Long NO_MOVE = Long.MIN_VALUE;

    /** Remembers which dimension's island layout placement queries on this thread belong to. */
    public static void bind(ChunkGenerator generator) {
        MODE.get()[0] = modeOf(generator);
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
            if (isLand(candidate.x, candidate.z)) {
                return candidate;
            }
            LongKeyedCache<Long> cache = CACHE.get();
            cache.validate(IslandLayout.columnStamp());
            long key = candidate.toLong() * 31 + placementSalt * 0x9E3779B97F4A7C15L + spacing;
            Long cached = cache.get(key);
            if (cached == null) {
                cached = findLand(candidate, spacing);
                cache.put(key, cached);
            }
            return cached == NO_MOVE ? candidate : new ChunkPos(cached);
        } finally {
            CellularIslandDensityFunction.setLayoutSalt(previousSalt);
        }
    }

    /** Nearest land chunk to the candidate inside its spacing region, preferring chunks clear of the rim. */
    private static Long findLand(ChunkPos candidate, int spacing) {
        int regionX = Math.floorDiv(candidate.x, spacing) * spacing;
        int regionZ = Math.floorDiv(candidate.z, spacing) * spacing;
        int step = Math.max(1, Math.min(MAX_STEP, (spacing + MAX_SAMPLES_PER_AXIS - 1) / MAX_SAMPLES_PER_AXIS));
        long bestSolid = NO_MOVE;
        long bestSolidDist = Long.MAX_VALUE;
        long bestAny = NO_MOVE;
        long bestAnyDist = Long.MAX_VALUE;
        for (int dx = 0; dx < spacing; dx += step) {
            for (int dz = 0; dz < spacing; dz += step) {
                int cx = regionX + dx;
                int cz = regionZ + dz;
                if (!isLand(cx, cz)) {
                    continue;
                }
                long ox = cx - candidate.x;
                long oz = cz - candidate.z;
                long dist = ox * ox + oz * oz;
                if (dist < bestAnyDist) {
                    bestAnyDist = dist;
                    bestAny = ChunkPos.asLong(cx, cz);
                }
                if (dist < bestSolidDist && isSolid(cx, cz)) {
                    bestSolidDist = dist;
                    bestSolid = ChunkPos.asLong(cx, cz);
                }
            }
        }
        return bestSolid != NO_MOVE ? bestSolid : bestAny;
    }

    private static boolean isLand(int chunkX, int chunkZ) {
        return CellularIslandDensityFunction.hasIslandColumn((chunkX << 4) + 8, (chunkZ << 4) + 8);
    }

    /** All four chunk corners on the island too, so starts do not sit on a sliver of rim. */
    private static boolean isSolid(int chunkX, int chunkZ) {
        int x = chunkX << 4;
        int z = chunkZ << 4;
        return CellularIslandDensityFunction.hasIslandColumn(x, z)
                && CellularIslandDensityFunction.hasIslandColumn(x + 15, z)
                && CellularIslandDensityFunction.hasIslandColumn(x, z + 15)
                && CellularIslandDensityFunction.hasIslandColumn(x + 15, z + 15);
    }
}
