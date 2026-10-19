package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction.ColumnSnapshot;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction.FastNoise;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction.WaterColumn;

/**
 * Basin, river, and swamp water columns.
 */
public final class IslandWater {

    private static final ThreadLocal<LongKeyedCache<WaterColumn>> WATER_COLUMN_CACHE = ThreadLocal.withInitial(() -> new LongKeyedCache<>(2048));
    /** Template blocks arrive Y, then X, then Z; keep enough columns for a large structure piece. */
    private static final ThreadLocal<LongKeyedCache<ColumnSnapshot>> COLUMN_SNAPSHOT_CACHE = ThreadLocal.withInitial(() -> new LongKeyedCache<>(4096));
    private static final WaterColumn NO_WATER = new WaterColumn(0, 0, false);

    private IslandWater() {
    }

    public static boolean couldHaveWaterAtY(int y) {
        return y >= -32 && y <= 300;
    }

    public static boolean isInsideWaterBasin(int x, int y, int z) {
        return isInsideWaterBasin(x, y, z,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius());
    }

    public static boolean isInsideWaterBasin(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        if (!couldHaveWaterAtY(y)) {
            return false;
        }
        ColumnSnapshot snapshot = columnSnapshotAt(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
        WaterColumn column = snapshot.waterColumn;
        return column != null && y > column.seabedY && y <= column.waterLevel;
    }

    public static boolean isInsideInteriorWaterBasin(int x, int y, int z) {
        return isInsideInteriorWaterBasin(x, y, z,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius());
    }

    /** Interior basin water only — same rule NoiseChunkMixin used with four neighbor checks. */
    public static boolean isInsideInteriorWaterBasin(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        if (!couldHaveWaterAtY(y)) {
            return false;
        }
        ChunkGenColumnCache.ensureFor(x, z);
        if (ChunkGenColumnCache.isActiveFor(x, z)) {
            if (!ChunkGenColumnCache.waterInteriorAt(x, z) || ChunkGenColumnCache.layoutAt(x, z) == null) {
                return false;
            }
            WaterColumn column = ChunkGenColumnCache.waterAt(x, z);
            return column != null && y > column.seabedY && y <= column.waterLevel;
        }
        ColumnSnapshot snapshot = columnSnapshotAt(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
        if (!snapshot.waterInterior) {
            return false;
        }
        WaterColumn column = snapshot.waterColumn;
        return column != null && y > column.seabedY && y <= column.waterLevel;
    }

    public static WaterColumn waterColumnAt(int x, int z) {
        return waterColumnAt(x, z,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius());
    }

    public static WaterColumn waterColumnAt(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        if (ChunkGenColumnCache.isActiveFor(x, z)) {
            return ChunkGenColumnCache.waterAt(x, z);
        }
        // Same answer the chunk cache would give, without building it for a one-off structure query.
        if (CellularIslandDensityFunction.chunkMightContainIsland(x >> 4, z >> 4) && !IslandLayout.hasIslandColumn(x, z)) {
            return null;
        }
        LongKeyedCache<WaterColumn> cache = WATER_COLUMN_CACHE.get();
        cache.validate(IslandLayout.columnStamp());
        long key = IslandLayout.packedXZ(x, z);
        WaterColumn hit = cache.get(key);
        if (hit != null) {
            return hit == NO_WATER ? null : hit;
        }
        WaterColumn column = computeWaterColumnUncached(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
        cache.put(key, column == null ? NO_WATER : column);
        return column;
    }

    public static long encodeWaterColumn(WaterColumn column) {
        if (column == null) {
            return Long.MIN_VALUE;
        }
        int packed = (column.seabedY + 2048) & 0xFFF;
        packed |= ((column.waterLevel + 2048) & 0xFFF) << 12;
        if (column.oceanBowl) {
            packed |= 1 << 24;
        }
        packed |= 1 << 25;
        return packed;
    }

    public static WaterColumn decodeWaterColumn(long packed) {
        if (packed == Long.MIN_VALUE || (packed & (1L << 25)) == 0) {
            return null;
        }
        int seabedY = (int) (packed & 0xFFF) - 2048;
        int waterLevel = (int) ((packed >> 12) & 0xFFF) - 2048;
        boolean oceanBowl = (packed & (1L << 24)) != 0;
        return new WaterColumn(seabedY, waterLevel, oceanBowl);
    }

    public static WaterColumn computeWaterColumnUncached(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        IslandLayout.WarpedPoint warp = IslandLayout.warpedPoint(x, z, cellSize);
        double wx = warp.wx;
        double wz = warp.wz;
        int cellX = warp.cellX;
        int cellZ = warp.cellZ;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = cellX + ox;
                int cz = cellZ + oz;

                CellularIslandDensityFunction.IslandLayout layout = IslandLayout.layoutForCell(cx, cz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null) {
                    continue;
                }

                int archetype = layout.archetype;
                boolean isMega = layout.mega;
                int baseRadius = layout.baseRadius;
                long cellHash = layout.cellHash;
                int altitude = layout.altitude;

                double dx = wx - layout.centerX;
                double dz = wz - layout.centerZ;
                double distSq = dx * dx + dz * dz;
                if (distSq > baseRadius * baseRadius * IslandLayout.MAX_SHAPE_SCALE_SQ) {
                    continue;
                }
                double dist = Math.sqrt(distSq);
                double angle = Math.atan2(dz, dx);

                double phase1 = ((cellHash >> 16) & 0xFF) * 0.025;
                double phase2 = ((cellHash >> 24) & 0xFF) * 0.025;

                double shapeMod = CellularIslandDensityFunction.getShapeMod(archetype, angle, phase1, phase2);
                double effectiveRadius = baseRadius * shapeMod;

                if (dist >= effectiveRadius) {
                    continue;
                }

                double normDist = dist / effectiveRadius;
                double smoothHoriz = Math.cos(normDist * (Math.PI / 2.0));
                double macroNoise = IslandVolume.surfaceNoise2D(x, z);
                double topSurfaceNoise = FastNoise.noise2D(x * 0.035, z * 0.035, 401) * 6.5;

                if (archetype == 4 && normDist < 0.70) {
                    double seabedRatio = normDist / 0.70;
                    double basinDepth = 26.0 * (1.0 - Math.pow(seabedRatio, 1.5));
                    int seabedY = (int) Math.floor(altitude - 4.0 - basinDepth + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz);
                    return new WaterColumn(seabedY, altitude - 4, true);
                }
                if (archetype == 1 && normDist < 0.45) {
                    double lakeRatio = normDist / 0.45;
                    double lakeDepth = 16.0 * (1.0 - Math.pow(lakeRatio, 1.6));
                    int seabedY = (int) Math.floor(altitude - 4.0 - lakeDepth + (topSurfaceNoise + macroNoise * 0.3) * smoothHoriz);
                    return new WaterColumn(seabedY, altitude - 4, false);
                }
                if (archetype == 5 && normDist < 0.60) {
                    double swampNoise = FastNoise.noise2D(wx * 0.04, wz * 0.04, 801);
                    if (swampNoise < -0.15) {
                        double islandHeight = 34.0 + ((cellHash >> 3) & 15);
                        double baseTop = (islandHeight * 0.6) * Math.pow(smoothHoriz, 0.7) + (topSurfaceNoise + macroNoise * 0.4) * smoothHoriz;
                        double poolDepth = 3.0 * (-0.15 - swampNoise) * (1.0 - normDist / 0.60);
                        int poolBed = (int) Math.floor(altitude + baseTop - poolDepth);
                        int poolWater = (int) Math.floor(altitude + baseTop);
                        return new WaterColumn(poolBed, poolWater, false);
                    }
                }
                boolean hasRiver = ((cellHash >> 9) & 3) == 0 && baseRadius >= 75;
                if (hasRiver && normDist < 0.55) {
                    double riverNoise = Math.abs(FastNoise.noise2D(wx * 0.012 + 500, wz * 0.012 + 500, 701));
                    if (riverNoise < 0.07) {
                        double islandHeight = isMega ? 80.0 : 34.0 + ((cellHash >> 3) & 15);
                        double baseTop = islandHeight * Math.pow(smoothHoriz, 0.7) + (topSurfaceNoise + macroNoise * 0.5) * smoothHoriz;
                        double riverDepth = 4.5 * (1.0 - riverNoise / 0.07) * (1.0 - normDist / 0.55);
                        int riverBed = (int) Math.floor(altitude + baseTop - riverDepth);
                        int riverWater = (int) Math.floor(altitude + baseTop - 1.0);
                        return new WaterColumn(riverBed, riverWater, false);
                    }
                }
            }
        }

        return null;
    }

    public static ColumnSnapshot columnSnapshotAt(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        LongKeyedCache<ColumnSnapshot> cache = COLUMN_SNAPSHOT_CACHE.get();
        cache.validate(LongKeyedCache.stamp(CellularIslandDensityFunction.worldSeed(), CellularIslandDensityFunction.layoutSalt(),
                cellSize, minAlt, maxAlt, minRad, maxRad, SkyloreConfig.islandDensity()));
        long key = IslandLayout.packedXZ(x, z);
        ColumnSnapshot hit = cache.get(key);
        if (hit != null) {
            return hit;
        }

        CellularIslandDensityFunction.IslandLayout layout = IslandLayout.findLayoutContainingUncached(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
        WaterColumn waterColumn = computeWaterColumnUncached(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
        boolean waterInterior = waterColumn != null
                && IslandLayout.hasIslandColumn(x + 1, z)
                && IslandLayout.hasIslandColumn(x - 1, z)
                && IslandLayout.hasIslandColumn(x, z + 1)
                && IslandLayout.hasIslandColumn(x, z - 1);
        ColumnSnapshot snapshot = new ColumnSnapshot(layout, waterColumn, waterInterior);
        cache.put(key, snapshot);
        return snapshot;
    }

    /** Caves/canyons must not cut ocean bowls or other sky water. */
    public static boolean shouldSkipCarver(int x, int y, int z) {
        if (IslandLayout.isOceanIslandColumn(x, z)) {
            return true;
        }
        return isInsideWaterBasin(x, y, z);
    }

    /**
     * Continentalness helper: ocean bowl, caldera, and swamp footprints that need ocean biome sampling.
     */
    public static boolean isInsideOceanBiomeFootprint(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        IslandLayout.WarpedPoint warp = IslandLayout.warpedPoint(x, z, cellSize);
        double wx = warp.wx;
        double wz = warp.wz;
        int cellX = warp.cellX;
        int cellZ = warp.cellZ;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = IslandLayout.layoutForCell(cellX + ox, cellZ + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null) {
                    continue;
                }

                int archetype = layout.archetype;
                if (archetype != 4 && archetype != 1 && archetype != 5) {
                    continue;
                }

                double dx = wx - layout.centerX;
                double dz = wz - layout.centerZ;
                double distSq = dx * dx + dz * dz;
                if (distSq > layout.baseRadius * layout.baseRadius * IslandLayout.MAX_SHAPE_SCALE_SQ) {
                    continue;
                }
                double dist = Math.sqrt(distSq);
                double angle = Math.atan2(dz, dx);

                double phase1 = ((layout.cellHash >> 16) & 0xFF) * 0.025;
                double phase2 = ((layout.cellHash >> 24) & 0xFF) * 0.025;

                double shapeMod = CellularIslandDensityFunction.getShapeMod(archetype, angle, phase1, phase2);
                double effectiveRadius = layout.baseRadius * shapeMod;

                if (dist < effectiveRadius) {
                    double normDist = dist / effectiveRadius;
                    if (archetype == 4 && normDist < 0.70) {
                        return true;
                    } else if (archetype == 1 && normDist < 0.45) {
                        return true;
                    } else if (archetype == 5 && normDist < 0.60) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean isOceanBiomeFootprintOnLayout(int x, int z, CellularIslandDensityFunction.IslandLayout layout, int cellSize) {
        int archetype = layout.archetype;
        if (archetype != 4 && archetype != 1 && archetype != 5) {
            return false;
        }

        IslandLayout.WarpedPoint warp = IslandLayout.warpedPoint(x, z, cellSize);
        double dx = warp.wx - layout.centerX;
        double dz = warp.wz - layout.centerZ;
        double distSq = dx * dx + dz * dz;
        if (distSq > layout.baseRadius * layout.baseRadius * IslandLayout.MAX_SHAPE_SCALE_SQ) {
            return false;
        }

        double dist = Math.sqrt(distSq);
        double angle = Math.atan2(dz, dx);
        double phase1 = ((layout.cellHash >> 16) & 0xFF) * 0.025;
        double phase2 = ((layout.cellHash >> 24) & 0xFF) * 0.025;
        double shapeMod = CellularIslandDensityFunction.getShapeMod(archetype, angle, phase1, phase2);
        double effectiveRadius = layout.baseRadius * shapeMod;
        if (dist >= effectiveRadius) {
            return false;
        }

        double normDist = dist / effectiveRadius;
        if (archetype == 4) {
            return normDist < 0.70;
        } else if (archetype == 1) {
            return normDist < 0.45;
        } else {
            return normDist < 0.60;
        }
    }
}
