package com.skylore.islands.client.cutscene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.cutscene.ArrivalCutscene;
import com.skylore.islands.cutscene.CutscenePayloads;
import com.skylore.islands.init.SkyloreBlocks;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Client side of the arrival cutscene. Owns the timeline (in seconds from the first frame in space),
 * the camera, the ship and crew drawing, sounds, particles and the letterboxed overlay.
 */
public final class CutscenePlayer {
    private static final ResourceLocation SCRIPT = ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "cutscene/arrival.json");

    // Timeline, seconds. Matches docs/arrival-cutscene-script.md and the black box log.
    private static final float SPARKS_AT = 41.0F;
    private static final float TROUBLE_AT = 44.0F;
    private static final float BREACH_AT = 52.0F;
    private static final float PODS_AT = 73.0F;
    private static final float PODS_SHOT_END = 75.8F;
    private static final float SNAP_AT = 77.0F;
    private static final float CHASE_AT = 80.0F;
    private static final float FINAL_INTERIOR_AT = 83.0F;
    private static final float IMPACT_AT = 85.0F;
    private static final float END_AT = 87.5F;
    private static final float SKIP_HOLD_TICKS = 30;
    private static final int OFFER_SETTLE_TICKS = 40;

    private static final double APPROACH_OUT = 230.0;
    private static final double APPROACH_UP = 160.0;

    private enum Stage { IDLE, OFFERED, ENTERING, PLAYING, LANDING }

    private static Stage stage = Stage.IDLE;
    private static BlockPos stand = BlockPos.ZERO;
    private static String species = "";
    private static boolean skippable = true;
    private static List<String> aboard = List.of();
    private static final List<Line> lines = new ArrayList<>();
    private static int ticks;
    private static int settle;
    private static int skipHeld;
    private static int landingTicks;
    private static boolean snapSent;
    private static boolean endSent;
    private static boolean begun;
    private static Vec3 spaceCenter = Vec3.ZERO;
    private static Vec3 approachDir = new Vec3(1, 0, 0);
    private static float starTravel;
    private static float starTravelOld;
    private static ShipRenderer ship;
    private static final Crew crew = new Crew();
    private static final RandomSource random = RandomSource.create();
    private static final List<LoopSound> loops = new ArrayList<>();

    private record Line(float t, String speaker, String key) {}

    private CutscenePlayer() {}

    public static boolean active() {
        return stage == Stage.PLAYING || stage == Stage.ENTERING || stage == Stage.LANDING;
    }

    /** The camera only leaves the player once the ship is on screen. */
    public static boolean cameraReady() {
        return stage == Stage.PLAYING && Minecraft.getInstance().player != null;
    }

    public static void receive(CutscenePayloads.ToClient payload) {
        stand = payload.stand();
        skippable = payload.skippable();
        if (payload.kind() == CutscenePayloads.OFFER) {
            stage = Stage.OFFERED;
            settle = 0;
            begun = false;
        } else if (payload.kind() == CutscenePayloads.BEGIN) {
            species = payload.species();
            aboard = Crew.aboard(species);
            loadScript();
            double a = (stand.hashCode() & 1023) / 1024.0 * Math.PI * 2.0;
            approachDir = new Vec3(Math.cos(a), 0, Math.sin(a));
            ticks = 0;
            skipHeld = 0;
            snapSent = false;
            endSent = false;
            starTravel = starTravelOld = 0.0F;
            begun = true;
            stage = Stage.ENTERING;
        }
    }

    private static void loadScript() {
        lines.clear();
        Optional<net.minecraft.server.packs.resources.Resource> resource = Minecraft.getInstance().getResourceManager().getResource(SCRIPT);
        if (resource.isEmpty()) {
            return;
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("lines");
            for (JsonElement element : array) {
                JsonObject o = element.getAsJsonObject();
                String speaker = o.get("speaker").getAsString();
                String alt = o.has("alt") ? o.get("alt").getAsString() : "";
                if (speaker.equals(species)) {
                    speaker = alt;
                }
                if (speaker.isEmpty() || !(speaker.equals("ship") || aboard.contains(speaker))) {
                    continue;
                }
                lines.add(new Line(o.get("t").getAsFloat(), speaker, o.get("key").getAsString()));
            }
        } catch (Exception e) {
            SkyloreIslands.LOGGER.warn("Couldn't read the arrival cutscene script", e);
        }
    }

    // ---- timeline ----

    private static float time(float partial) {
        return (ticks + (stage == Stage.PLAYING ? partial : 0.0F)) / 20.0F;
    }

    private static boolean inTransit() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == ArrivalCutscene.TRANSIT;
    }

    private static boolean interior(float t) {
        return (t >= 6.0F && t < SPARKS_AT) || (t >= TROUBLE_AT && t < PODS_AT + 0.3F)
                || (t >= PODS_SHOT_END && t < CHASE_AT) || (t >= FINAL_INTERIOR_AT);
    }

    /** How hard everything shakes, 0..1. */
    private static float shake(float t) {
        float s = 0.03F;
        if (t >= TROUBLE_AT) {
            s = 0.15F + 0.25F * Mth.clamp((t - TROUBLE_AT) / (SNAP_AT - TROUBLE_AT), 0.0F, 1.0F);
        }
        s += spike(t, 43.2F) + spike(t, TROUBLE_AT) + spike(t, BREACH_AT) + spike(t, PODS_AT) * 0.6F
                + spike(t, PODS_AT + 0.4F) * 0.6F + spike(t, PODS_AT + 0.8F) * 0.6F;
        if (t >= SNAP_AT) {
            s = 0.55F + 0.45F * Mth.clamp((t - SNAP_AT) / (IMPACT_AT - SNAP_AT), 0.0F, 1.0F);
        }
        return Math.min(s, 1.0F);
    }

    private static float spike(float t, float at) {
        float d = t - at;
        return d < 0.0F || d > 1.5F ? 0.0F : (1.0F - d / 1.5F);
    }

    private static float brightness(float t) {
        if (t < TROUBLE_AT - 0.8F || t >= SNAP_AT) {
            return 1.0F;
        }
        int step = (int) (t * 9.0F);
        float noise = (float) ((step * 2654435761L & 0xFFFF) / 65535.0);
        return noise < 0.18F ? 0.25F : 0.7F;
    }

    private static float alarm(float t) {
        boolean on = (t >= TROUBLE_AT && t < SNAP_AT) || (t >= 81.0F && t < IMPACT_AT);
        return on ? 0.5F + 0.5F * Mth.sin(t * Mth.TWO_PI * 1.2F) : 0.0F;
    }

    private static float noise(float t, float seed) {
        return Mth.sin(t * 23.0F + seed) * 0.6F + Mth.sin(t * 41.0F + seed * 2.3F) * 0.4F;
    }

    // ---- ship pose ----

    private static Vec3 approachStart() {
        Vec3 end = approachEnd();
        return end.add(approachDir.scale(APPROACH_OUT)).add(0, APPROACH_UP, 0);
    }

    private static Vec3 approachEnd() {
        return Vec3.atBottomCenterOf(stand).add(0, 3.0, 0);
    }

    private static Vec3 travel() {
        return approachEnd().subtract(approachStart()).normalize();
    }

    static Vec3 shipCenter(float t) {
        if (t < SNAP_AT || inTransit()) {
            return spaceCenter;
        }
        float u = Mth.clamp((t - SNAP_AT) / (IMPACT_AT - SNAP_AT), 0.0F, 1.0F);
        // Still braking: fast at first, slower toward the island, but never stopped.
        float f = 0.6F * u + 0.4F * (1.0F - (1.0F - u) * (1.0F - u));
        return approachStart().lerp(approachEnd(), f);
    }

    static float shipYaw(float t) {
        if (t < SNAP_AT || inTransit()) {
            return 0.0F;
        }
        Vec3 d = travel();
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    private static Vec3 toWorld(Vec3 local, float t) {
        return local.yRot(-shipYaw(t) * Mth.DEG_TO_RAD).add(shipCenter(t));
    }

    // ---- camera ----

    /** Camera position and rotation {x, y, z, yaw, pitch, roll} for this frame. */
    public static double[] camera(float partial) {
        Minecraft mc = Minecraft.getInstance();
        float t = time(partial);
        float s = shake(t);
        Vec3 pos;
        float yaw;
        float pitch;
        if (interior(t)) {
            pos = toWorld(ShipModel.PLAYER_EYE, t);
            yaw = mc.player.getViewYRot(partial);
            pitch = mc.player.getViewXRot(partial);
        } else {
            Vec3 center = shipCenter(t);
            Vec3 look;
            if (t < 6.0F) {
                float a = (200.0F + t * 14.0F) * Mth.DEG_TO_RAD;
                pos = center.add(Mth.sin(a) * 22.0, 5.0 - t * 0.4, Mth.cos(a) * 22.0);
                look = center;
            } else if (t < TROUBLE_AT) {
                Vec3 hull = ShipModel.hullPoint(0.6, 0.35, 0.7);
                pos = center.add(hull.scale(1.9)).add(3.0, 1.0, 0.0);
                look = center.add(hull);
            } else if (t < PODS_SHOT_END) {
                pos = center.add(16.0, 3.0, -22.0);
                look = center.add(0.0, -3.0, -12.0);
            } else {
                Vec3 d = travel();
                Vec3 side = d.cross(new Vec3(0, 1, 0)).normalize();
                pos = center.subtract(d.scale(24.0)).add(0, 7.0, 0).add(side.scale(5.0));
                look = center.add(d.scale(20.0));
            }
            Vec3 d = look.subtract(pos);
            yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        }
        yaw += s * noise(t, 1.0F) * 2.5F;
        pitch += s * noise(t, 2.0F) * 2.0F;
        float roll = s * noise(t, 3.0F) * 3.0F;
        return new double[]{pos.x, pos.y, pos.z, yaw, pitch, roll};
    }

    public static float starTravel(float partial) {
        return Mth.lerp(partial, starTravelOld, starTravel);
    }

    // ---- ticking ----

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (stage == Stage.IDLE) {
            return;
        }
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        switch (stage) {
            case OFFERED -> {
                // Wait for the origin picker (or anything else) to be closed for a moment.
                settle = mc.screen == null ? settle + 1 : 0;
                if (settle >= OFFER_SETTLE_TICKS) {
                    stage = Stage.ENTERING;
                    PacketDistributor.sendToServer(new CutscenePayloads.ToServer(CutscenePayloads.READY));
                }
            }
            case ENTERING -> {
                hold(player);
                if (begun && inTransit()) {
                    spaceCenter = player.position();
                    stage = Stage.PLAYING;
                    startLoops();
                }
            }
            case PLAYING -> tickPlaying(mc, player);
            case LANDING -> {
                // Stay black until the server has put the player down on the island.
                boolean landed = mc.level.dimension() == Level.OVERWORLD && !player.isNoGravity();
                if (++landingTicks > 200 || landed && landingTicks > 10) {
                    reset();
                }
            }
            default -> {}
        }
    }

    private static void tickPlaying(Minecraft mc, LocalPlayer player) {
        hold(player);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        if (mc.isPaused() || mc.screen != null && !(mc.screen instanceof ReceivingLevelScreen)) {
            return;
        }
        float before = ticks / 20.0F;
        // Hold the clock at the snap until the island level has arrived, so the cut is exact.
        if (before >= SNAP_AT - 0.05F && inTransit()) {
            if (!snapSent) {
                snapSent = true;
                PacketDistributor.sendToServer(new CutscenePayloads.ToServer(CutscenePayloads.SNAP));
            }
            return;
        }
        ticks++;
        float t = ticks / 20.0F;

        starTravelOld = starTravel;
        float speed = t < PODS_AT ? 0.5F : Mth.lerp(Mth.clamp((t - PODS_AT) / 4.0F, 0.0F, 1.0F), 0.5F, 0.08F);
        starTravel += speed;

        if (interior(t) && !interior(before) || t >= SNAP_AT && before < SNAP_AT) {
            // Every cut back inside starts looking out the front window.
            player.setYRot(shipYaw(t));
            player.setXRot(8.0F);
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
        }

        cues(mc, before, t);
        particles(mc, t);
        for (LoopSound loop : loops) {
            loop.update(t);
        }

        if (skippable && mc.options.keyJump.isDown()) {
            if (++skipHeld >= SKIP_HOLD_TICKS) {
                end();
            }
        } else {
            skipHeld = 0;
        }
        if (t >= END_AT) {
            end();
        }
    }

    private static void end() {
        if (endSent) {
            return;
        }
        endSent = true;
        PacketDistributor.sendToServer(new CutscenePayloads.ToServer(CutscenePayloads.END));
        stopLoops();
        stage = Stage.LANDING;
        landingTicks = 0;
    }

    private static void hold(LocalPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void reset() {
        stopLoops();
        stage = Stage.IDLE;
        ship = null;
    }

    private static void cues(Minecraft mc, float before, float t) {
        if (crossed(before, t, 41.5F)) {
            ui(SoundEvents.BEACON_POWER_SELECT, 0.5F, 0.8F);
        }
        if (crossed(before, t, 43.2F)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 0.7F);
        }
        if (crossed(before, t, TROUBLE_AT)) {
            ui(SoundEvents.BEACON_DEACTIVATE, 0.5F, 1.0F);
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.4F, 0.6F);
        }
        if (crossed(before, t, BREACH_AT)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 0.5F);
            ui(SoundEvents.ELYTRA_FLYING, 0.4F, 1.0F);
        }
        if (crossed(before, t, BREACH_AT + 1.5F)) {
            ui(SoundEvents.IRON_DOOR_CLOSE, 0.6F, 1.0F);
        }
        if (crossed(before, t, 62.0F)) {
            ui(SoundEvents.BEACON_DEACTIVATE, 0.5F, 0.6F);
        }
        for (int i = 0; i < 3; i++) {
            if (crossed(before, t, PODS_AT + i * 0.4F)) {
                ui(SoundEvents.ANVIL_PLACE, 0.5F, 0.5F);
            }
        }
        if (crossed(before, t, PODS_AT + 1.2F)) {
            ui(SoundEvents.FIRECHARGE_USE, 0.5F, 0.6F);
        }
        if (crossed(before, t, IMPACT_AT)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 1.0F);
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 1.0F);
            ui(SoundEvents.ANVIL_LAND, 0.5F, 0.8F);
            stopLoops();
        }
        if (crossed(before, t, IMPACT_AT + 1.6F)) {
            ui(SoundEvents.NOTE_BLOCK_BIT.value(), 2.0F, 0.4F);
        }
        // Alarm: two-tone, every 0.6 s.
        if (alarm(t) > 0.0F || (t >= TROUBLE_AT && t < SNAP_AT)) {
            if (ticks % 12 == 0) {
                ui(SoundEvents.NOTE_BLOCK_BIT.value(), (ticks / 12) % 2 == 0 ? 1.4F : 1.0F, 0.35F);
            }
        }
        // Typing blips for whoever is talking.
        Line line = currentLine(t);
        if (line != null && ticks % 2 == 0 && t - line.t < typed(line) / 40.0F) {
            blip(line.speaker);
        }
    }

    private static boolean crossed(float before, float t, float at) {
        return before < at && t >= at;
    }

    private static void ui(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static void blip(String speaker) {
        SoundEvent sound;
        float pitch;
        switch (speaker) {
            case "fortis" -> { sound = SoundEvents.NOTE_BLOCK_BASS.value(); pitch = 1.0F; }
            case "enoki" -> { sound = SoundEvents.NOTE_BLOCK_FLUTE.value(); pitch = 1.2F; }
            case "teknari" -> { sound = SoundEvents.NOTE_BLOCK_BIT.value(); pitch = 1.4F; }
            case "beryl" -> { sound = SoundEvents.NOTE_BLOCK_CHIME.value(); pitch = 1.0F; }
            case "automaton" -> { sound = SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE.value(); pitch = 0.8F; }
            case "tenebris" -> { sound = SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(); pitch = 1.2F; }
            case "laevis" -> { sound = SoundEvents.NOTE_BLOCK_GUITAR.value(); pitch = 1.1F; }
            default -> { sound = SoundEvents.NOTE_BLOCK_BIT.value(); pitch = 0.7F; }
        }
        ui(sound, pitch + (random.nextFloat() - 0.5F) * 0.1F, 0.25F);
    }

    private static void particles(Minecraft mc, float t) {
        if (mc.level == null) {
            return;
        }
        Vec3 center = shipCenter(t);
        if (t >= SPARKS_AT && t < SNAP_AT) {
            Vec3 hull = center.add(ShipModel.hullPoint(0.6, 0.35, 0.7));
            int sparks = t < TROUBLE_AT ? 4 : 1;
            for (int i = 0; i < sparks; i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, hull.x, hull.y, hull.z,
                        random.nextGaussian() * 0.3, random.nextGaussian() * 0.3, random.nextGaussian() * 0.3);
            }
            if (t >= TROUBLE_AT || t >= 43.2F) {
                mc.level.addParticle(ParticleTypes.CLOUD, hull.x, hull.y, hull.z, 0.15, 0.05, 0.1);
            }
            if (t >= 43.2F && t < 43.3F) {
                mc.level.addParticle(ParticleTypes.EXPLOSION, hull.x, hull.y, hull.z, 0, 0, 0);
            }
        }
        if (t >= SNAP_AT && t < IMPACT_AT) {
            Vec3 back = travel().scale(-ShipModel.RADIUS);
            Vec3 prev = shipCenter(t - 0.05F);
            for (int i = 0; i < 8; i++) {
                Vec3 p = prev.lerp(center, random.nextDouble()).add(back.scale(0.3))
                        .add(random.nextGaussian() * 3, random.nextGaussian() * 3, random.nextGaussian() * 3);
                mc.level.addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 0, 0.02, 0);
            }
            Vec3 p = center.add(back);
            mc.level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, p.x, p.y, p.z, 0, 0.05, 0);
        }
    }

    // ---- sounds that loop ----

    private static void startLoops() {
        stopLoops();
        loops.add(new LoopSound(SoundEvents.BEACON_AMBIENT, 0.5F, 0.0F, SNAP_AT, 0.6F));
        loops.add(new LoopSound(SoundEvents.ELYTRA_FLYING, 1.0F, SNAP_AT, IMPACT_AT, 1.0F));
        loops.add(new LoopSound(SoundEvents.FIRE_AMBIENT, 0.8F, SNAP_AT, IMPACT_AT, 1.0F));
        for (LoopSound loop : loops) {
            Minecraft.getInstance().getSoundManager().play(loop);
        }
    }

    private static void stopLoops() {
        loops.forEach(LoopSound::finish);
        loops.clear();
    }

    private static final class LoopSound extends AbstractTickableSoundInstance {
        private final float from;
        private final float to;
        private final float loud;

        LoopSound(SoundEvent sound, float pitch, float from, float to, float loud) {
            super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.pitch = pitch;
            this.volume = 0.0001F;
            this.from = from;
            this.to = to;
            this.loud = loud;
        }

        void update(float t) {
            volume = t >= from && t < to ? loud : 0.0001F;
        }

        void finish() {
            stop();
        }

        @Override
        public void tick() {
            if (!active()) {
                stop();
            }
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }

    // ---- rendering ----

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (stage != Stage.PLAYING || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float t = time(partial);
        if (t >= IMPACT_AT) {
            return;
        }
        if (ship == null) {
            ship = new ShipRenderer(new ShipModel());
        }
        Vec3 cam = event.getCamera().getPosition();
        Vec3 center = shipCenter(t);
        float yaw = shipYaw(t);
        float light = interior(t) ? brightness(t) : 1.0F;
        float red = alarm(t) * 0.35F * (interior(t) ? 1.0F : 0.0F);

        PoseStack pose = new PoseStack();
        pose.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z);
        pose.mulPose(Axis.YP.rotation(-yaw * Mth.DEG_TO_RAD));
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        ship.render(pose, buffers, light, red);
        Line line = currentLine(t);
        float brace = Mth.clamp((t - PODS_AT) / 1.5F, 0.0F, 1.0F);
        crew.render(pose, buffers, aboard, line == null ? "" : line.speaker, brace, shake(t), t, light);

        if (t >= PODS_AT && t < SNAP_AT) {
            BlockState pod = SkyloreBlocks.CARGO_POD.get().defaultBlockState();
            for (int i = 0; i < 3; i++) {
                float age = t - (PODS_AT + i * 0.4F);
                if (age < 0.0F) {
                    continue;
                }
                pose.pushPose();
                pose.translate(-2.5 + 2.5 * i - 0.5 + age * (i - 1) * 0.8, -ShipModel.RADIUS - age * 1.5, -ShipModel.RADIUS + 1 - age * 5.0);
                pose.mulPose(Axis.XP.rotationDegrees(age * (40 + i * 25)));
                pose.mulPose(Axis.ZP.rotationDegrees(age * (30 - i * 20)));
                pose.scale(1.5F, 1.5F, 1.5F);
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(pod, pose, buffers, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        buffers.endBatch();
    }

    // ---- overlay ----

    private static Line currentLine(float t) {
        Line found = null;
        int next = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).t <= t) {
                found = lines.get(i);
                next = i + 1;
            }
        }
        if (found == null) {
            return null;
        }
        float until = next < lines.size() ? lines.get(next).t : found.t + 4.0F;
        return t < Math.min(until, found.t + 4.5F) && t < IMPACT_AT ? found : null;
    }

    private static int typed(Line line) {
        return Component.translatable(line.key).getString().length();
    }

    public static void renderOverlay(GuiGraphics g, DeltaTracker delta) {
        if (!active() && stage != Stage.OFFERED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (stage == Stage.OFFERED) {
            if (settle > 0) {
                g.fill(0, 0, w, h, 0xFF000000);
            }
            return;
        }
        if (stage != Stage.PLAYING) {
            g.fill(0, 0, w, h, 0xFF000000);
            return;
        }
        float t = time(delta.getGameTimeDeltaPartialTick(false));
        Font font = mc.font;
        int bar = h / 9;

        float alarm = alarm(t);
        if (alarm > 0.0F && interior(t)) {
            g.fill(0, 0, w, h, ((int) (alarm * 40) << 24) | 0xC00000);
        }
        if (t >= SNAP_AT && t < SNAP_AT + 2.5F) {
            // Punching out of the cloud bank.
            int a = (int) (Mth.clamp(1.0F - (t - SNAP_AT - 0.3F) / 2.2F, 0.0F, 1.0F) * 230);
            g.fill(0, 0, w, h, (a << 24) | 0xE8ECF2);
        }
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);

        if (t >= 2.0F && t < 6.5F) {
            int a = (int) (Mth.clamp(Math.min(t - 2.0F, 6.5F - t), 0.0F, 1.0F) * 255);
            if (a > 8) {
                Component title = Component.translatable("cutscene.skylore_islands.arrival.title");
                Component sub = Component.translatable("cutscene.skylore_islands.arrival.subtitle");
                g.pose().pushPose();
                g.pose().translate(w / 2.0F, h / 2.0F - 20, 0);
                g.pose().scale(1.6F, 1.6F, 1.0F);
                g.drawCenteredString(font, title, 0, 0, (a << 24) | 0xFFFFFF);
                g.pose().popPose();
                g.drawCenteredString(font, sub, w / 2, h / 2 + 2, (a << 24) | 0x8CFFDA);
            }
        }

        Line line = currentLine(t);
        if (line != null) {
            String text = Component.translatable(line.key).getString();
            int shown = Math.min(text.length(), (int) ((t - line.t) * 40.0F));
            boolean ship = line.speaker.equals("ship");
            int color = ship ? 0xFF5CE1E6 : Crew.SPECIES.get(line.speaker).color();
            Component name = Component.translatable("cutscene.skylore_islands.speaker." + line.speaker);
            int y = h - bar + 4;
            g.drawCenteredString(font, name, w / 2, y, color);
            g.drawCenteredString(font, text.substring(0, shown), w / 2, y + 12, ship ? 0xFFB8F4F6 : 0xFFFFFFFF);
        }

        if (skippable && t < IMPACT_AT) {
            Component hint = Component.translatable("cutscene.skylore_islands.skip", mc.options.keyJump.getTranslatedKeyMessage());
            int hw = font.width(hint);
            int x = w - hw - 8;
            int y = bar / 2 - 4;
            g.drawString(font, hint, x, y, 0x99FFFFFF);
            if (skipHeld > 0) {
                g.fill(x, y + 10, x + (int) (hw * skipHeld / SKIP_HOLD_TICKS), y + 11, 0xFFFFFFFF);
            }
        }

        // Fade in from black, white flash on impact, then black until the island wake-up.
        if (t < 1.5F) {
            g.fill(0, 0, w, h, ((int) ((1.0F - t / 1.5F) * 255) << 24));
        }
        if (t >= IMPACT_AT) {
            float d = t - IMPACT_AT;
            g.fill(0, 0, w, h, d < 0.25F ? 0xFFFFFFFF : 0xFF000000);
        }
    }

    // ---- event plumbing ----

    /** Draws the cutscene overlay in place of the whole HUD, other mods' HUDs included. */
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (active() || stage == Stage.OFFERED && settle > 0) {
            renderOverlay(event.getGuiGraphics(), event.getPartialTick());
            event.setCanceled(true);
        }
    }

    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (active()) {
            var input = event.getInput();
            input.leftImpulse = 0.0F;
            input.forwardImpulse = 0.0F;
            input.up = input.down = input.left = input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }
    }

    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (active()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    public static void onRenderHand(RenderHandEvent event) {
        if (active()) {
            event.setCanceled(true);
        }
    }

    public static void onHighlight(RenderHighlightEvent.Block event) {
        if (active()) {
            event.setCanceled(true);
        }
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        // No "loading terrain" between space and Atmos: the cut has to be instant.
        if (active() && (event.getNewScreen() instanceof ReceivingLevelScreen || event.getNewScreen() instanceof InventoryScreen)) {
            event.setCanceled(true);
        }
    }

    public static void onFog(ViewportEvent.RenderFog event) {
        if (stage != Stage.PLAYING) {
            return;
        }
        float t = time((float) event.getPartialTick());
        if (t >= SNAP_AT && !inTransit()) {
            float clear = Mth.clamp((t - SNAP_AT - 0.3F) / 2.4F, 0.0F, 1.0F);
            float far = Mth.lerp(clear * clear, 6.0F, event.getFarPlaneDistance());
            event.setFarPlaneDistance(far);
            event.setNearPlaneDistance(far * 0.05F);
            event.setCanceled(true);
        }
    }

    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (stage != Stage.PLAYING) {
            return;
        }
        float t = time((float) event.getPartialTick());
        if (t >= SNAP_AT && t < SNAP_AT + 3.0F && !inTransit()) {
            float c = Mth.clamp(1.0F - (t - SNAP_AT - 0.3F) / 2.4F, 0.0F, 1.0F);
            event.setRed(Mth.lerp(c, event.getRed(), 0.9F));
            event.setGreen(Mth.lerp(c, event.getGreen(), 0.92F));
            event.setBlue(Mth.lerp(c, event.getBlue(), 0.95F));
        }
    }

    /** Hands the overworld level back once the server has landed the player. */
    public static void onLoggedOut() {
        reset();
    }
}
