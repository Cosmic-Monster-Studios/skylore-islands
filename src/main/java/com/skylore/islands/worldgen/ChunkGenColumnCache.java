package com.skylore.islands.worldgen;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;

/**
 * During noise fill, the same 16x16 columns are sampled thousands of times per chunk.
 * Precompute layout, water, and interior flags once per chunk on first touch.
 */
public final class ChunkGenColumnCache {
    private static final int PAD = 18;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private ChunkGenColumnCache() {}

    public static void ensureFor(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        State state = STATE.get();
        if (state.prepared && state.chunkX == chunkX && state.chunkZ == chunkZ
                && state.layoutSalt == CellularIslandDensityFunction.layoutSalt()
                && state.worldSeed == CellularIslandDensityFunction.worldSeed()) {
            return;
        }
        prepare(chunkX, chunkZ);
    }

    public static boolean isActiveFor(int blockX, int blockZ) {
        State state = STATE.get();
        return state.active && state.prepared
                && state.layoutSalt == CellularIslandDensityFunction.layoutSalt()
                && state.worldSeed == CellularIslandDensityFunction.worldSeed()
                && state.chunkX == (blockX >> 4) && state.chunkZ == (blockZ >> 4);
    }

    public static CellularIslandDensityFunction.IslandLayout layoutAt(int blockX, int blockZ) {
        return STATE.get().layouts[index(blockX, blockZ)];
    }

    public static CellularIslandDensityFunction.WaterColumn waterAt(int blockX, int blockZ) {
        return STATE.get().water[index(blockX, blockZ)];
    }

    public static boolean waterInteriorAt(int blockX, int blockZ) {
        return STATE.get().waterInterior[index(blockX, blockZ)];
    }

    /** {@link #basinFill} results. */
    public static final int FILL_NONE = 0;
    public static final int FILL_FLOOR = 1;
    public static final int FILL_FLUID = 2;
    /** final_density is interpolated, so the stone floor can sit a little below the exact seabed. */
    private static final int FLOOR_PATCH_DEPTH = 4;

    /**
     * What noise fill should put at an air block: nothing, a basin floor patch, or basin fluid.
     * One cache lookup per block, and an early out for chunks with no interior water.
     */
    public static int basinFill(int x, int y, int z) {
        State state = STATE.get();
        if (!state.prepared || state.chunkX != (x >> 4) || state.chunkZ != (z >> 4)
                || state.layoutSalt != CellularIslandDensityFunction.layoutSalt()
                || state.worldSeed != CellularIslandDensityFunction.worldSeed()) {
            return slowBasinFill(x, y, z);
        }
        if (!state.active) {
            return slowBasinFill(x, y, z);
        }
        if (!state.anyWaterInterior) {
            return FILL_NONE;
        }
        int idx = index(x, z);
        if (!state.waterInterior[idx]) {
            return FILL_NONE;
        }
        CellularIslandDensityFunction.WaterColumn water = state.water[idx];
        if (water == null) {
            return FILL_NONE;
        }
        if (y <= water.seabedY && y > water.seabedY - FLOOR_PATCH_DEPTH) {
            return FILL_FLOOR;
        }
        if (!CellularIslandDensityFunction.couldHaveWaterAtY(y) || state.layouts[idx] == null) {
            return FILL_NONE;
        }
        return y > water.seabedY && y <= water.waterLevel ? FILL_FLUID : FILL_NONE;
    }

    /**
     * Highest Y in the prepared chunk that noise fill can make non-air: the solid top over the
     * interpolation corner columns, and the basin water levels. Integer.MAX_VALUE when unknown
     * (no trimming); Integer.MIN_VALUE when the whole chunk is air.
     */
    public static int solidTopY(int blockX, int blockZ, int cellWidth) {
        State state = STATE.get();
        if (!state.prepared || !state.active || state.chunkX != (blockX >> 4) || state.chunkZ != (blockZ >> 4)
                || state.layoutSalt != CellularIslandDensityFunction.layoutSalt()
                || state.worldSeed != CellularIslandDensityFunction.worldSeed()
                || cellWidth <= 0 || 16 % cellWidth != 0) {
            return Integer.MAX_VALUE;
        }
        int x0 = state.chunkX << 4;
        int z0 = state.chunkZ << 4;
        int top = Integer.MIN_VALUE;
        for (int dx = 0; dx <= 16; dx += cellWidth) {
            for (int dz = 0; dz <= 16; dz += cellWidth) {
                top = Math.max(top, IslandVolume.solidTopY(x0 + dx, z0 + dz));
            }
        }
        for (int i = 0; i < 256; i++) {
            CellularIslandDensityFunction.WaterColumn water = state.water[i];
            if (water != null) {
                top = Math.max(top, Math.max(water.waterLevel, water.seabedY));
            }
        }
        return top;
    }

    /** Columns outside the prepared chunk: same rules through the per-column lookups. */
    private static int slowBasinFill(int x, int y, int z) {
        if (!CellularIslandDensityFunction.couldHaveWaterAtY(y)) {
            return FILL_NONE;
        }
        return CellularIslandDensityFunction.isInsideInteriorWaterBasin(x, y, z) ? FILL_FLUID : FILL_NONE;
    }

    private static void prepare(int chunkX, int chunkZ) {
        State state = STATE.get();
        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.layoutSalt = CellularIslandDensityFunction.layoutSalt();
        state.worldSeed = CellularIslandDensityFunction.worldSeed();
        state.prepared = true;
        state.anyWaterInterior = false;
        state.active = CellularIslandDensityFunction.chunkMightContainIsland(chunkX, chunkZ);
        if (!state.active) {
            return;
        }

        int cellSize = SkyloreConfig.cellSize();
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Water right after each column's layout so both reuse the same cached warp point.
        for (int dz = 0; dz < PAD; dz++) {
            for (int dx = 0; dx < PAD; dx++) {
                int x = baseX + dx - 1;
                int z = baseZ + dz - 1;
                CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.findLayoutContainingUncached(
                        x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
                state.paddedLayouts[dx + dz * PAD] = layout;
                if (dx == 0 || dz == 0 || dx == PAD - 1 || dz == PAD - 1) {
                    continue;
                }
                int idx = (dx - 1) + (dz - 1) * 16;
                state.layouts[idx] = layout;
                state.water[idx] = layout == null
                        ? null
                        : CellularIslandDensityFunction.computeWaterColumnUncached(
                                x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
            }
        }

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int idx = dx + dz * 16;
                CellularIslandDensityFunction.WaterColumn water = state.water[idx];
                if (water == null) {
                    state.waterInterior[idx] = false;
                    continue;
                }
                int padIdx = (dx + 1) + (dz + 1) * PAD;
                state.waterInterior[idx] = state.paddedLayouts[padIdx - 1] != null
                        && state.paddedLayouts[padIdx + 1] != null
                        && state.paddedLayouts[padIdx - PAD] != null
                        && state.paddedLayouts[padIdx + PAD] != null;
                state.anyWaterInterior |= state.waterInterior[idx];
            }
        }
    }

    private static int index(int blockX, int blockZ) {
        return (blockX & 15) + ((blockZ & 15) * 16);
    }

    private static final class State {
        boolean prepared;
        boolean active;
        boolean anyWaterInterior;
        int chunkX;
        int chunkZ;
        long layoutSalt;
        long worldSeed;
        final CellularIslandDensityFunction.IslandLayout[] layouts = new CellularIslandDensityFunction.IslandLayout[256];
        final CellularIslandDensityFunction.WaterColumn[] water = new CellularIslandDensityFunction.WaterColumn[256];
        final boolean[] waterInterior = new boolean[256];
        final CellularIslandDensityFunction.IslandLayout[] paddedLayouts = new CellularIslandDensityFunction.IslandLayout[PAD * PAD];
    }
}
