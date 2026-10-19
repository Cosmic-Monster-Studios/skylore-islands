package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction.FastNoise;

/**
 * Voronoi island placement, warp, footprints, and chunk occupancy.
 * Data type remains {@link CellularIslandDensityFunction.IslandLayout}.
 */
public final class IslandLayout {

    public static final double MAX_SHAPE_SCALE_SQ = 1.60 * 1.60;
    public static final double MEGA_RADIUS_SCALE = 2.10;
    public static final int Y_PAD_BELOW = 200;
    public static final int Y_PAD_ABOVE = 120;
    public static final double LAND_BRIDGE_HALF_WIDTH = 10.0;
    public static final double LAND_BRIDGE_GAP_SLACK = 80.0;
    /** Max |warp| from warpedPoint (~45+14). Chunk AABB tests are unwarped. */
    public static final double WARP_SLACK = 60.0;

    /** Islet packing in {@link CellularIslandDensityFunction.IslandLayout#islets}: x, z, radius, y offset. */
    public static final int ISLET_STRIDE = 4;
    public static final double[] NO_ISLETS = new double[0];
    /** Farthest islet edge past baseRadius * max shape: max radius 14 + max gap 39 + max radius 14. */
    public static final double ISLET_REACH = 68.0;
    /** Satellite packing in {@link CellularIslandDensityFunction.IslandLayout#satellites}: x, z, radius. */
    public static final int SATELLITE_STRIDE = 3;
    /** Slack around a chunk's warped bounds; the warp bends by far less than this across 16 blocks. */
    private static final double ISLET_CHUNK_PAD = 4.0;

    private static final int[][] PAIR_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final CellularIslandDensityFunction.IslandLayout ABSENT_LAYOUT =
            new CellularIslandDensityFunction.IslandLayout(0, 0, 0, 0, 0, 0, -1, false, 0L);

    static final ThreadLocal<CellularIslandDensityFunction.IslandLayout[]> CHUNK_LAYOUT_HITS =
            ThreadLocal.withInitial(() -> new CellularIslandDensityFunction.IslandLayout[256]);
    static final ThreadLocal<CellularIslandDensityFunction.IslandLayout[]> NEARBY_LAYOUTS =
            ThreadLocal.withInitial(() -> new CellularIslandDensityFunction.IslandLayout[9]);
    static final ThreadLocal<long[]> NEARBY_META = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE, 0L});
    static final ThreadLocal<long[]> FOOTPRINT_CACHE = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE, 0L});
    static final ThreadLocal<long[]> CHUNK_INSIDE_CACHE = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE, 0L});
    private static final ThreadLocal<LongKeyedCache<CellularIslandDensityFunction.IslandLayout>> LAYOUT_CACHE =
            ThreadLocal.withInitial(() -> new LongKeyedCache<>(1024));
    private static final ThreadLocal<WarpCache> WARP_CACHE = ThreadLocal.withInitial(WarpCache::new);
    private static final ThreadLocal<LayoutSlot> LAYOUT_CONTAINING_CACHE = ThreadLocal.withInitial(LayoutSlot::new);

    private IslandLayout() {
    }

    private static long placementSalt() {
        return CellularIslandDensityFunction.layoutSalt() ^ CellularIslandDensityFunction.worldSeed();
    }

    public static CellularIslandDensityFunction.IslandLayout[] nearbyLayouts() {
        return NEARBY_LAYOUTS.get();
    }

    public static CellularIslandDensityFunction.IslandLayout layoutForCell(int cx, int cz, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        LongKeyedCache<CellularIslandDensityFunction.IslandLayout> cache = LAYOUT_CACHE.get();
        cache.validate(LongKeyedCache.stamp(CellularIslandDensityFunction.worldSeed(), CellularIslandDensityFunction.layoutSalt(),
                cellSize, minAlt, maxAlt, minRad, maxRad, SkyloreConfig.islandDensity()));
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        CellularIslandDensityFunction.IslandLayout cached = cache.get(key);
        if (cached != null) {
            return cached.archetype < 0 ? null : cached;
        }

        long cellHash = hashCoordinates(cx, cz, placementSalt());
        if (!doesCellHaveIsland(cx, cz, cellHash)) {
            cache.put(key, ABSENT_LAYOUT);
            return null;
        }

        int archetype = getArchetype(cellHash);
        boolean isMega = isMegaIsland(cellHash);
        int baseRadius = getBaseRadius(cellHash, minRad, maxRad, archetype, isMega);

        int[] offset = pairAwareOffset(cx, cz, cellHash, cellSize, baseRadius);
        int altitude = computeAltitude(cx, cz, cellHash, minAlt, maxAlt);

        CellularIslandDensityFunction.IslandLayout layout = new CellularIslandDensityFunction.IslandLayout(cx, cz, cx * cellSize + offset[0], cz * cellSize + offset[1],
                altitude, baseRadius, archetype, isMega, cellHash);
        cache.put(key, layout);
        return layout;
    }

    public static boolean doesCellHaveIsland(int cx, int cz, long cellHash) {
        if (cx == 0 && cz == 0) {
            return true;
        }

        int macroX = Math.floorDiv(cx, 5);
        int macroZ = Math.floorDiv(cz, 5);
        if ((macroX != 0 || macroZ != 0) && Math.floorMod(hashCoordinates(macroX, macroZ, 99999L ^ CellularIslandDensityFunction.worldSeed()), 400) == 0) {
            return (cellHash & 3) != 0;
        }

        int density = SkyloreConfig.islandDensity();
        if (density <= 0) {
            return false;
        }

        if (spawnsIsolatedIsland(cx, cz, cellHash)) {
            return true;
        }

        if (density > 50 && Math.floorMod(cellHash >> 19, 100) < (density - 50)) {
            return true;
        }

        return isPairPartnerCell(cx, cz);
    }

    public static boolean spawnsIsolatedIsland(int cx, int cz, long cellHash) {
        if (!isLocalHashMinimum(cx, cz, cellHash)) {
            return false;
        }
        int keep = Math.min(100, SkyloreConfig.islandDensity() * 2);
        return Math.floorMod(cellHash >> 17, 100) < keep;
    }

    public static boolean isLocalHashMinimum(int cx, int cz, long cellHash) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) {
                    continue;
                }
                int nx = cx + ox;
                int nz = cz + oz;
                if (nx == 0 && nz == 0) {
                    return false;
                }
                long neighborHash = hashCoordinates(nx, nz, placementSalt());
                if (compareCells(nx, nz, neighborHash, cx, cz, cellHash) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isPairHostCell(int cx, int cz, long cellHash) {
        if (cx == 0 && cz == 0) {
            return Math.floorMod(cellHash >> 11, pairDivisor()) == 0;
        }
        return spawnsIsolatedIsland(cx, cz, cellHash) && Math.floorMod(cellHash >> 11, pairDivisor()) == 0;
    }

    public static boolean isPairPartnerCell(int cx, int cz) {
        int[][] dirs = PAIR_DIRS;
        for (int i = 0; i < dirs.length; i++) {
            int hx = cx - dirs[i][0];
            int hz = cz - dirs[i][1];
            long hostHash = hashCoordinates(hx, hz, placementSalt());
            if (!isPairHostCell(hx, hz, hostHash)) {
                continue;
            }
            int claimed = Math.floorMod(hostHash >> 3, 4);
            if (dirs[claimed][0] == dirs[i][0] && dirs[claimed][1] == dirs[i][1]) {
                return true;
            }
        }
        return false;
    }

    public static int pairDivisor() {
        // Default density 70 → divisor 2, so about half of isolated islands get a neighbor.
        return Math.max(2, 7 - SkyloreConfig.islandDensity() / 12);
    }

    /**
     * Isolated islands keep random jitter. Pair hosts and partners sit on the shared cell edge
     * so they read as a cluster instead of two loners a full cell apart.
     */
    public static int[] pairAwareOffset(int cx, int cz, long cellHash, int cellSize, int baseRadius) {
        int jitterRange = Math.max(32, cellSize - baseRadius * 2 - 48);
        int minOff = baseRadius + 24;
        int maxOff = minOff + jitterRange - 1;
        int randomX = (int) (Math.abs((cellHash >> 14) % jitterRange)) + minOff;
        int randomZ = (int) (Math.abs((cellHash >> 22) % jitterRange)) + minOff;

        if (cx == 0 && cz == 0) {
            return new int[]{cellSize / 2, cellSize / 2};
        }

        int[] pull = pairPullDir(cx, cz, cellHash);
        if (pull == null) {
            return new int[]{randomX, randomZ};
        }
        int ox = pull[0] > 0 ? maxOff : (pull[0] < 0 ? minOff : randomX);
        int oz = pull[1] > 0 ? maxOff : (pull[1] < 0 ? minOff : randomZ);
        return new int[]{ox, oz};
    }

    /** Cell-space direction to sit toward the other island in a pair, or null if isolated. */
    public static int[] pairPullDir(int cx, int cz, long cellHash) {
        if (isPairHostCell(cx, cz, cellHash)) {
            return PAIR_DIRS[Math.floorMod(cellHash >> 3, 4)];
        }
        for (int i = 0; i < PAIR_DIRS.length; i++) {
            int hx = cx - PAIR_DIRS[i][0];
            int hz = cz - PAIR_DIRS[i][1];
            long hostHash = hashCoordinates(hx, hz, placementSalt());
            if (!isPairHostCell(hx, hz, hostHash)) {
                continue;
            }
            int claimed = Math.floorMod(hostHash >> 3, 4);
            if (PAIR_DIRS[claimed][0] == PAIR_DIRS[i][0] && PAIR_DIRS[claimed][1] == PAIR_DIRS[i][1]) {
                return new int[]{-PAIR_DIRS[i][0], -PAIR_DIRS[i][1]};
            }
        }
        return null;
    }

    public static int compareCells(int ax, int az, long aHash, int bx, int bz, long bHash) {
        int cmp = Long.compare(aHash, bHash);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(ax, bx);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(az, bz);
    }

    public static boolean isMegaIsland(long cellHash) {
        // Super rare massive floating continents: ~1.25% of islands (1 in 80)
        return ((cellHash >> 7) % 80) == 0;
    }

    public static int getBaseRadius(long cellHash, int minRad, int maxRad, int archetype, boolean isMega) {
        if (isMega) {
            return (int) (maxRad * 2.10); // Super rare colossal continent: radius ~300..360 blocks
        }
        if (archetype == 4) {
            // Super rare ocean basin: radius ~240..340 blocks
            return Math.max(220, (int) (maxRad * 1.65));
        }

        int tier = (int) (Math.abs((cellHash >> 10) % 100));
        int span = Math.max(1, maxRad - minRad);

        if (tier < 65) {
            // Small regular island (65% frequency): radius 45..85 blocks (90..170 blocks across)
            return minRad + (int) (Math.abs((cellHash >> 12) % ((int) (span * 0.32) + 1)));
        } else if (tier < 93) {
            // Medium regular island (28% frequency): radius 85..130 blocks (170..260 blocks across)
            return minRad + (int) (span * 0.32) + (int) (Math.abs((cellHash >> 12) % ((int) (span * 0.36) + 1)));
        } else {
            // Large regular island (7% frequency): radius 130..170 blocks (260..340 blocks across)
            return minRad + (int) (span * 0.68) + (int) (Math.abs((cellHash >> 12) % ((int) (span * 0.32) + 1)));
        }
    }

    public static int getArchetype(long cellHash) {
        // Archetype distribution:
        // Slot 31: Archetype 4 (Super Rare Ocean Basin, ~3.1%)
        // Slot 30: Archetype 1 (Caldera Lake, ~3.1%)
        // Slot 28, 29: Archetype 5 (Swamp / Wetland Island, ~6.2%)
        // Slots 0..27: Regular land islands (~87.6%)
        int slot = (int) (Math.abs((cellHash >> 4) % 32));
        if (slot == 31) {
            return 4; // Ocean Basin
        } else if (slot == 30) {
            return 1; // Mountain Caldera Lake
        } else if (slot == 28 || slot == 29) {
            return 5; // Swamp / Wetland Island
        } else {
            int land = slot % 4;
            // Nether only: some multi-lobe cells become extra lava calderas. Overworld slots unchanged.
            if (land == 0 && CellularIslandDensityFunction.usingNetherLayout()
                    && ((cellHash >> 29) & 7) == 0) {
                return 1;
            }
            return land;
        }
    }

    public static int computeAltitude(int cx, int cz, long cellHash, int minAlt, int maxAlt) {
        if (cx == 0 && cz == 0) {
            return Math.max(minAlt, Math.min(100, minAlt + Math.max(1, maxAlt - minAlt) / 3));
        }
        int span = Math.max(1, maxAlt - minAlt + 1);
        int a = (int) (Math.abs(cellHash) % span);
        int b = (int) (Math.abs(cellHash >> 21) % span);
        return minAlt + Math.min(a, b);
    }

    public static long hashCoordinates(int x, int z, long seed) {
        long h = seed + (long) x * 374761393L + (long) z * 668265263L;
        h = (h ^ (h >> 13)) * 1274126177L;
        return h ^ (h >> 16);
    }

    public static CellularIslandDensityFunction.IslandLayout findLayoutContainingUncached(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        WarpedPoint warp = warpedPoint(x, z, cellSize);
        double wx = warp.wx;
        double wz = warp.wz;
        int cellX = warp.cellX;
        int cellZ = warp.cellZ;
        CellularIslandDensityFunction.IslandLayout[] nearby = NEARBY_LAYOUTS.get();
        int n = nearbyLayoutsForColumn(x, z, cellX, cellZ, cellSize, minAlt, maxAlt, minRad, maxRad);
        for (int i = 0; i < n; i++) {
            if (columnOnIslandOrExtra(nearby[i], wx, wz)) {
                rememberFootprint(x, z, true);
                return nearby[i];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (isLandBridge(nearby[i], nearby[j]) && onLandBridge(wx, wz, nearby[i], nearby[j])) {
                    rememberFootprint(x, z, false);
                    return nearby[i];
                }
            }
        }
        rememberFootprint(x, z, false);
        return null;
    }

    public static CellularIslandDensityFunction.IslandLayout findNearestMegaIsland(int x, int z, int cellRadius) {
        int cellSize = SkyloreConfig.cellSize();
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();
        int[] cell = cellOfWarped(x, z, cellSize);
        CellularIslandDensityFunction.IslandLayout best = null;
        double bestDist = Double.MAX_VALUE;
        for (int ox = -cellRadius; ox <= cellRadius; ox++) {
            for (int oz = -cellRadius; oz <= cellRadius; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(cell[0] + ox, cell[1] + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null || !layout.mega) {
                    continue;
                }
                double dx = (double) layout.centerX - x;
                double dz = (double) layout.centerZ - z;
                double dist = dx * dx + dz * dz;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = layout;
                }
            }
        }
        return best;
    }

    public static CellularIslandDensityFunction.IslandLayout findNearestIsland(int x, int z, int cellRadius, int minBaseRadius) {
        int cellSize = SkyloreConfig.cellSize();
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();
        int[] cell = cellOfWarped(x, z, cellSize);
        CellularIslandDensityFunction.IslandLayout best = null;
        double bestDist = Double.MAX_VALUE;
        for (int ox = -cellRadius; ox <= cellRadius; ox++) {
            for (int oz = -cellRadius; oz <= cellRadius; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(cell[0] + ox, cell[1] + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null || layout.baseRadius < minBaseRadius) {
                    continue;
                }
                double dx = (double) layout.centerX - x;
                double dz = (double) layout.centerZ - z;
                double dist = dx * dx + dz * dz;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = layout;
                }
            }
        }
        return best;
    }

    public static CellularIslandDensityFunction.IslandLayout layoutContaining(int x, int z) {
        if (ChunkGenColumnCache.isActiveFor(x, z)) {
            return ChunkGenColumnCache.layoutAt(x, z);
        }
        long key = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt() ^ (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
        LayoutSlot cache = LAYOUT_CONTAINING_CACHE.get();
        if (cache.key == key) {
            return cache.layout;
        }
        CellularIslandDensityFunction.IslandLayout layout = findLayoutContainingUncached(x, z,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius());
        cache.key = key;
        cache.layout = layout;
        return layout;
    }

    public static boolean hasIslandColumn(int x, int z) {
        return layoutContaining(x, z) != null;
    }

    public static boolean isOceanIslandColumn(int x, int z) {
        CellularIslandDensityFunction.IslandLayout layout = layoutContaining(x, z);
        return layout != null && layout.archetype == 4;
    }

    /** Inclusive block Y-band covering sampled island columns in this chunk, or null. */
    public static int[] sampleIslandYBand(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int dz = 0; dz <= 15; dz += 4) {
            for (int dx = 0; dx <= 15; dx += 4) {
                CellularIslandDensityFunction.IslandLayout layout = layoutContaining(x0 + dx, z0 + dz);
                if (layout == null) {
                    continue;
                }
                minY = Math.min(minY, layout.altitude - Y_PAD_BELOW);
                maxY = Math.max(maxY, layout.altitude + Y_PAD_ABOVE);
            }
        }
        CellularIslandDensityFunction.IslandLayout corner = layoutContaining(x0 + 15, z0 + 15);
        if (corner != null) {
            minY = Math.min(minY, corner.altitude - Y_PAD_BELOW);
            maxY = Math.max(maxY, corner.altitude + Y_PAD_ABOVE);
        }
        if (minY == Integer.MAX_VALUE) {
            CellularIslandDensityFunction.IslandLayout islet = isletLayoutNearChunk(chunkX, chunkZ);
            if (islet == null) {
                return null;
            }
            return new int[]{islet.altitude - Y_PAD_BELOW, islet.altitude + Y_PAD_ABOVE};
        }
        return new int[]{minY, maxY};
    }

    public static boolean chunkAabbMightContainIsland(int chunkX, int chunkZ) {
        return isChunkInsideIsland(
                chunkX,
                chunkZ,
                SkyloreConfig.cellSize(),
                SkyloreConfig.minIslandAltitude(),
                SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(),
                SkyloreConfig.maxIslandRadius());
    }

    public static boolean chunkMightContainIsland(int chunkX, int chunkZ) {
        long key = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt() ^ (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
        long[] cache = CHUNK_INSIDE_CACHE.get();
        if (cache[0] == key) {
            return cache[1] != 0L;
        }
        boolean aabb = chunkAabbMightContainIsland(chunkX, chunkZ);
        boolean inside = aabb && (chunkHasSampledIslandColumn(chunkX, chunkZ) || isletLayoutNearChunk(chunkX, chunkZ) != null);
        cache[0] = key;
        cache[1] = inside ? 1L : 0L;
        return inside;
    }

    public static boolean isChunkInsideIsland(int chunkX, int chunkZ, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        int reach = maxHorizontalReach(maxRad);
        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        int minCellX = Math.floorDiv(chunkMinX - reach, cellSize) - 1;
        int maxCellX = Math.floorDiv(chunkMaxX + reach, cellSize) + 1;
        int minCellZ = Math.floorDiv(chunkMinZ - reach, cellSize) - 1;
        int maxCellZ = Math.floorDiv(chunkMaxZ + reach, cellSize) + 1;

        int homeX = Math.floorDiv(chunkMinX + 8, cellSize);
        int homeZ = Math.floorDiv(chunkMinZ + 8, cellSize);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(homeX + ox, homeZ + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout != null && chunkHitsLayout(layout, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
                    return true;
                }
            }
        }

        CellularIslandDensityFunction.IslandLayout[] hits = CHUNK_LAYOUT_HITS.get();
        int hitCount = 0;
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(cx, cz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null) {
                    continue;
                }
                if (hitCount < hits.length) {
                    hits[hitCount++] = layout;
                }

                if (chunkHitsLayout(layout, chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ)) {
                    return true;
                }
            }
        }

        for (int i = 0; i < hitCount; i++) {
            CellularIslandDensityFunction.IslandLayout a = hits[i];
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox < 0 || (ox == 0 && oz <= 0)) {
                        continue;
                    }
                    CellularIslandDensityFunction.IslandLayout b = layoutForCell(a.cellX + ox, a.cellZ + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                    if (b == null || !isLandBridge(a, b)) {
                        continue;
                    }
                    if (segmentNearAabb(a.centerX, a.centerZ, b.centerX, b.centerZ,
                            chunkMinX, chunkMinZ, chunkMaxX, chunkMaxZ, LAND_BRIDGE_HALF_WIDTH + 2.0)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean chunkHitsLayout(CellularIslandDensityFunction.IslandLayout layout, int chunkMinX, int chunkMinZ, int chunkMaxX, int chunkMaxZ) {
        double layoutReach = layoutSkipRadius(layout) + WARP_SLACK;
        int closestX = Math.max(chunkMinX, Math.min(layout.centerX, chunkMaxX));
        int closestZ = Math.max(chunkMinZ, Math.min(layout.centerZ, chunkMaxZ));
        double dx = layout.centerX - closestX;
        double dz = layout.centerZ - closestZ;
        return dx * dx + dz * dz <= layoutReach * layoutReach;
    }

    public static boolean chunkHasSampledIslandColumn(int chunkX, int chunkZ) {
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        for (int dz = 0; dz <= 15; dz += 4) {
            for (int dx = 0; dx <= 15; dx += 4) {
                if (hasIslandColumn(x0 + dx, z0 + dz)) {
                    return true;
                }
            }
        }
        return hasIslandColumn(x0 + 15, z0 + 15);
    }

    public static int[] cellOfWarped(int x, int z, int cellSize) {
        WarpedPoint warp = warpedPoint(x, z, cellSize);
        return new int[]{warp.cellX, warp.cellZ};
    }

    public static WarpedPoint warpedPoint(int x, int z, int cellSize) {
        WarpCache cache = WARP_CACHE.get();
        long key = packedXZ(x, z) ^ ((long) cellSize << 1);
        if (cache.key == key) {
            return cache.point;
        }
        double wx = x + warpOffsetX(x, z);
        double wz = z + warpOffsetZ(x, z);
        WarpedPoint point = new WarpedPoint(wx, wz, Math.floorDiv((int) wx, cellSize), Math.floorDiv((int) wz, cellSize));
        cache.key = key;
        cache.point = point;
        return point;
    }

    public static double warpOffsetX(int x, int z) {
        return FastNoise.noise2D(x * 0.008, z * 0.008, 101) * 45.0 + FastNoise.noise2D(x * 0.03, z * 0.03, 102) * 14.0;
    }

    public static double warpOffsetZ(int x, int z) {
        return FastNoise.noise2D((x + 600) * 0.008, (z + 600) * 0.008, 103) * 45.0 + FastNoise.noise2D((x + 600) * 0.03, (z + 600) * 0.03, 104) * 14.0;
    }

    /**
     * The block column whose warped position is nearest (wx, wz): a damped fixed-point walk,
     * then a 5x5 search to settle on whole blocks. Deterministic, so every caller agrees.
     * Returns {x, z, squared warped distance * 1000}.
     */
    public static long[] unwarp(double wx, double wz) {
        double x = wx;
        double z = wz;
        for (int i = 0; i < 24; i++) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            x += 0.6 * (wx - (bx + warpOffsetX(bx, bz)));
            z += 0.6 * (wz - (bz + warpOffsetZ(bx, bz)));
        }
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);
        long bestX = cx;
        long bestZ = cz;
        double best = Double.MAX_VALUE;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int px = cx + ox;
                int pz = cz + oz;
                double dx = px + warpOffsetX(px, pz) - wx;
                double dz = pz + warpOffsetZ(px, pz) - wz;
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    bestX = px;
                    bestZ = pz;
                }
            }
        }
        return new long[]{bestX, bestZ, (long) (best * 1000.0)};
    }

    public static int nearbyLayoutsForColumn(int x, int z, int cellX, int cellZ,
            int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        long key = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt() ^ packedXZ(x, z);
        long[] meta = NEARBY_META.get();
        if (meta[0] == key) {
            return (int) meta[1];
        }
        int n = collectNearbyLayouts(NEARBY_LAYOUTS.get(), cellX, cellZ, cellSize, minAlt, maxAlt, minRad, maxRad);
        meta[0] = key;
        meta[1] = n;
        return n;
    }

    public static int collectNearbyLayouts(CellularIslandDensityFunction.IslandLayout[] dest, int cellX, int cellZ,
            int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        int n = 0;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(cellX + ox, cellZ + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout != null) {
                    dest[n++] = layout;
                }
            }
        }
        return n;
    }

    public static boolean columnOnIslandOrExtra(CellularIslandDensityFunction.IslandLayout layout, double wx, double wz) {
        double dx = wx - layout.centerX;
        double dz = wz - layout.centerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double angle = Math.atan2(dz, dx);
        double phase1 = ((layout.cellHash >> 16) & 0xFF) * 0.025;
        double phase2 = ((layout.cellHash >> 24) & 0xFF) * 0.025;
        double shapeMod = CellularIslandDensityFunction.getShapeMod(layout.archetype, angle, phase1, phase2);
        if (dist < layout.baseRadius * shapeMod) {
            return true;
        }
        if (!hasSatellites(layout) || dist < layout.baseRadius * 1.10 || dist > layout.baseRadius * 1.40 + 36.0) {
            return onIslet(layout, wx, wz);
        }
        double[] sats = layout.satellites;
        for (int o = 0; o < sats.length; o += SATELLITE_STRIDE) {
            double sdx = wx - sats[o];
            double sdz = wz - sats[o + 1];
            double rad = sats[o + 2];
            if (sdx * sdx + sdz * sdz < rad * rad) {
                return true;
            }
        }
        return onIslet(layout, wx, wz);
    }

    /**
     * Satellites (radius 18-33) at 1.15-1.35x baseRadius around large land, 1-3 per island.
     * Positions are in warped space, like the island body.
     */
    public static double[] computeSatellites(int centerX, int centerZ, int baseRadius, int archetype, boolean mega, long cellHash) {
        if (isBowlArchetype(archetype) || !(mega || baseRadius >= 90)) {
            return NO_ISLETS;
        }
        int count = 1 + (int) Math.floorMod(cellHash >> 18, 3);
        double[] sats = new double[count * SATELLITE_STRIDE];
        for (int i = 0; i < count; i++) {
            double ang = ((cellHash >> (8 + i * 6)) & 63) * (Math.PI * 2.0 / 64.0);
            double distScale = 1.15 + 0.20 * (((cellHash >> (i * 4)) & 3) / 3.0);
            double orbit = baseRadius * distScale;
            int o = i * SATELLITE_STRIDE;
            sats[o] = centerX + Math.cos(ang) * orbit;
            sats[o + 1] = centerZ + Math.sin(ang) * orbit;
            sats[o + 2] = 18.0 + ((cellHash >> (i * 5)) & 15);
        }
        return sats;
    }

    /**
     * Rock islets (radius 6-14) orbiting just outside the shoreline: 1-3 per island, 3-6 on large land.
     * Bowl archetypes keep a clean rim. Positions are in warped space, like the island body.
     */
    public static double[] computeIslets(int centerX, int centerZ, int baseRadius, int archetype, boolean mega, long cellHash) {
        if (isBowlArchetype(archetype)) {
            return NO_ISLETS;
        }
        long h = cellHash * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        boolean large = mega || baseRadius >= 90;
        int count = large ? 3 + (int) Math.floorMod(h, 4L) : 1 + (int) Math.floorMod(h, 3L);
        double phase1 = ((cellHash >> 16) & 0xFF) * 0.025;
        double phase2 = ((cellHash >> 24) & 0xFF) * 0.025;
        double[] islets = new double[count * ISLET_STRIDE];
        for (int i = 0; i < count; i++) {
            long r = hashCoordinates(i, 7919, h);
            double ang = (r & 1023) * (Math.PI * 2.0 / 1024.0);
            double rad = 6 + Math.floorMod(r >>> 10, 9L);
            double gap = 8 + ((r >>> 14) & 31);
            double yOffset = -30 + Math.floorMod(r >>> 20, 41L);
            double orbit = baseRadius * CellularIslandDensityFunction.getShapeMod(archetype, ang, phase1, phase2) + rad + gap;
            int o = i * ISLET_STRIDE;
            islets[o] = centerX + Math.cos(ang) * orbit;
            islets[o + 1] = centerZ + Math.sin(ang) * orbit;
            islets[o + 2] = rad;
            islets[o + 3] = yOffset;
        }
        return islets;
    }

    public static boolean onIslet(CellularIslandDensityFunction.IslandLayout layout, double wx, double wz) {
        double[] islets = layout.islets;
        for (int o = 0; o < islets.length; o += ISLET_STRIDE) {
            double dx = wx - islets[o];
            double dz = wz - islets[o + 1];
            double rad = islets[o + 2];
            if (dx * dx + dz * dz < rad * rad) {
                return true;
            }
        }
        return false;
    }

    /**
     * Islets are too small for the 4-block column sampling, so test their discs against the
     * bounding box of the chunk's warped corners. Returns the owning layout, or null.
     */
    public static CellularIslandDensityFunction.IslandLayout isletLayoutNearChunk(int chunkX, int chunkZ) {
        int cellSize = SkyloreConfig.cellSize();
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();
        int x0 = chunkX << 4;
        int z0 = chunkZ << 4;
        double minWx = Double.MAX_VALUE;
        double minWz = Double.MAX_VALUE;
        double maxWx = -Double.MAX_VALUE;
        double maxWz = -Double.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            WarpedPoint p = warpedPoint(x0 + ((corner & 1) * 15), z0 + ((corner >> 1) * 15), cellSize);
            minWx = Math.min(minWx, p.wx);
            minWz = Math.min(minWz, p.wz);
            maxWx = Math.max(maxWx, p.wx);
            maxWz = Math.max(maxWz, p.wz);
        }
        WarpedPoint center = warpedPoint(x0 + 8, z0 + 8, cellSize);
        int cellX = center.cellX;
        int cellZ = center.cellZ;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = layoutForCell(cellX + ox, cellZ + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null) {
                    continue;
                }
                double[] islets = layout.islets;
                for (int o = 0; o < islets.length; o += ISLET_STRIDE) {
                    double cx = Math.max(minWx, Math.min(maxWx, islets[o]));
                    double cz = Math.max(minWz, Math.min(maxWz, islets[o + 1]));
                    double dx = islets[o] - cx;
                    double dz = islets[o + 1] - cz;
                    double reach = islets[o + 2] + ISLET_CHUNK_PAD;
                    if (dx * dx + dz * dz <= reach * reach) {
                        return layout;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isLandBridge(CellularIslandDensityFunction.IslandLayout a, CellularIslandDensityFunction.IslandLayout b) {
        if (a == null || b == null || isBowlArchetype(a.archetype) || isBowlArchetype(b.archetype)) {
            return false;
        }
        double gx = a.centerX - b.centerX;
        double gz = a.centerZ - b.centerZ;
        double maxGap = a.baseRadius + b.baseRadius + LAND_BRIDGE_GAP_SLACK;
        if (gx * gx + gz * gz >= maxGap * maxGap) {
            return false;
        }
        int ax = a.cellX;
        int az = a.cellZ;
        int bx = b.cellX;
        int bz = b.cellZ;
        if (ax > bx || (ax == bx && az > bz)) {
            int tx = ax;
            int tz = az;
            ax = bx;
            az = bz;
            bx = tx;
            bz = tz;
        }
        long h = hashCoordinates(ax * 31 + bx, az * 31 + bz, placementSalt() ^ 0x51B1D6EL);
        return Math.floorMod(h, 8) == 0;
    }

    public static boolean onLandBridge(double wx, double wz, CellularIslandDensityFunction.IslandLayout a, CellularIslandDensityFunction.IslandLayout b) {
        return distanceToSegment(wx, wz, a.centerX, a.centerZ, b.centerX, b.centerZ) <= LAND_BRIDGE_HALF_WIDTH;
    }

    public static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double lenSq = vx * vx + vz * vz;
        if (lenSq < 1.0) {
            return Math.sqrt((px - ax) * (px - ax) + (pz - az) * (pz - az));
        }
        double t = ((px - ax) * vx + (pz - az) * vz) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = px - (ax + t * vx);
        double ez = pz - (az + t * vz);
        return Math.sqrt(ex * ex + ez * ez);
    }

    public static boolean segmentNearAabb(double ax, double az, double bx, double bz,
            int minX, int minZ, int maxX, int maxZ, double pad) {
        for (int i = 0; i <= 8; i++) {
            double t = i / 8.0;
            double px = ax + (bx - ax) * t;
            double pz = az + (bz - az) * t;
            double cx = Math.max(minX, Math.min(maxX, px));
            double cz = Math.max(minZ, Math.min(maxZ, pz));
            if ((px - cx) * (px - cx) + (pz - cz) * (pz - cz) <= pad * pad) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBowlArchetype(int archetype) {
        return archetype == 1 || archetype == 4 || archetype == 5;
    }

    public static boolean isLargeLand(CellularIslandDensityFunction.IslandLayout layout) {
        return layout != null && !isBowlArchetype(layout.archetype) && (layout.mega || layout.baseRadius >= 90);
    }

    public static boolean hasSatellites(CellularIslandDensityFunction.IslandLayout layout) {
        return isLargeLand(layout);
    }

    public static double layoutSkipRadius(CellularIslandDensityFunction.IslandLayout layout) {
        double shape = layout.baseRadius * Math.sqrt(MAX_SHAPE_SCALE_SQ);
        double reach = shape;
        if (hasSatellites(layout)) {
            reach = Math.max(reach, layout.baseRadius * 1.40 + 36.0);
        }
        if (layout.islets.length > 0) {
            reach = Math.max(reach, shape + ISLET_REACH);
        }
        return reach;
    }

    public static int maxHorizontalReach(int maxRad) {
        return (int) (maxRad * MEGA_RADIUS_SCALE * Math.sqrt(MAX_SHAPE_SCALE_SQ)) + 96 + (int) WARP_SLACK;
    }

    public static void rememberFootprint(int x, int z, boolean onFootprint) {
        long[] cache = FOOTPRINT_CACHE.get();
        cache[0] = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt() ^ packedXZ(x, z);
        cache[1] = onFootprint ? 1L : 0L;
    }

    public static boolean columnOnFootprintCached(int x, int z, double wx, double wz, CellularIslandDensityFunction.IslandLayout[] nearby, int nearbyCount) {
        long key = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt() ^ packedXZ(x, z);
        long[] cache = FOOTPRINT_CACHE.get();
        if (cache[0] == key) {
            return cache[1] != 0L;
        }
        boolean on = false;
        for (int i = 0; i < nearbyCount; i++) {
            if (columnOnIslandOrExtra(nearby[i], wx, wz)) {
                on = true;
                break;
            }
        }
        cache[0] = key;
        cache[1] = on ? 1L : 0L;
        return on;
    }

    public static long packedXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static final class WarpedPoint {
        public final double wx;
        public final double wz;
        public final int cellX;
        public final int cellZ;

        public WarpedPoint(double wx, double wz, int cellX, int cellZ) {
            this.wx = wx;
            this.wz = wz;
            this.cellX = cellX;
            this.cellZ = cellZ;
        }
    }

    /** Single-entry cache without a boxed key. */
    public static final class LayoutSlot {
        public long key = Long.MIN_VALUE;
        public CellularIslandDensityFunction.IslandLayout layout;
    }

    private static final class WarpCache {
        long key = Long.MIN_VALUE;
        WarpedPoint point;
    }

}
