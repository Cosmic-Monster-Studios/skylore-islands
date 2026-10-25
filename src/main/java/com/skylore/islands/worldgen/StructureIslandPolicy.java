package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

import java.util.List;

/**
 * Size class and placement rules for structures on sky islands.
 * Small/medium structures may sit on any island. Huge footprints only generate on mega continents.
 * Open void is only for structures in {@code #skylore_islands:void_craft} (empty by default)
 * and crash-island paste. Dungeons, airships, and boats sit on islands.
 */
public final class StructureIslandPolicy {
    private StructureIslandPolicy() {}

    /** Jigsaw max_distance_from_center at or above this is treated as huge even without a name match. */
    public static final int HUGE_JIGSAW_DISTANCE = 112;

    /** Airships / boats are pinned around this Y in open void. */
    public static final int AIRBORNE_START_Y = 170;

    /** Void stubs below this are treated as vanilla-height stragglers, not flying craft. */
    public static final int AIRBORNE_MIN_Y = 140;

    /** Open-void blocks this far below the airship band are still part of a flying hull. */
    public static final int VOID_HULL_MIN_Y = 110;

    /** How far below island altitude a ground/buried piece may sit (foundations, mines, bowls). */
    public static final int SURFACE_SLACK = 48;

    /** Extra horizontal reach past the island rim for overhanging wings / eaves. */
    public static final int OVERHANG_BLOCKS = 160;

    public static final TagKey<Structure> VOID_CRAFT_TAG = TagKey.create(
            Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath("skylore_islands", "void_craft"));
    public static final TagKey<Structure> MEGA_ONLY_TAG = TagKey.create(
            Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath("skylore_islands", "mega_only"));

    private static final ThreadLocal<Boolean> ALLOW_VOID_PLACEMENT = ThreadLocal.withInitial(() -> false);
    /** Set by jigsaw adapter when a void-craft stub is produced; consumed by StructureMixin. */
    private static final ThreadLocal<Boolean> VOID_CRAFT_STUB = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<IslandLayout.LayoutSlot> NEAR_ISLAND_CACHE = ThreadLocal.withInitial(IslandLayout.LayoutSlot::new);

    /** Overall box of the structure start currently being placed on this thread, or null. */
    private static final ThreadLocal<BoundingBox> PLACING = new ThreadLocal<>();

    public static void beginStructurePlacement(BoundingBox box) {
        PLACING.set(box);
    }

    public static void endStructurePlacement() {
        PLACING.remove();
    }

    public static BoundingBox activePlacementBox() {
        return PLACING.get();
    }

    public static void allowVoidPlacement(boolean on) {
        ALLOW_VOID_PLACEMENT.set(on);
    }

    public static void markVoidCraftStub(boolean on) {
        VOID_CRAFT_STUB.set(on);
    }

    public static void clearVoidCraftStub() {
        VOID_CRAFT_STUB.remove();
    }

    public static boolean isAirbornePool(String poolName) {
        return contains(poolName, "airship", "blimp", "corsair", "sky_village", "flying", "zeppelin",
                "air_balloon", "sky_structure", "skyship", "air_ship", "skyvillage", "castle_in_the_sky", "balloon");
    }

    /** Watercraft that may float in open void instead of sitting on land. Not vanilla shipwrecks (those stay in bowls). */
    public static boolean isVoidCraftPool(String poolName) {
        return contains(poolName, "boat", "raft", "barge", "galleon", "schooner", "skiff", "rowboat");
    }

    public static boolean isVoidTolerantPool(String poolName) {
        return isAirbornePool(poolName) || isVoidCraftPool(poolName);
    }

    public static boolean isHugeJigsaw(String poolName, int maxDistanceFromCenter) {
        if (isVoidTolerantPool(poolName)) {
            return false;
        }
        if (maxDistanceFromCenter >= HUGE_JIGSAW_DISTANCE) {
            return true;
        }
        return isHugePoolName(poolName);
    }

    public static boolean isHugePoolName(String poolName) {
        if (poolName == null || poolName.isEmpty()) {
            return false;
        }
        if (poolName.startsWith("cataclysm:") || poolName.contains(":cataclysm/") || poolName.contains("cataclysm:")) {
            return true;
        }
        return contains(poolName,
                "mechanical_nest",
                "heavenly_challenger",
                "heavenly_conqueror",
                "heavenly_rider",
                "aviary",
                "mining_complex",
                "mining_system",
                "scorched_mines",
                "keep_kayra",
                "thornborn",
                "wailing_halls",
                "shiraz",
                "cerene",
                "illager_fort",
                "plague_asylum",
                "asylum",
                "foundry",
                "mansion",
                "woodland",
                "citadel",
                "acropolis",
                "colosseum",
                "coliseum",
                "ancient_factory",
                "ancient_city",
                "betterstronghold",
                "better_stronghold",
                "burning_arena",
                "bastion",
                "sunken_city");
    }

    public static boolean isMegaIslandAt(int blockX, int blockZ) {
        CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.layoutContaining(blockX, blockZ);
        return layout != null && layout.mega;
    }

    /** Huge ground structures skip this chunk unless it sits on a mega continent. */
    public static boolean rejectHugeHere(String poolName, int maxDistanceFromCenter, int blockX, int blockZ) {
        return isHugeJigsaw(poolName, maxDistanceFromCenter) && !isMegaIslandAt(blockX, blockZ);
    }

    public static boolean rejectHugeHere(String poolName, int maxDistanceFromCenter, int blockX, int blockZ,
            Structure structure, RegistryAccess access) {
        if (isMegaOnly(structure, access) && !isMegaIslandAt(blockX, blockZ)) {
            return true;
        }
        return rejectHugeHere(poolName, maxDistanceFromCenter, blockX, blockZ);
    }

    public static boolean isVoidCraft(Structure structure, RegistryAccess access) {
        return holderIn(structure, access, VOID_CRAFT_TAG);
    }

    public static boolean isMegaOnly(Structure structure, RegistryAccess access) {
        return holderIn(structure, access, MEGA_ONLY_TAG);
    }

    /** Tag only. Name heuristics must not send airships/boats into open void. */
    public static boolean isVoidTolerant(Structure structure, RegistryAccess access, String poolName) {
        return isVoidCraft(structure, access);
    }

    public static boolean isVoidTolerantId(String id) {
        return isVoidTolerantPool(id);
    }

    private static String structureId(Structure structure, RegistryAccess access) {
        if (structure == null || access == null) {
            return "";
        }
        Registry<Structure> registry = access.registryOrThrow(Registries.STRUCTURE);
        return registry.getResourceKey(structure)
                .map(key -> key.location().toString().toLowerCase())
                .orElse("");
    }

    private static boolean holderIn(Structure structure, RegistryAccess access, TagKey<Structure> tag) {
        if (structure == null || access == null) {
            return false;
        }
        Registry<Structure> registry = access.registryOrThrow(Registries.STRUCTURE);
        return registry.getResourceKey(structure)
                .flatMap(registry::getHolder)
                .map(holder -> holder.is(tag))
                .orElse(false);
    }

    /**
     * Whether a generation stub may exist here. Ground structures must be on an island
     * (surface, buried in stone, or in a water bowl). Open void is only for tagged void craft.
     */
    public static boolean allowsGenerationStub(int x, int y, int z, int surfaceY) {
        return allowsGenerationStub(x, y, z, surfaceY, null, null);
    }

    public static boolean allowsGenerationStub(int x, int y, int z, int surfaceY,
            Structure structure, RegistryAccess access) {
        CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.layoutContaining(x, z);
        if (layout == null) {
            // Fail closed: high Y alone is not enough (mods often use absolute Y ~150+).
            boolean voidCraft = isVoidCraft(structure, access) || Boolean.TRUE.equals(VOID_CRAFT_STUB.get());
            return y >= AIRBORNE_MIN_Y && voidCraft;
        }
        if (y >= surfaceY - SURFACE_SLACK || y >= layout.altitude - SURFACE_SLACK) {
            return true;
        }
        if (CellularIslandDensityFunction.isInsideIslandStone(x, y, z)) {
            return true;
        }
        CellularIslandDensityFunction.WaterColumn water = CellularIslandDensityFunction.waterColumnAt(x, z);
        return water != null && y >= water.seabedY - 4;
    }

    /**
     * Block-level filter: keep on-island buildings and nest overhangs, strip lamp posts
     * dangling under the plug and vanilla-Y fragments in open void.
     * Does not use a 2D column-only allow (that sliced Mechanical Nest wings).
     * Open-void hull blocks are only allowed while a void-craft / crash-island paste is active.
     */
    public static boolean allowsPlacement(int x, int y, int z) {
        if (Boolean.TRUE.equals(ALLOW_VOID_PLACEMENT.get())) {
            return true;
        }

        CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.layoutContaining(x, z);
        if (layout != null) {
            if (y >= layout.altitude - SURFACE_SLACK) {
                return true;
            }
            if (CellularIslandDensityFunction.isInsideIslandStone(x, y, z)) {
                return true;
            }
            CellularIslandDensityFunction.WaterColumn water = CellularIslandDensityFunction.waterColumnAt(x, z);
            return water != null && y >= water.seabedY - 4 && y <= water.waterLevel + 16;
        }

        // Open-void hulls only while a tagged void-craft stub or crash-island paste is active.
        if (y >= VOID_HULL_MIN_Y
                && (Boolean.TRUE.equals(VOID_CRAFT_STUB.get()) || Boolean.TRUE.equals(ALLOW_VOID_PLACEMENT.get()))) {
            return true;
        }

        // Near-island overhangs (Mechanical Nest wings, eaves) below the hull band.
        CellularIslandDensityFunction.IslandLayout nearby = nearbyIsland(x, z);
        if (nearby == null) {
            return false;
        }
        int dx = x - nearby.centerX;
        int dz = z - nearby.centerZ;
        int maxDist = nearby.baseRadius + OVERHANG_BLOCKS;
        if ((long) dx * dx + (long) dz * dz > (long) maxDist * maxDist) {
            return false;
        }
        return y >= nearby.altitude - SURFACE_SLACK;
    }

    /** Largest share of a structure's footprint that may sit over open void. */
    public static final double MAX_VOID_FOOTPRINT = 0.05;
    /** Footprint sample spacing in blocks. */
    private static final int FOOTPRINT_STEP = 6;

    /** Real ground height at a column; the world floor for open void. */
    @FunctionalInterface
    public interface SurfaceSampler {
        int surfaceAt(int x, int z);
    }

    /**
     * Share of the pieces' footprint columns with no ground under them. Pieces are sampled, not the
     * overall box, so L-shaped and spread-out structures are not penalised for empty box corners.
     * Uses the generated surface rather than the island outline: the outline reaches past where the
     * rim terrain thins out, so a structure could hang over sky while still "inside" it.
     */
    public static double voidFootprintShare(List<StructurePiece> pieces, SurfaceSampler surface, int floorY) {
        int samples = 0;
        int voids = 0;
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            for (int x = box.minX(); x <= box.maxX(); x += FOOTPRINT_STEP) {
                for (int z = box.minZ(); z <= box.maxZ(); z += FOOTPRINT_STEP) {
                    samples++;
                    if (surface.surfaceAt(x, z) <= floorY) {
                        voids++;
                    }
                }
            }
            // Always include the far corner so thin pieces along an edge are seen.
            samples++;
            if (surface.surfaceAt(box.maxX(), box.maxZ()) <= floorY) {
                voids++;
            }
        }
        return samples == 0 ? 0.0 : (double) voids / samples;
    }

    /** Sink at most this far when settling a floating structure onto the ground. */
    public static final int MAX_SETTLE = 40;

    /**
     * How far to lower a rigid jigsaw structure so its base meets the ground instead of floating over
     * the low side of uneven terrain. Our jigsaw adapter anchors at the highest nearby surface and the
     * beard fill is off (it grew legs into the void), so wide rigid builds hovered. Settles onto the
     * lower quartile of the ground under the pieces; returns 0 when nothing should move.
     */
    public static int settleDepth(List<StructurePiece> pieces, SurfaceSampler surface, int floorY) {
        int minY = Integer.MAX_VALUE;
        List<Integer> heights = new java.util.ArrayList<>();
        for (StructurePiece piece : pieces) {
            if (!(piece instanceof PoolElementStructurePiece pool)
                    || pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                // Terrain-following pieces (village roads and houses) already sit on the ground.
                return 0;
            }
            BoundingBox box = piece.getBoundingBox();
            minY = Math.min(minY, box.minY());
            for (int x = box.minX(); x <= box.maxX(); x += FOOTPRINT_STEP) {
                for (int z = box.minZ(); z <= box.maxZ(); z += FOOTPRINT_STEP) {
                    int y = surface.surfaceAt(x, z);
                    if (y > floorY) {
                        heights.add(y);
                    }
                }
            }
        }
        if (heights.isEmpty()) {
            return 0;
        }
        java.util.Collections.sort(heights);
        int ground = heights.get(heights.size() / 4);
        int gap = minY - ground;
        return gap > 2 ? Math.min(gap, MAX_SETTLE) : 0;
    }

    private static CellularIslandDensityFunction.IslandLayout nearbyIsland(int x, int z) {
        long key = CellularIslandDensityFunction.worldSeed() ^ CellularIslandDensityFunction.layoutSalt()
                ^ ChunkPos.asLong(x >> 4, z >> 4);
        IslandLayout.LayoutSlot cache = NEAR_ISLAND_CACHE.get();
        if (cache.key == key) {
            return cache.layout;
        }
        CellularIslandDensityFunction.IslandLayout found = scanAdjacentCells(x, z);
        cache.key = key;
        cache.layout = found;
        return found;
    }

    private static CellularIslandDensityFunction.IslandLayout scanAdjacentCells(int x, int z) {
        int cellSize = SkyloreConfig.cellSize();
        int[] cell = CellularIslandDensityFunction.cellOfWarped(x, z, cellSize);
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();
        CellularIslandDensityFunction.IslandLayout best = null;
        double bestDist = Double.MAX_VALUE;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.layoutForCell(
                        cell[0] + ox, cell[1] + oz, cellSize, minAlt, maxAlt, minRad, maxRad);
                if (layout == null) {
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

    private static boolean contains(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
