package com.skylore.islands.worldgen;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.block.BlackBoxBlock;
import com.skylore.islands.block.WreckContainerBlock;
import com.skylore.islands.block.WreckContainerBlockEntity;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.init.SkyloreBlocks;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Blasts an impact crater into a forested island and leaves a wrecked ship plus a black box.
 * Runs once per player on the server thread; chunks are force-loaded only while building.
 */
public final class CrashSiteBuilder {
    public static final TagKey<Biome> CRASH_SITE_BIOMES = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "crash_site_biomes"));
    public static final ResourceKey<LootTable> BLACK_BOX_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "chests/black_box"));
    public static final ResourceKey<LootTable> CARGO_POD_LOOT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "chests/cargo_pod"));

    private static final int DEPTH = 7;
    private static final int TREE_SCAN = 24;
    private static final int CLEAR_ABOVE = 24;
    private static final int CANDIDATES = 9;
    private static final int PODS = 3;
    private static final int POD_MIN_DIST = 22;
    private static final int POD_MAX_DIST = 36;
    /** Trees out to this many crater radii get knocked flat. */
    private static final double BLAST_RING = 2.2;

    private CrashSiteBuilder() {}

    /** Column positions on this island whose biome is in {@link #CRASH_SITE_BIOMES}, best first. No chunk loads. */
    public static List<int[]> candidateSites(ServerLevel level, CellularIslandDensityFunction.IslandLayout layout, long seed) {
        int radius = SkyloreConfig.crashCraterRadius();
        RandomSource random = RandomSource.create(seed);
        double ring = layout.baseRadius * 0.45;
        double phase = random.nextDouble() * Math.PI * 2.0;
        List<int[]> sites = new ArrayList<>();
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = phase + i * (Math.PI * 2.0 / CANDIDATES);
            int x = layout.centerX + (int) Math.round(Math.cos(angle) * ring);
            int z = layout.centerZ + (int) Math.round(Math.sin(angle) * ring);
            if (!fitsOnIsland(layout, x, z, radius) || CellularIslandDensityFunction.waterColumnAt(x, z) != null) {
                continue;
            }
            if (!isTreeBiome(level, x, layout.altitude + 8, z)) {
                continue;
            }
            sites.add(new int[]{x, z});
        }
        return sites;
    }

    /**
     * Loads the area, confirms real trees, and builds the crater.
     * Returns the player stand position, or null if this spot was rejected (nothing is changed then).
     */
    public static BlockPos tryBuild(ServerLevel level, int x, int z, long seed) {
        int radius = SkyloreConfig.crashCraterRadius();
        int reach = Math.max(Math.max(TREE_SCAN, (int) Math.ceil(radius * (BLAST_RING + 0.3))), POD_MAX_DIST + 6) + 2;
        Set<Long> forced = new HashSet<>();
        try {
            for (int cx = (x - reach) >> 4; cx <= (x + reach) >> 4; cx++) {
                for (int cz = (z - reach) >> 4; cz <= (z + reach) >> 4; cz++) {
                    long packed = ChunkPos.asLong(cx, cz);
                    if (!level.getForcedChunks().contains(packed)) {
                        level.setChunkForced(cx, cz, true);
                        forced.add(packed);
                    }
                    level.getChunk(cx, cz);
                }
            }
            int ground = groundY(level, x, z);
            BlockState surface = level.getBlockState(new BlockPos(x, ground, z));
            if (!surface.is(BlockTags.DIRT) || !surface.getFluidState().isEmpty()) {
                return null;
            }
            if (countLogs(level, x, ground, z) < SkyloreConfig.crashMinTreeLogs()) {
                return null;
            }
            if (touchesStructure(level, x, ground, z, radius)) {
                return null;
            }
            return build(level, x, ground, z, radius, RandomSource.create(seed));
        } finally {
            for (long packed : forced) {
                level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false);
            }
        }
    }

    /** Player position at a built crash site, facing-ready; falls back to the stored point. */
    public static Vec3 standAt(ServerLevel level, BlockPos stored) {
        BlockPos.MutableBlockPos pos = stored.mutable();
        for (int i = 0; i < 32 && !canStand(level, pos); i++) {
            pos.move(Direction.UP);
        }
        BlockPos at = canStand(level, pos) ? pos : stored;
        return new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static boolean canStand(ServerLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.isSolid() && below.getFluidState().isEmpty()
                && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }

    /** True if any structure piece sits in or around the crater footprint. */
    private static boolean touchesStructure(ServerLevel level, int x, int ground, int z, int radius) {
        int span = radius + 8;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -span; dx <= span; dx += 4) {
            for (int dz = -span; dz <= span; dz += 4) {
                for (int dy = -DEPTH - 4; dy <= 12; dy += 4) {
                    if (level.structureManager().getStructureWithPieceAt(pos.set(x + dx, ground + dy, z + dz),
                            structure -> true).isValid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Stand position for leases made by the old void crash island (cell center, mid altitude). */
    public static Vec3 legacyStand(ServerLevel level, int cellX, int cellZ) {
        int cellSize = SkyloreConfig.cellSize();
        int x = cellX * cellSize + cellSize / 2;
        int z = cellZ * cellSize + cellSize / 2;
        int y = (SkyloreConfig.minIslandAltitude() + SkyloreConfig.maxIslandAltitude()) / 2;
        Integer found = standOnColumn(level, x, z, y);
        return new Vec3(x + 0.5, found != null ? found : y + 1, z + 0.5);
    }

    private static BlockPos build(ServerLevel level, int cx, int sy, int cz, int radius, RandomSource random) {
        double rimPhase1 = random.nextDouble() * Math.PI * 2.0;
        double rimPhase2 = random.nextDouble() * Math.PI * 2.0;
        double standAngle = random.nextDouble() * Math.PI * 2.0;
        int span = (int) Math.ceil(radius * (BLAST_RING + 0.1));
        List<BlockPos> trunks = new ArrayList<>();
        int floorY = sy - DEPTH;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                double angle = Math.atan2(dz, dx);
                double rim = radius * (1.0 + 0.08 * Math.sin(3 * angle + rimPhase1) + 0.05 * Math.sin(7 * angle + rimPhase2));
                double d = Math.sqrt(dx * dx + dz * dz) / rim;
                if (d >= BLAST_RING) {
                    continue;
                }
                int gt = groundY(level, x, z);
                if (d < 1.0) {
                    carveColumn(level, pos, x, z, d, gt, sy, random);
                    continue;
                }
                if (level.getBlockState(pos.set(x, gt + 1, z)).is(BlockTags.LOGS)) {
                    trunks.add(new BlockPos(x, gt + 1, z));
                }
                if (d < 1.25) {
                    ejecta(level, pos, x, z, d, gt, random);
                } else if (d < 1.8) {
                    scorchGround(level, pos, x, z, d, gt, random);
                }
            }
        }

        for (BlockPos trunk : trunks) {
            fellTree(level, pos, trunk, cx, cz, radius, random);
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(level, pos.set(cx + dx, floorY, cz + dz), SkyloreBlocks.SKY_STONE.get().defaultBlockState());
            }
        }

        // Stand on the freshly cleared crater floor near the black box; never on unchecked terrain.
        int standX = cx + (int) Math.round(Math.cos(standAngle) * radius * 0.4);
        int standZ = cz + (int) Math.round(Math.sin(standAngle) * radius * 0.4);
        Direction toStand = Direction.fromYRot(Math.toDegrees(Math.atan2(standZ - cz, standX - cx)) - 90.0);
        BlockPos boxPos = new BlockPos(cx, floorY + 1, cz);
        set(level, boxPos, SkyloreBlocks.BLACK_BOX.get().defaultBlockState()
                .setValue(BlackBoxBlock.FACING, toStand)
                .setValue(BlackBoxBlock.SIGNAL, true));
        if (level.getBlockEntity(boxPos) instanceof WreckContainerBlockEntity box) {
            box.setLootTable(BLACK_BOX_LOOT, random.nextLong());
        }

        placeWreck(level, pos, cx, cz, sy, radius, standAngle, random);
        placeCargoPods(level, pos, cx, cz, random);

        BlockPos.MutableBlockPos stand = new BlockPos.MutableBlockPos(standX, groundY(level, standX, standZ) + 1, standZ);
        for (int y = 0; y < 3; y++) {
            set(level, pos.set(standX, stand.getY() + y, standZ), Blocks.AIR.defaultBlockState());
        }
        return stand.immutable();
    }

    private static void carveColumn(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, double d,
            int gt, int sy, RandomSource random) {
        int floor = Math.min(sy - (int) Math.round(DEPTH * (1.0 - d * d)), gt);
        int top = Math.max(gt, sy) + CLEAR_ABOVE;
        for (int y = floor + 1; y <= top; y++) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) {
                set(level, pos, Blocks.AIR.defaultBlockState());
            }
        }
        set(level, pos.set(x, floor, z), lining(d, random));
        set(level, pos.set(x, floor - 1, z), random.nextFloat() < 0.6F
                ? Blocks.BLACKSTONE.defaultBlockState()
                : Blocks.BASALT.defaultBlockState());
    }

    private static BlockState lining(double d, RandomSource random) {
        float roll = random.nextFloat();
        if (d < 0.3 && roll < 0.18F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (d > 0.78) {
            if (roll < 0.45F) {
                return Blocks.COARSE_DIRT.defaultBlockState();
            }
            return roll < 0.75F ? Blocks.TUFF.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        if (roll < 0.45F) {
            return Blocks.BASALT.defaultBlockState();
        }
        return roll < 0.8F ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.SMOOTH_BASALT.defaultBlockState();
    }

    private static void ejecta(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, double d, int gt, RandomSource random) {
        pos.set(x, gt, z);
        if (level.getBlockState(pos).is(BlockTags.DIRT)) {
            set(level, pos, random.nextFloat() < 0.7F ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
        }
        double chance = (1.25 - d) / 0.25 * 0.55;
        if (random.nextDouble() < chance) {
            pos.set(x, gt + 1, z);
            if (level.getBlockState(pos).canBeReplaced()) {
                set(level, pos, random.nextFloat() < 0.6F ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.TUFF.defaultBlockState());
            }
        }
    }

    /** Burnt ground ring: grass scorched to dirt, plants burnt off, the odd dead bush. */
    private static void scorchGround(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int z, double d, int gt, RandomSource random) {
        double strength = (1.8 - d) / 0.55;
        pos.set(x, gt, z);
        BlockState top = level.getBlockState(pos);
        if (top.is(Blocks.GRASS_BLOCK) || top.is(Blocks.PODZOL) || top.is(Blocks.MYCELIUM)) {
            if (random.nextDouble() < strength) {
                set(level, pos, random.nextFloat() < 0.6F ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState());
            }
        }
        pos.set(x, gt + 1, z);
        BlockState above = level.getBlockState(pos);
        if (!above.isAir() && above.canBeReplaced() && above.getFluidState().isEmpty()) {
            set(level, pos, Blocks.AIR.defaultBlockState());
        }
        if (random.nextFloat() < 0.04F && level.getBlockState(pos).isAir()
                && Blocks.DEAD_BUSH.defaultBlockState().canSurvive(level, pos)) {
            set(level, pos, Blocks.DEAD_BUSH.defaultBlockState());
        }
    }

    /** Knocks a tree flat, crown pointing away from the impact. Close trees are charred. */
    private static void fellTree(ServerLevel level, BlockPos.MutableBlockPos pos, BlockPos base, int cx, int cz,
            int radius, RandomSource random) {
        BlockState log = level.getBlockState(base);
        if (!log.is(BlockTags.LOGS)) {
            return;
        }
        int height = 0;
        while (height < 30 && level.getBlockState(pos.set(base.getX(), base.getY() + height, base.getZ())).is(BlockTags.LOGS)) {
            height++;
        }
        BlockState leaves = null;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = base.getY() + 1; y <= base.getY() + height + 3; y++) {
                    pos.set(base.getX() + dx, y, base.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    boolean branch = state.is(BlockTags.LOGS) && Math.abs(dx) <= 2 && Math.abs(dz) <= 2
                            && (dx == 0 && dz == 0 || y > base.getY() + 2);
                    if (state.is(BlockTags.LEAVES)) {
                        if (leaves == null) {
                            leaves = state;
                        }
                        set(level, pos, Blocks.AIR.defaultBlockState());
                    } else if (branch) {
                        set(level, pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        int dx = base.getX() - cx;
        int dz = base.getZ() - cz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean charred = dist < radius * 1.6;
        Direction away = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState stump = charred ? Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState() : log;
        set(level, base, stump);

        BlockState lying = (charred ? Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState() : log);
        if (lying.hasProperty(RotatedPillarBlock.AXIS)) {
            lying = lying.setValue(RotatedPillarBlock.AXIS, away.getAxis());
        }
        int length = Math.min(Math.max(2, height - 1), 7);
        int endX = base.getX();
        int endZ = base.getZ();
        int endY = base.getY();
        for (int i = 1; i <= length; i++) {
            int x = base.getX() + away.getStepX() * i;
            int z = base.getZ() + away.getStepZ() * i;
            int y = groundY(level, x, z) + 1;
            if (Math.abs(y - base.getY()) > 3) {
                break;
            }
            pos.set(x, y, z);
            if (!level.getBlockState(pos).canBeReplaced()) {
                break;
            }
            set(level, pos, lying);
            endX = x;
            endZ = z;
            endY = y;
        }
        if (!charred && leaves != null && leaves.hasProperty(LeavesBlock.PERSISTENT)) {
            BlockState crown = leaves.setValue(LeavesBlock.PERSISTENT, true);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    for (int oy = 0; oy <= 1; oy++) {
                        pos.set(endX + away.getStepX() + ox, endY + oy, endZ + away.getStepZ() + oz);
                        if (random.nextFloat() < 0.65F && level.getBlockState(pos).isAir()) {
                            set(level, pos, crown);
                        }
                    }
                }
            }
        }
    }

    /** Cargo pods thrown clear on impact, each half-buried in its own small dent. */
    private static void placeCargoPods(ServerLevel level, BlockPos.MutableBlockPos pos, int cx, int cz, RandomSource random) {
        double phase = random.nextDouble() * Math.PI * 2;
        int placed = 0;
        for (int attempt = 0; attempt < PODS * 4 && placed < PODS; attempt++) {
            double angle = phase + attempt * (Math.PI * 2 / (PODS * 4)) * 3.7;
            double dist = POD_MIN_DIST + random.nextDouble() * (POD_MAX_DIST - POD_MIN_DIST);
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);
            CellularIslandDensityFunction.IslandLayout island = CellularIslandDensityFunction.layoutContaining(x, z);
            if (island == null || !fitsOnIsland(island, x, z, 4) || CellularIslandDensityFunction.waterColumnAt(x, z) != null) {
                continue;
            }
            int ground = groundY(level, x, z);
            if (!level.getBlockState(pos.set(x, ground, z)).is(BlockTags.DIRT) || touchesStructure(level, x, ground, z, 3)) {
                continue;
            }
            buildPod(level, pos, x, ground, z, random);
            placed++;
        }
    }

    private static void buildPod(ServerLevel level, BlockPos.MutableBlockPos pos, int x, int ground, int z, RandomSource random) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz) / 3.0;
                if (d >= 1.0) {
                    continue;
                }
                int floor = ground - (int) Math.round(2 * (1.0 - d * d));
                for (int y = floor + 1; y <= ground + 12; y++) {
                    pos.set(x + dx, y, z + dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir() && !state.is(BlockTags.LOGS)) {
                        set(level, pos, Blocks.AIR.defaultBlockState());
                    }
                }
                float roll = random.nextFloat();
                set(level, pos.set(x + dx, floor, z + dz), roll < 0.5F ? Blocks.COARSE_DIRT.defaultBlockState()
                        : roll < 0.8F ? Blocks.GRAVEL.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState());
            }
        }
        int floor = ground - 2;
        BlockPos podPos = new BlockPos(x, floor + 1, z);
        Direction facing = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        set(level, podPos, SkyloreBlocks.CARGO_POD.get().defaultBlockState().setValue(WreckContainerBlock.FACING, facing));
        if (level.getBlockEntity(podPos) instanceof WreckContainerBlockEntity pod) {
            pod.setLootTable(CARGO_POD_LOOT, random.nextLong());
        }
        BlockState smolder = SkyloreBlocks.SMOLDERING_HULL.get().defaultBlockState();
        for (int i = 0; i < 2 + random.nextInt(2); i++) {
            Direction side = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            BlockPos at = podPos.relative(side).relative(side.getClockWise(), random.nextInt(3) - 1);
            set(level, at, smolder);
        }
    }

    /** A few hull ribs around the rim and scraps on the slope. Most of the ship is gone. */
    private static void placeWreck(ServerLevel level, BlockPos.MutableBlockPos pos, int cx, int cz, int sy, int radius,
            double standAngle, RandomSource random) {
        BlockState cold = SkyloreBlocks.SHIP_HULL.get().defaultBlockState();
        BlockState hot = SkyloreBlocks.SMOLDERING_HULL.get().defaultBlockState();
        BlockState frame = SkyloreBlocks.SHIP_HULL_FRAME.get().defaultBlockState();
        int ribs = 8 + random.nextInt(3);
        for (int i = 0; i < ribs; i++) {
            double angle = standAngle + Math.PI / 6 + i * ((Math.PI * 2 - Math.PI / 3) / ribs) + (random.nextDouble() - 0.5) * 0.2;
            int x = cx + (int) Math.round(Math.cos(angle) * radius * 0.86);
            int z = cz + (int) Math.round(Math.sin(angle) * radius * 0.86);
            int base = groundY(level, x, z) + 1;
            int height = 2 + random.nextInt(3);
            BlockState rib = random.nextFloat() < 0.3F ? frame : random.nextFloat() < 0.7F ? hot : cold;
            for (int h = 0; h < height; h++) {
                set(level, pos.set(x, base + h, z), rib);
            }
            if (rib != frame && random.nextBoolean()) {
                int ix = x - (int) Math.signum(Math.round(Math.cos(angle)));
                int iz = z - (int) Math.signum(Math.round(Math.sin(angle)));
                set(level, pos.set(ix, base + height - 1, iz), rib);
            }
        }
        int scraps = 5 + random.nextInt(3);
        for (int i = 0; i < scraps; i++) {
            double angle = standAngle + 0.6 + random.nextDouble() * (Math.PI * 2 - 1.2);
            double dist = radius * (0.35 + random.nextDouble() * 0.4);
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);
            set(level, pos.set(x, groundY(level, x, z) + 1, z), random.nextFloat() < 0.6F ? hot : cold);
        }
        // Glowing wreckage half-sunk in the crater floor.
        int embers = 4 + random.nextInt(3);
        for (int i = 0; i < embers; i++) {
            double angle = standAngle + 0.6 + random.nextDouble() * (Math.PI * 2 - 1.2);
            double dist = 2.5 + random.nextDouble() * radius * 0.35;
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);
            set(level, pos.set(x, groundY(level, x, z), z), hot);
        }
    }

    /** The whole footprint must be dry land of this one island; no ocean, crater-lake or swamp islands. */
    private static boolean fitsOnIsland(CellularIslandDensityFunction.IslandLayout island, int x, int z, int radius) {
        if (IslandLayout.isBowlArchetype(island.archetype)) {
            return false;
        }
        int edge = radius + 6;
        for (int i = 0; i < 12; i++) {
            double angle = i * (Math.PI / 6);
            if (!onIsland(island, x + (int) Math.round(Math.cos(angle) * edge), z + (int) Math.round(Math.sin(angle) * edge))) {
                return false;
            }
        }
        return onIsland(island, x, z);
    }

    private static boolean onIsland(CellularIslandDensityFunction.IslandLayout island, int x, int z) {
        CellularIslandDensityFunction.IslandLayout here = CellularIslandDensityFunction.layoutContaining(x, z);
        return here != null && here.cellX == island.cellX && here.cellZ == island.cellZ;
    }

    private static boolean isTreeBiome(ServerLevel level, int x, int y, int z) {
        Holder<Biome> biome = level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z),
                level.getChunkSource().randomState().sampler());
        return biome.is(CRASH_SITE_BIOMES);
    }

    private static int countLogs(ServerLevel level, int x, int ground, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int logs = 0;
        for (int dx = -TREE_SCAN; dx <= TREE_SCAN; dx++) {
            for (int dz = -TREE_SCAN; dz <= TREE_SCAN; dz++) {
                for (int y = ground - 2; y <= ground + 18; y++) {
                    if (level.getBlockState(pos.set(x + dx, y, z + dz)).is(BlockTags.LOGS)) {
                        logs++;
                    }
                }
            }
        }
        return logs;
    }

    /** Top block of the actual ground: skips trees, plants and our own loose wreckage. */
    private static int groundY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        for (int i = 0; i < 40 && pos.getY() > level.getMinBuildHeight(); i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !state.canBeReplaced()) {
                return pos.getY();
            }
            pos.move(Direction.DOWN);
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static Integer standOnColumn(ServerLevel level, int x, int z, int guessY) {
        int y = Math.min(level.getMaxBuildHeight() - 2, guessY + 64);
        int bottom = Math.max(level.getMinBuildHeight() + 1, guessY - 80);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (y > bottom) {
            pos.set(x, y, z);
            BlockState below = level.getBlockState(pos.below());
            if (below.isSolid() && below.getFluidState().isEmpty() && !below.is(BlockTags.LEAVES)
                    && level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
                return y;
            }
            y--;
        }
        return null;
    }
}
