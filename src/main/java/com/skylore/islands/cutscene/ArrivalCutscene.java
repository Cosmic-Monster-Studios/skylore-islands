package com.skylore.islands.cutscene;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.event.CrashIslandHandler;
import com.skylore.islands.worldgen.CrashIslandSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the first-spawn cutscene: the ship flying through space, the sky snapping to Atmos and the crash.
 * The client runs the timeline; this only moves the player between the transit dimension and the island and
 * hands over to the normal crash arrival at the end. Singleplayer only.
 */
public final class ArrivalCutscene {
    public static final ResourceKey<Level> TRANSIT = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "transit"));
    public static final List<String> SPECIES = List.of("fortis", "enoki", "teknari", "beryl", "automaton", "tenebris", "laevis");

    /** Survives a logout mid-scene so the next login still lands the player properly. */
    private static final String ARRIVING_TAG = SkyloreIslands.MOD_ID + ":arriving";
    private static final Vec3 TRANSIT_ANCHOR = new Vec3(0.5, 128.0, 0.5);
    private static final int MAX_PLAY_TICKS = 20 * 150;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ArrivalCutscene() {}

    /** Whether a brand-new crash arrival should get the cutscene instead of waking up straight away. */
    public static boolean eligible(ServerPlayer player) {
        MinecraftServer server = player.server;
        return SkyloreConfig.arrivalCutscene()
                && !server.isDedicatedServer()
                && !server.isPublished()
                && server.getPlayerCount() <= 1
                && server.getLevel(TRANSIT) != null
                && player.connection.hasChannel(CutscenePayloads.ToClient.TYPE)
                && !CrashIslandSavedData.get(server.overworld()).sawCutscene(player.getUUID());
    }

    /** Offers the cutscene; the client starts it once the origin picker is closed. */
    public static void start(ServerPlayer player, BlockPos stand) {
        SESSIONS.put(player.getUUID(), new Session(stand));
        player.getPersistentData().putBoolean(ARRIVING_TAG, true);
        send(player, CutscenePayloads.OFFER, stand, "");
    }

    static void receive(ServerPlayer player, int kind) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        switch (kind) {
            case CutscenePayloads.READY -> {
                if (session.stage != Stage.OFFERED) {
                    return;
                }
                ServerLevel transit = player.server.getLevel(TRANSIT);
                if (transit == null) {
                    finish(player);
                    return;
                }
                session.stage = Stage.SPACE;
                session.startTick = player.server.getTickCount();
                hold(player, true);
                player.teleportTo(transit, TRANSIT_ANCHOR.x, TRANSIT_ANCHOR.y, TRANSIT_ANCHOR.z, 0.0F, 0.0F);
                send(player, CutscenePayloads.BEGIN, session.stand, species(player));
            }
            case CutscenePayloads.SNAP -> {
                if (session.stage != Stage.SPACE) {
                    return;
                }
                session.stage = Stage.APPROACH;
                BlockPos stand = session.stand;
                // Parked on the crash site so its chunks stream in; the camera rides the ship on the client.
                player.teleportTo(player.server.overworld(), stand.getX() + 0.5, stand.getY() + 0.5, stand.getZ() + 0.5,
                        player.getYRot(), 0.0F);
            }
            case CutscenePayloads.END -> finish(player);
            default -> {}
        }
    }

    private static void finish(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        land(player);
        if (session != null && session.test && player.level().dimension() != Level.OVERWORLD) {
            // Test runs without a crash site of their own end where they started.
            BlockPos stand = session.stand;
            player.teleportTo(player.server.overworld(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, player.getYRot(), 0.0F);
        }
    }

    /** {@code /skylore_islands cutscene}: replays the cutscene onto your own crash site, or onto where you stand. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(SkyloreIslands.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("cutscene").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    BlockPos stand = CrashIslandSavedData.get(player.server.overworld()).standOf(player.getUUID());
                    boolean test = stand == null;
                    start(player, test ? player.blockPosition() : stand);
                    SESSIONS.get(player.getUUID()).test = test;
                    return 1;
                })));
    }

    /** Puts the player on their crash site with the normal wake-up, whatever stage they got to. */
    private static void land(ServerPlayer player) {
        player.getPersistentData().remove(ARRIVING_TAG);
        hold(player, false);
        CrashIslandSavedData data = CrashIslandSavedData.get(player.server.overworld());
        data.markSawCutscene(player.getUUID());
        CrashIslandHandler.arrive(player);
    }

    private static void hold(ServerPlayer player, boolean held) {
        player.setNoGravity(held);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void send(ServerPlayer player, int kind, BlockPos stand, String species) {
        PacketDistributor.sendToPlayer(player, new CutscenePayloads.ToClient(kind, stand, species,
                SkyloreConfig.arrivalCutsceneSkippable()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (UUID uuid : List.copyOf(SESSIONS.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            Session session = SESSIONS.get(uuid);
            if (player == null || session.stage == Stage.OFFERED) {
                continue;
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            if (server.getTickCount() - session.startTick > MAX_PLAY_TICKS) {
                SkyloreIslands.LOGGER.warn("Arrival cutscene for {} never finished; landing them.", player.getGameProfile().getName());
                finish(player);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getPersistentData().getBoolean(ARRIVING_TAG)) {
            // Left mid-scene: parked in the transit dimension or hovering over the island.
            land(player);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && SESSIONS.containsKey(player.getUUID())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
    }

    /** The player's Skylore species path ("enoki"), or "" when Origins can't tell us. */
    static String species(ServerPlayer player) {
        String id = originFromApi(player);
        if (id == null) {
            String nbt = player.saveWithoutId(new CompoundTag()).toString();
            for (String species : SPECIES) {
                if (nbt.contains("\"skylore:" + species + "\"")) {
                    id = "skylore:" + species;
                    break;
                }
            }
        }
        if (id != null && id.startsWith("skylore:") && SPECIES.contains(id.substring(8))) {
            return id.substring(8);
        }
        return "";
    }

    /** Origins isn't a compile dependency, so ask it reflectively, the same two ways the pack's KubeJS does. */
    private static String originFromApi(ServerPlayer player) {
        try {
            Class<?> components = Class.forName("io.github.apace100.origins.registry.ModComponents");
            Class<?> layers = Class.forName("io.github.apace100.origins.origin.OriginLayers");
            Object layer = layers.getMethod("getLayer", ResourceLocation.class)
                    .invoke(null, ResourceLocation.fromNamespaceAndPath("origins", "origin"));
            Object key = components.getField("ORIGIN").get(null);
            Object component = key.getClass().getMethod("get", Object.class).invoke(key, player);
            return originId(component, layer);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        try {
            Class<?> componentClass = Class.forName("io.github.apace100.origins.component.OriginComponent");
            Class<?> layers = Class.forName("io.github.apace100.origins.registry.OriginLayers");
            Object key = componentClass.getField("KEY").get(null);
            Object component = key.getClass().getMethod("get", Object.class).invoke(key, player);
            Object layer = layers.getMethod("getLayer", String.class).invoke(null, "origins:origin");
            return originId(component, layer);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        return null;
    }

    private static String originId(Object component, Object layer) throws ReflectiveOperationException {
        if (component == null || layer == null) {
            return null;
        }
        for (Method method : component.getClass().getMethods()) {
            if (method.getName().equals("getOrigin") && method.getParameterCount() == 1) {
                Object origin = method.invoke(component, layer);
                return origin == null ? null : String.valueOf(origin.getClass().getMethod("getIdentifier").invoke(origin));
            }
        }
        return null;
    }

    private enum Stage { OFFERED, SPACE, APPROACH }

    private static final class Session {
        final BlockPos stand;
        Stage stage = Stage.OFFERED;
        int startTick;
        boolean test;

        Session(BlockPos stand) {
            this.stand = stand;
        }
    }
}
