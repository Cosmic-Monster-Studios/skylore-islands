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

    private static void prepare(int chunkX, int chunkZ) {
        State state = STATE.get();
        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.layoutSalt = CellularIslandDensityFunction.layoutSalt();
        state.worldSeed = CellularIslandDensityFunction.worldSeed();
        state.prepared = true;
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
            }
        }
    }

    private static int index(int blockX, int blockZ) {
        return (blockX & 15) + ((blockZ & 15) * 16);
    }

    private static final class State {
        boolean prepared;
        boolean active;
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
