package com.skylore.islands.event;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.api.CrashSiteBuiltEvent;
import com.skylore.islands.api.IslandCell;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.cutscene.ArrivalCutscene;
import com.skylore.islands.gameplay.CrashArrival;
import com.skylore.islands.gameplay.PlayerTasks;
import com.skylore.islands.worldgen.CrashIslandCells;
import com.skylore.islands.worldgen.CrashIslandSavedData;
import com.skylore.islands.worldgen.CrashSiteBuilder;
import com.skylore.islands.worldgen.IslandLayout;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;


/**
 * Crash-lands each new player on their own forested island: a blasted crater, a wrecked ship and a black box.
 */
public class CrashIslandHandler {
    private static final int MAX_ALLOC_TRIES = 512;
    /** Spots that get chunk-loaded and checked for real trees, per player. */
    private static final int MAX_BUILD_ATTEMPTS = 12;
    private static final int LOGIN_DELAY_TICKS = 20;
    private static final int RESPAWN_DELAY_TICKS = 5;

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SkyloreConfig.crashIslandsEnabled() || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        CrashIslandSavedData data = CrashIslandSavedData.get(overworld(player));
        int[] assigned = data.assignedCell(player.getUUID());
        if (assigned == null) {
            schedule(player, LOGIN_DELAY_TICKS, true);
        } else if (!data.isBuilt(assigned[0], assigned[1])) {
            schedule(player, LOGIN_DELAY_TICKS, false);
        }
        // Already housed: leave the player where they logged out.
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!SkyloreConfig.crashIslandsEnabled() || player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        // A bed, anchor or the stored crash-island spawn wins; only fall back when none is left.
        if (player.getRespawnPosition() != null) {
            return;
        }
        schedule(player, RESPAWN_DELAY_TICKS, false);
    }

    private static void schedule(ServerPlayer player, int delay, boolean announce) {
        PlayerTasks.schedule(player, delay, live -> deliver(live, announce));
    }

    private static void deliver(ServerPlayer player, boolean announce) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return;
        }
        ServerLevel level = player.serverLevel();
        IslandLayoutBinder.bindFromLevel(level);
        CrashIslandSavedData data = CrashIslandSavedData.get(overworld(player));
        int[] assigned = data.assignedCell(player.getUUID());
        Vec3 stand;
        boolean fresh = false;
        IslandCell builtCell = null;
        if (assigned != null && data.isBuilt(assigned[0], assigned[1])) {
            BlockPos site = data.site(assigned[0], assigned[1]);
            stand = site != null
                    ? CrashSiteBuilder.standAt(level, site)
                    : CrashSiteBuilder.legacyStand(level, assigned[0], assigned[1]);
        } else {
            if (assigned != null) {
                data.release(player.getUUID());
            }
            CrashSite crash = allocateAndBuild(level, data);
            if (crash == null) {
                player.sendSystemMessage(Component.literal("No forested island left to crash on."));
                return;
            }
            data.assign(player.getUUID(), crash.cellX, crash.cellZ);
            data.markBuilt(crash.cellX, crash.cellZ, crash.stand);
            stand = CrashSiteBuilder.standAt(level, crash.stand);
            announce = true;
            fresh = true;
            builtCell = new IslandCell(crash.cellX, crash.cellZ);
        }
        BlockPos spawn = BlockPos.containing(stand);
        if (announce || player.getRespawnPosition() == null) {
            // forced: vanilla clears non-forced spawns that have no bed/anchor block.
            player.setRespawnPosition(Level.OVERWORLD, spawn, player.getYRot(), true, false);
        }
        if (announce) {
            SkyloreIslands.LOGGER.info("Crash site for {} -> {}", player.getGameProfile().getName(), spawn);
        }
        if (fresh && ArrivalCutscene.eligible(player)) {
            // The cutscene ends in arrive(), which does the teleport and wake-up below.
            ArrivalCutscene.start(player, spawn);
            return;
        }
        player.teleportTo(stand.x, stand.y, stand.z);
        if (fresh) {
            CrashArrival.wakeUp(player);
            NeoForge.EVENT_BUS.post(new CrashSiteBuiltEvent(player, builtCell, spawn));
        }
    }

    /** Lands a player on their already-built crash site with the fresh-arrival wake-up, after the cutscene. */
    public static void arrive(ServerPlayer player) {
        ServerLevel level = overworld(player);
        CrashIslandSavedData data = CrashIslandSavedData.get(level);
        int[] assigned = data.assignedCell(player.getUUID());
        BlockPos site = data.standOf(player.getUUID());
        if (assigned == null || site == null) {
            return;
        }
        Vec3 stand = CrashSiteBuilder.standAt(level, site);
        player.teleportTo(level, stand.x, stand.y, stand.z, player.getYRot(), 0.0F);
        CrashArrival.wakeUp(player);
        NeoForge.EVENT_BUS.post(new CrashSiteBuiltEvent(player, new IslandCell(assigned[0], assigned[1]), BlockPos.containing(stand)));
    }

    /** Walks the cell spiral for an unclaimed forested island and blasts a crash site into it. */
    private static CrashSite allocateAndBuild(ServerLevel level, CrashIslandSavedData data) {
        boolean skipOrigin = SkyloreConfig.crashIslandSkipOriginCell();
        int cellSize = SkyloreConfig.cellSize();
        int minAlt = SkyloreConfig.minIslandAltitude();
        int maxAlt = SkyloreConfig.maxIslandAltitude();
        int minRad = SkyloreConfig.minIslandRadius();
        int maxRad = SkyloreConfig.maxIslandRadius();
        int builds = 0;
        for (int tries = 0; tries < MAX_ALLOC_TRIES && builds < MAX_BUILD_ATTEMPTS; tries++) {
            int[] cell = CrashIslandCells.cellAtSpawnIndex(data.takeNextSeq(), skipOrigin);
            int cellX = cell[0];
            int cellZ = cell[1];
            if ((skipOrigin && cellX == 0 && cellZ == 0) || data.isClaimed(cellX, cellZ)) {
                continue;
            }
            CellularIslandDensityFunction.IslandLayout layout = CellularIslandDensityFunction.layoutForCell(
                    cellX, cellZ, cellSize, minAlt, maxAlt, minRad, maxRad);
            if (layout == null || IslandLayout.isBowlArchetype(layout.archetype) || layout.baseRadius < SkyloreConfig.crashMinIslandRadius()) {
                continue;
            }
            long seed = CellularIslandDensityFunction.worldSeed() ^ (CrashIslandSavedData.pack(cellX, cellZ) * 0x9E3779B97F4A7C15L);
            for (int[] site : CrashSiteBuilder.candidateSites(level, layout, seed)) {
                if (builds++ >= MAX_BUILD_ATTEMPTS) {
                    break;
                }
                BlockPos stand = CrashSiteBuilder.tryBuild(level, site[0], site[1], seed);
                if (stand != null) {
                    return new CrashSite(cellX, cellZ, stand);
                }
            }
        }
        return null;
    }

    private record CrashSite(int cellX, int cellZ, BlockPos stand) {}

    private static ServerLevel overworld(ServerPlayer player) {
        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        return overworld != null ? overworld : player.serverLevel();
    }
}
