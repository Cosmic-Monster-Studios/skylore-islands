package com.skylore.islands.worldgen;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.api.IslandsApi;
import com.skylore.islands.block.WreckContainerBlock;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.init.SkyloreBlocks;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Buried Sky Caches on islets and satellites. One in {@link SkyloreConfig#skyCacheChance()} small
 * islands gets one, under its warped centre, with a lantern post on the surface above.
 * The chunk holding the unwarped centre places it, so each cache is placed exactly once.
 */
public final class SkyCaches {

    public static final ResourceKey<LootTable> SKY_CACHE_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "chests/sky_cache"));

    private static final long CACHE_SALT = 0x5C1CAC4EL;
    private static final int KIND_ISLET = 1;
    private static final int KIND_SATELLITE = 2;
    /** Cache sits this far under the island surface. */
    private static final int BURY_DEPTH = 3;
    /** Unwarp must land within this many blocks of the warped centre (squared, x1000). */
    private static final long MAX_UNWARP_ERROR = 4_000L;
    private static final int SCAN_ABOVE = 30;
    private static final int SCAN_BELOW = 10;
    private static final Direction[] FACINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private SkyCaches() {
    }

    public static boolean hasCache(CellularIslandDensityFunction.IslandLayout layout, int kind, int index, int chance) {
        long h = IslandLayout.hashCoordinates(index, kind, layout.cellHash ^ CACHE_SALT);
        return Math.floorMod(h, chance) == 0;
    }

    /** Runs after biome decoration of an ISLAND chunk; layout salt must be bound for this level. */
    public static void decorate(WorldGenLevel level, ChunkAccess chunk) {
        int chance = SkyloreConfig.skyCacheChance();
        if (chance <= 0 || !IslandsApi.hasIslands(level.getLevel())) {
            return;
        }
        if (CellularIslandDensityFunction.usingNetherLayout() && !SkyloreConfig.skyCacheInNether()) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int cellSize = SkyloreConfig.cellSize();
        int x0 = pos.getMinBlockX();
        int z0 = pos.getMinBlockZ();

        double minWx = Double.MAX_VALUE;
        double minWz = Double.MAX_VALUE;
        double maxWx = -Double.MAX_VALUE;
        double maxWz = -Double.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            IslandLayout.WarpedPoint p = IslandLayout.warpedPoint(x0 + ((corner & 1) * 15), z0 + ((corner >> 1) * 15), cellSize);
            minWx = Math.min(minWx, p.wx);
            minWz = Math.min(minWz, p.wz);
            maxWx = Math.max(maxWx, p.wx);
            maxWz = Math.max(maxWz, p.wz);
        }
        // The warp bends a little across 16 blocks; pad so edge centres still reach the unwarp test.
        minWx -= 4.0;
        minWz -= 4.0;
        maxWx += 4.0;
        maxWz += 4.0;

        IslandLayout.WarpedPoint center = IslandLayout.warpedPoint(x0 + 8, z0 + 8, cellSize);
        CellularIslandDensityFunction.IslandLayout[] nearby = new CellularIslandDensityFunction.IslandLayout[9];
        int n = IslandLayout.collectNearbyLayouts(nearby, center.cellX, center.cellZ, cellSize,
                SkyloreConfig.minIslandAltitude(), SkyloreConfig.maxIslandAltitude(),
                SkyloreConfig.minIslandRadius(), SkyloreConfig.maxIslandRadius());
        for (int i = 0; i < n; i++) {
            CellularIslandDensityFunction.IslandLayout layout = nearby[i];
            double[] islets = layout.islets;
            for (int o = 0, index = 0; o < islets.length; o += IslandLayout.ISLET_STRIDE, index++) {
                if (inBox(islets[o], islets[o + 1], minWx, minWz, maxWx, maxWz) && hasCache(layout, KIND_ISLET, index, chance)) {
                    // Islet tops are a 5-block mound over altitude + yOffset.
                    tryPlace(level, chunk, layout, islets[o], islets[o + 1], layout.altitude + (int) islets[o + 3] + 5, KIND_ISLET, index);
                }
            }
            double[] sats = layout.satellites;
            for (int o = 0, index = 0; o < sats.length; o += IslandLayout.SATELLITE_STRIDE, index++) {
                if (inBox(sats[o], sats[o + 1], minWx, minWz, maxWx, maxWz) && hasCache(layout, KIND_SATELLITE, index, chance)) {
                    tryPlace(level, chunk, layout, sats[o], sats[o + 1], layout.altitude + 10, KIND_SATELLITE, index);
                }
            }
        }
    }

    private static boolean inBox(double wx, double wz, double minWx, double minWz, double maxWx, double maxWz) {
        return wx >= minWx && wx <= maxWx && wz >= minWz && wz <= maxWz;
    }

    private static void tryPlace(WorldGenLevel level, ChunkAccess chunk, CellularIslandDensityFunction.IslandLayout layout,
            double wx, double wz, int expectedTop, int kind, int index) {
        long[] column = IslandLayout.unwarp(wx, wz);
        if (column[2] > MAX_UNWARP_ERROR) {
            return;
        }
        int x = (int) column[0];
        int z = (int) column[1];
        ChunkPos pos = chunk.getPos();
        if ((x >> 4) != pos.x || (z >> 4) != pos.z) {
            return;
        }
        int groundY = findGround(chunk, x, z, expectedTop);
        if (groundY == Integer.MIN_VALUE) {
            return;
        }
        BlockPos cachePos = new BlockPos(x, groundY - BURY_DEPTH, z);
        if (chunk.getBlockState(cachePos.below()).isAir()) {
            cachePos = cachePos.above();
        }

        long seed = CellularIslandDensityFunction.worldSeed() ^ layout.cellHash ^ ((long) kind << 40) ^ ((long) index * 0x9E3779B97F4A7C15L);
        RandomSource random = RandomSource.create(seed);
        BlockState cache = SkyloreBlocks.SKY_CACHE.get().defaultBlockState()
                .setValue(WreckContainerBlock.FACING, FACINGS[random.nextInt(FACINGS.length)]);
        level.setBlock(cachePos, cache, 2);
        RandomizableContainer.setBlockEntityLootTable(level, random, cachePos, SKY_CACHE_LOOT);

        BlockPos post = new BlockPos(x, groundY + 1, z);
        if (chunk.getBlockState(post).canBeReplaced() && chunk.getBlockState(post.above()).canBeReplaced()) {
            level.setBlock(post, Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), 2);
            level.setBlock(post.above(), Blocks.LANTERN.defaultBlockState(), 2);
        }
    }

    /**
     * Topmost ground block near the expected surface, skipping plants, logs and leaves. Biome
     * surfaces and terrain noise can lift the real top well above the density estimate, so
     * search high; scanning down means the first hit has no ground above it.
     */
    private static int findGround(ChunkAccess chunk, int x, int z, int expectedTop) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = expectedTop + SCAN_ABOVE; y >= expectedTop - SCAN_BELOW; y--) {
            BlockState state = chunk.getBlockState(cursor.set(x, y, z));
            if (isGround(state)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isGround(BlockState state) {
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.NYLIUM)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE);
    }
}
