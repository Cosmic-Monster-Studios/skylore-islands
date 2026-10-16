package com.skylore.islands.gameplay;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Runs player actions a number of server ticks later. Vanilla TickTask runs as soon as the
 * server has spare time, so it can't be used for timed sequences.
 */
public final class PlayerTasks {
    private static final List<Task> TASKS = new ArrayList<>();

    private PlayerTasks() {}

    public static void schedule(ServerPlayer player, int delayTicks, Consumer<ServerPlayer> action) {
        TASKS.add(new Task(player.server.getTickCount() + delayTicks, player.getUUID(), action));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (TASKS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task task = it.next();
            if (task.dueTick <= now) {
                due.add(task);
                it.remove();
            }
        }
        for (Task task : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(task.player);
            if (player != null) {
                task.action.accept(player);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TASKS.clear();
    }

    private record Task(int dueTick, UUID player, Consumer<ServerPlayer> action) {}
}
