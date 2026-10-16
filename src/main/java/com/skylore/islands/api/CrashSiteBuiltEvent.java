package com.skylore.islands.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/** Posted on NeoForge.EVENT_BUS after a player's crash site is built and they are standing on it. */
public class CrashSiteBuiltEvent extends Event {
    private final ServerPlayer player;
    private final IslandCell cell;
    private final BlockPos stand;

    public CrashSiteBuiltEvent(ServerPlayer player, IslandCell cell, BlockPos stand) {
        this.player = player;
        this.cell = cell;
        this.stand = stand.immutable();
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public IslandCell getCell() {
        return cell;
    }

    public BlockPos getStand() {
        return stand;
    }
}
