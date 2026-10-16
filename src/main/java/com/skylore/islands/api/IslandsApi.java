package com.skylore.islands.api;

import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.CrashIslandSavedData;
import com.skylore.islands.worldgen.IslandLayout;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Stable surface for companion mods (Skylore Hearts). Every call is a fixed-cost
 * lookup: nothing here scans chunks or generates terrain.
 */
public final class IslandsApi {
    private IslandsApi() {}

    /** The Overworld and the Nether are island dimensions. */
    public static boolean hasIslands(Level level) {
        return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
    }

    /** Cell owning a position, using the worldgen domain warp so island rims map to their own island. */
    public static IslandCell cellAt(Level level, BlockPos pos) {
        int[] cell = IslandLayout.cellOfWarped(pos.getX(), pos.getZ(), SkyloreConfig.cellSize());
        return new IslandCell(cell[0], cell[1]);
    }

    public static boolean islandExists(Level level, IslandCell cell) {
        return layout(level, cell) != null;
    }

    /** The player's crash-island cell, once its crash site has been built. */
    public static Optional<IslandCell> homeCellOf(ServerPlayer player) {
        CrashIslandSavedData data = CrashIslandSavedData.get(player.server.overworld());
        int[] cell = data.assignedCell(player.getUUID());
        if (cell == null || !data.isBuilt(cell[0], cell[1])) {
            return Optional.empty();
        }
        return Optional.of(new IslandCell(cell[0], cell[1]));
    }

    /** Where the player stood after their crash landing (Overworld), if recorded. */
    public static Optional<BlockPos> crashSiteOf(ServerPlayer player) {
        CrashIslandSavedData data = CrashIslandSavedData.get(player.server.overworld());
        return homeCellOf(player).map(cell -> data.site(cell.x(), cell.z()));
    }

    /** Island centre column at its layout altitude. Rough: not a surface Y. */
    public static Optional<BlockPos> islandCenter(Level level, IslandCell cell) {
        CellularIslandDensityFunction.IslandLayout layout = layout(level, cell);
        return layout == null
                ? Optional.empty()
                : Optional.of(new BlockPos(layout.centerX, layout.altitude, layout.centerZ));
    }

    private static CellularIslandDensityFunction.IslandLayout layout(Level level, IslandCell cell) {
        if (!hasIslands(level)) {
            return null;
        }
        // The layout salt is thread-local worldgen state; restore it so callers are unaffected.
        long previous = CellularIslandDensityFunction.layoutSalt();
        IslandLayoutBinder.bindFromLevel(level);
        try {
            return IslandLayout.layoutForCell(cell.x(), cell.z(), SkyloreConfig.cellSize(),
                    SkyloreConfig.minIslandAltitude(), SkyloreConfig.maxIslandAltitude(),
                    SkyloreConfig.minIslandRadius(), SkyloreConfig.maxIslandRadius());
        } finally {
            CellularIslandDensityFunction.setLayoutSalt(previous);
        }
    }
}
