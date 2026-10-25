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
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client side of the arrival cutscene. Owns the timeline (in seconds from the first frame in space),
 * the camera, the ship and crew drawing, sounds, particles and the letterboxed overlay.
 */
public final class CutscenePlayer {
    private static final ResourceLocation SCRIPT = ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "cutscene/arrival.json");

    // Timeline, seconds. Matches docs/arrival-cutscene-script.md; slow enough to read every line.
    private static final float SPARKS_AT = 62.0F;
    private static final float TROUBLE_AT = 66.8F;
    private static final float BREACH_AT = 79.6F;
    private static final float PODS_AT = 113.2F;
    private static final float PODS_SHOT_END = 117.7F;
    private static final float SNAP_AT = 119.6F;
    private static final float CHASE_AT = 122.6F;
    private static final float FINAL_INTERIOR_AT = 125.6F;
    private static final float IMPACT_AT = 127.6F;
    private static final float BANG_AT = SPARKS_AT + 3.2F;
    private static final float NAV_LOST_AT = 95.6F;
    private static final float ALARM_AGAIN_AT = CHASE_AT + 1.0F;
    /** Characters per second for the typed subtitles, and the longest a line stays up. */
    private static final float TYPE_SPEED = 25.0F;
    private static final float LINE_HOLD = 7.0F;
    private static final float SUBTITLE_SCALE = 2.0F;
    /** Just before impact the film cuts outside to watch the ship come in and hit, and stays on the wreck after. */
    private static final float CRASH_CAM_AT = IMPACT_AT - 1.6F;
    /** After impact the camera stays outside on the wreck for a few seconds before it fades out. */
    private static final float CRASH_FADE_AT = IMPACT_AT + 4.5F;
    private static final float END_AT = IMPACT_AT + 6.5F;
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
    /** Dialogue close-ups: a few seconds on each crew member the first time they speak in the cruise. */
    private static final List<Line> closeUps = new ArrayList<>();
    private static final int MAX_CLOSE_UPS = 4;
    private static final float CLOSE_UP_LENGTH = 3.6F;
    /** Timeline position in seconds. It follows the wall clock, not ticks, so a slow frame rate can't slow the film down. */
    private static double clock;
    private static long lastNanos;
    /** Test command only: keep the timeline still so a moment can be inspected. */
    private static boolean frozen;
    /** Ticks played, for things that repeat on a beat (alarm, typing blips). */
    private static int ticks;
    private static int settle;
    private static int skipHeld;
    private static int landingTicks;
    private static boolean snapSent;
    private static boolean endSent;
    private static boolean begun;
    /** Whether the view has been pointed down the new course since arriving over Atmos. */
    private static boolean facedCourse;
    private static Vec3 spaceCenter = Vec3.ZERO;
    private static Vec3 approachDir = new Vec3(1, 0, 0);
    private static float starTravel;
    private static float starTravelOld;
    private static ShipModel shipModel;
    private static ShipRenderer ship;
    /** What is left of the ship after impact: hull pieces thrown out of the blast. */
    private static final List<Debris> debris = new ArrayList<>();
    private static final float GRAVITY = 20.0F;
    private static final Crew crew = new Crew();
    private static final RandomSource random = RandomSource.create();
    private static final List<LoopSound> loops = new ArrayList<>();
    /**
     * The score, in two parts split at the snap: A runs from the first frame (and waits out the
     * transit hold in its own closing silence), B starts on the actual cut to blue sky. The music is
     * timed against the timeline constants above, so changing any of them means re-timing the score.
     */
    private static final ResourceLocation MUSIC_A = ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "music.arrival_a");
    private static final ResourceLocation MUSIC_B = ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "music.arrival_b");
    private static SoundInstance musicA;
    /**
     * Part A streams from disk and takes this long to start sounding (measured in the dev client),
     * where part B is preloaded and starts at once; the picture clock starts this much later to match.
     */
    private static final double MUSIC_A_LEAD = 0.14;
    private static SoundInstance musicB;
    /** Whether part B has been cued (once per run, at the cut to sky). */
    private static boolean snapCut;

    private record Line(float t, String speaker, String key) {}

    /** One piece of the ship flying out of the explosion. */
    /** One piece of the ship flying out of the explosion, falling until it hits real ground. */
    private static final class Debris {
        final BlockState state;
        final Vec3 from;
        final Vec3 velocity;
        final Vec3 axis;
        final float spin;
        final boolean smokes;
        /** How far along its flight it has been checked for touching the ground. */
        double checkedAge;
        /** Where it came to rest and when, or null while still flying. */
        Vec3 landedAt;
        double landedAge = -1.0;
        /** Went off the island's edge and fell out of the scene. */
        boolean gone;

        Debris(BlockState state, Vec3 from, Vec3 velocity, Vec3 axis, float spin, boolean smokes) {
            this.state = state;
            this.from = from;
            this.velocity = velocity;
            this.axis = axis;
            this.spin = spin;
            this.smokes = smokes;
        }
    }

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
            clock = payload.start();
            frozen = payload.hold();
            lastNanos = 0L;
            ticks = 0;
            skipHeld = 0;
            snapSent = false;
            endSent = false;
            starTravel = starTravelOld = 0.0F;
            begun = true;
            facedCourse = false;
            snapCut = false;
            stopMusic();
            stage = Stage.ENTERING;
        }
    }

    private static void loadScript() {
        lines.clear();
        closeUps.clear();
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
                Line line = new Line(o.get("t").getAsFloat(), speaker, o.get("key").getAsString());
                lines.add(line);
                boolean seen = closeUps.stream().anyMatch(c -> c.speaker.equals(line.speaker));
                if (!speaker.equals("ship") && !seen && closeUps.size() < MAX_CLOSE_UPS
                        && line.t >= 6.0F && line.t + CLOSE_UP_LENGTH < SPARKS_AT) {
                    closeUps.add(line);
                }
            }
        } catch (Exception e) {
            SkyloreIslands.LOGGER.warn("Couldn't read the arrival cutscene script", e);
        }
    }

    // ---- timeline ----

    private static float time(float partial) {
        if (stage != Stage.PLAYING || lastNanos == 0L || frozen) {
            return (float) clock;
        }
        // Between ticks, carry on smoothly with real time (capped so a stall doesn't run ahead).
        return (float) (clock + Math.min((Util.getNanos() - lastNanos) / 1.0e9, 0.1));
    }

    private static boolean inTransit() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == ArrivalCutscene.TRANSIT;
    }

    private static boolean interior(float t) {
        return (t >= 6.0F && t < SPARKS_AT) || (t >= TROUBLE_AT && t < PODS_AT + 0.3F)
                || (t >= PODS_SHOT_END && t < CHASE_AT) || (t >= FINAL_INTERIOR_AT && t < CRASH_CAM_AT);
    }

    /** What the crew all turn to look at: the dying lights, the breach, their consoles. */
    private static int reaction(float t) {
        if (t >= TROUBLE_AT && t < TROUBLE_AT + 2.0F) {
            return Crew.CEILING;
        }
        if (t >= BREACH_AT && t < BREACH_AT + 2.4F) {
            return Crew.AFT;
        }
        if (t >= NAV_LOST_AT && t < NAV_LOST_AT + 2.0F) {
            return Crew.CONSOLE;
        }
        return Crew.NOTHING;
    }

    // Mounted on the hull and pointing out from it: the side lamps on the walls facing port (+X) and
    // starboard (-X), the strobe standing on top of the antenna.
    private static final BlockState PORT_LIGHT = ShipModel.with(ShipModel.with(
            ShipModel.block("ad_astra:red_industrial_lamp", Blocks.REDSTONE_LAMP), "face", "wall"), "facing", "east");
    private static final BlockState STARBOARD_LIGHT = ShipModel.with(ShipModel.with(
            ShipModel.block("ad_astra:green_industrial_lamp", Blocks.VERDANT_FROGLIGHT), "face", "wall"), "facing", "west");
    private static final BlockState STROBE_LIGHT = ShipModel.with(
            ShipModel.block("ad_astra:white_industrial_lamp", Blocks.SEA_LANTERN), "face", "floor");

    /**
     * Navigation lights: red to port, green to starboard (each blinking off briefly, out of step),
     * and a white strobe on the antenna that flashes every second and a half.
     */
    private static void navLights(PoseStack pose, MultiBufferSource buffers, float t) {
        // Mounting plates flush on the trim ring (its outer face is RADIUS + 1 out on this row).
        double out = ShipModel.RADIUS + 0.8;
        if (t % 2.0F > 0.15F) {
            light(pose, buffers, PORT_LIGHT, 0.5 + out, -2.5, 0.5);
        }
        if ((t + 1.0F) % 2.0F > 0.15F) {
            light(pose, buffers, STARBOARD_LIGHT, 0.5 - out, -2.5, 0.5);
        }
        if (t % 1.5F < 0.12F) {
            light(pose, buffers, STROBE_LIGHT, 0.5, ShipModel.RADIUS + 4.3, 0.5);
        }
    }

    private static void light(PoseStack pose, MultiBufferSource buffers, BlockState state, double x, double y, double z) {
        pose.pushPose();
        pose.translate(x - 0.3, y - 0.3, z - 0.3);
        pose.scale(0.6F, 0.6F, 0.6F);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** The close-up on screen now, if any. */
    private static Line closeUp(float t) {
        for (Line line : closeUps) {
            if (t >= line.t + 0.15F && t < line.t + CLOSE_UP_LENGTH && aboard.contains(line.speaker)) {
                return line;
            }
        }
        return null;
    }

    /** How hard everything shakes, 0..1. */
    private static float shake(float t) {
        // Dead steady while cruising: any wobble before the trouble just reads as seasickness.
        float s = 0.0F;
        if (t >= TROUBLE_AT) {
            s = 0.15F + 0.25F * Mth.clamp((t - TROUBLE_AT) / (SNAP_AT - TROUBLE_AT), 0.0F, 1.0F);
        }
        if (t >= SNAP_AT) {
            s = 0.55F + 0.45F * Mth.clamp((t - SNAP_AT) / (IMPACT_AT - SNAP_AT), 0.0F, 1.0F);
        }
        if (t >= CRASH_CAM_AT) {
            // Outside, the camera only trembles until the ship hits, then takes the jolt.
            s = t < IMPACT_AT ? 0.12F : Math.max(0.0F, 1.0F - (t - IMPACT_AT) / 2.5F);
        }
        return Math.min(s, 1.0F);
    }

    /** Hard knocks on the hull, 0..~1: each event kicks at once and dies away over half a second. */
    private static float jolt(float t) {
        if (t < BANG_AT || t >= SNAP_AT) {
            return 0.0F;
        }
        float j = impulse(t, BANG_AT, 0.7F) + impulse(t, TROUBLE_AT, 1.0F) + impulse(t, BREACH_AT, 1.0F)
                + impulse(t, NAV_LOST_AT, 0.5F);
        for (int i = 0; i < 3; i++) {
            j += impulse(t, PODS_AT + i * 0.4F, 0.5F);
        }
        return Math.min(j, 1.2F);
    }

    private static float impulse(float t, float at, float strength) {
        float d = t - at;
        return d < 0.0F || d > 2.0F ? 0.0F : strength * (float) (Math.exp(-d * 5.0) * (1.0 - Math.exp(-d * 60.0)));
    }

    /** 0..1 bump while the breach pulls air (and everything loose) toward the back of the cabin. */
    private static float decompression(float t) {
        float d = t - BREACH_AT;
        return d < 0.0F || d > 1.6F ? 0.0F : Mth.sin(d / 1.6F * Mth.PI);
    }

    private static float brightness(float t) {
        if (t < TROUBLE_AT - 0.8F) {
            return 1.0F;
        }
        if (t >= SNAP_AT) {
            return 0.85F;
        }
        int step = (int) (t * 9.0F);
        float noise = (float) ((step * 2654435761L & 0xFFFF) / 65535.0);
        if (t < TROUBLE_AT) {
            // The lights stutter just before the surge...
            return noise < 0.5F ? 0.3F : 1.0F;
        }
        if (t < TROUBLE_AT + 0.6F) {
            // ...go out...
            return 0.08F;
        }
        // ...and come back as dim emergency lighting that drops out now and then.
        return noise < 0.07F ? 0.3F : 0.55F;
    }

    /** How red the cabin lighting is: emergency red through the trouble. */
    private static float emergencyRed(float t) {
        return t >= TROUBLE_AT + 0.6F && t < SNAP_AT ? 0.45F + 0.1F * alarm(t) : 0.0F;
    }

    private static float alarm(float t) {
        boolean on = (t >= TROUBLE_AT && t < SNAP_AT) || (t >= ALARM_AGAIN_AT && t < IMPACT_AT);
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
            // Not bolted to anything: a slow float.
            // Kept tiny: a camera that sways while all is calm reads as seasickness.
            return spaceCenter.add(0.0, Mth.sin(t * 0.4F) * 0.05, 0.0);
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

    /** 0..1 how far the descent has gone, for the wobble that grows as control goes. */
    private static float descent(float t) {
        return t < SNAP_AT || inTransit() ? 0.0F : Mth.clamp((t - SNAP_AT) / (IMPACT_AT - SNAP_AT), 0.0F, 1.0F);
    }

    /** Extra heading on top of the course: a lazy sway in space, a growing fishtail on the way down. */
    private static float yawWobble(float t) {
        return t < SNAP_AT || inTransit() ? Mth.sin(t * 0.21F) * 0.25F : Mth.sin(t * 1.3F) * 4.0F * descent(t);
    }

    /** Nose down is positive. In space a slow drift; after the snap the nose drops and it bucks. */
    private static float shipPitch(float t) {
        if (t < SNAP_AT || inTransit()) {
            return Mth.sin(t * 0.3F) * 0.2F;
        }
        float drop = Mth.clamp((t - SNAP_AT) / 1.5F, 0.0F, 1.0F);
        return 22.0F * drop + Mth.sin(t * 2.7F) * (2.0F + 5.0F * descent(t));
    }

    private static float shipRoll(float t) {
        if (t < SNAP_AT || inTransit()) {
            return Mth.sin(t * 0.25F + 1.0F) * 0.3F;
        }
        float k = descent(t);
        return Mth.sin(t * 1.9F) * (4.0F + 10.0F * k) + 6.0F * k;
    }

    /** Ship-local to world turn: heading, then pitch, then roll (same order as the render pose). */
    private static Quaternionf shipRotation(float t) {
        return new Quaternionf()
                .rotateY(-(shipYaw(t) + yawWobble(t)) * Mth.DEG_TO_RAD)
                .rotateX(shipPitch(t) * Mth.DEG_TO_RAD)
                .rotateZ(shipRoll(t) * Mth.DEG_TO_RAD);
    }

    private static Vec3 toWorld(Vec3 local, float t) {
        Vector3f v = shipRotation(t).transform(local.toVector3f());
        return shipCenter(t).add(v.x, v.y, v.z);
    }

    // ---- camera ----

    /** Where the camera is and which way it faces; yaw and pitch are approximate, for anything that asks. */
    public record CameraPose(Vec3 position, Quaternionf rotation, float yaw, float pitch) {}

    /** The camera for this frame. */
    public static CameraPose camera(float partial) {
        Minecraft mc = Minecraft.getInstance();
        float t = time(partial);
        float s = shake(t);
        Vec3 pos;
        float yaw;
        float pitch;
        Line close = closeUp(t);
        if (close != null) {
            // Cut in on whoever is talking: just in front of them and off to one side, at eye height.
            ShipModel.Seat chair = Crew.seat(aboard.indexOf(close.speaker));
            float a = chair.yaw() * Mth.DEG_TO_RAD;
            Vec3 facing = new Vec3(Mth.sin(a), 0.0, Mth.cos(a));
            Vec3 side = new Vec3(facing.z, 0.0, -facing.x);
            Vec3 eye = Crew.eye(aboard, close.speaker);
            // A slow push in over the shot.
            double push = (t - close.t) / CLOSE_UP_LENGTH * 0.25;
            Vec3 camLocal = eye.add(facing.scale(1.35 - push)).add(side.scale(0.55)).add(0.0, 0.05, 0.0);
            pos = toWorld(camLocal, t);
            Vec3 d = toWorld(eye.add(0.0, -0.1, 0.0), t).subtract(pos);
            yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            Quaternionf rot = new Quaternionf().rotationYXZ(Mth.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, 0.0F);
            return new CameraPose(pos, rot, yaw, pitch);
        }
        if (interior(t)) {
            float j = jolt(t);
            float pull = decompression(t);
            // Knocks throw the head down and sideways; the breach drags it toward the back.
            Vec3 eye = ShipModel.PLAYER_EYE.add(Mth.sin(t * 31.0F) * 0.06 * j, -0.12 * j, -0.25 * pull);
            pos = toWorld(eye, t);
            // Strapped in: the cabin stays put around you and the world outside tilts. The free look is
            // relative to the ship, so take the ship's whole turn and look around inside it.
            float relYaw = mc.player.getViewYRot(partial) - shipYaw(t);
            float relPitch = mc.player.getViewXRot(partial) + 4.0F * j;
            Quaternionf rot = shipRotation(t)
                    .rotateY(-relYaw * Mth.DEG_TO_RAD)
                    .rotateY(Mth.PI)
                    .rotateX(-relPitch * Mth.DEG_TO_RAD)
                    .rotateZ(-(s * noise(t, 3.0F) * 3.0F + 3.0F * j * Mth.sin(t * 23.0F)) * Mth.DEG_TO_RAD)
                    .rotateY(-s * noise(t, 1.0F) * 2.5F * Mth.DEG_TO_RAD)
                    .rotateX(-s * noise(t, 2.0F) * 2.0F * Mth.DEG_TO_RAD);
            return new CameraPose(pos, rot, mc.player.getViewYRot(partial), relPitch + shipPitch(t));
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
            } else if (t >= CRASH_CAM_AT) {
                // Beyond the crater, looking back up the approach: the ship comes down toward the camera
                // and slams in front of it; then hold on the wreck, drifting closer.
                Vec3 d = travel();
                Vec3 flat = new Vec3(d.x, 0.0, d.z).normalize();
                Vec3 side = flat.cross(new Vec3(0, 1, 0)).normalize();
                Vec3 wreck = approachEnd();
                float u = Mth.clamp((t - IMPACT_AT) / (END_AT - IMPACT_AT), 0.0F, 1.0F);
                pos = wreck.add(flat.scale(26.0 - 7.0 * u)).add(side.scale(9.0)).add(0, 9.0 - 2.0 * u, 0);
                look = center.add(0.0, -1.0, 0.0);
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
        // Same construction as Camera.setRotation.
        Quaternionf rot = new Quaternionf().rotationYXZ(Mth.PI - yaw * Mth.DEG_TO_RAD, -pitch * Mth.DEG_TO_RAD, -roll * Mth.DEG_TO_RAD);
        return new CameraPose(pos, rot, yaw, pitch);
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
                    player.setYRot(0.0F);
                    player.setXRot(8.0F);
                    player.yRotO = 0.0F;
                    player.xRotO = 8.0F;
                    stage = Stage.PLAYING;
                    startLoops();
                    if (clock < 0.25) {
                        musicA = playMusic(MUSIC_A);
                        clock -= MUSIC_A_LEAD;
                    }
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
        mc.getToasts().clear();
        mc.getMusicManager().stopPlaying();
        long now = Util.getNanos();
        double elapsed = lastNanos == 0L ? 0.05 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        // Only a pause stops the clock: it is also the only thing that pauses the music, so any other
        // screen (chat, say) must not let the picture fall behind the score.
        if (mc.isPaused()) {
            return;
        }
        float before = (float) clock;
        // Hold the clock at the snap until the island level has arrived, so the cut is exact.
        if (before >= SNAP_AT - 0.05F && inTransit()) {
            if (!snapSent) {
                snapSent = true;
                PacketDistributor.sendToServer(new CutscenePayloads.ToServer(CutscenePayloads.SNAP));
            }
            return;
        }
        if (!snapCut && before >= SNAP_AT - 0.05F && before < SNAP_AT + 0.5F && !inTransit()) {
            // The island level is in: this tick is the cut to sky. Pin the clock to the snap exactly and
            // start part B with it, so the impact lands on the score's hit 8 seconds later.
            snapCut = true;
            clock = SNAP_AT;
            before = SNAP_AT - 0.001F;
            elapsed = 0.0;
            musicB = playMusic(MUSIC_B);
        }
        if (!frozen) {
            // Uncapped: the music keeps playing through a hitch, so the picture has to catch up with it.
            clock = Math.min(clock + elapsed, before < SNAP_AT && inTransit() ? SNAP_AT - 0.05 : Double.MAX_VALUE);
        }
        ticks++;
        float t = (float) clock;

        starTravelOld = starTravel;
        float speed = t < PODS_AT ? 0.5F : Mth.lerp(Mth.clamp((t - PODS_AT) / 4.0F, 0.0F, 1.0F), 0.5F, 0.08F);
        starTravel += speed;

        boolean arrived = !facedCourse && t >= SNAP_AT && !inTransit();
        if (arrived) {
            facedCourse = true;
        }
        if (interior(t) && !interior(before) || arrived) {
            // Every cut back inside starts looking out the front window.
            player.setYRot(shipYaw(t));
            player.setXRot(8.0F);
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
        }

        cues(mc, before, t);
        particles(mc, before, t);
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
        stopMusic();
        stage = Stage.LANDING;
        landingTicks = 0;
    }

    private static void hold(LocalPlayer player) {
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    private static void reset() {
        stopLoops();
        stopMusic();
        stage = Stage.IDLE;
        ship = null;
        debris.clear();
    }

    private static void cues(Minecraft mc, float before, float t) {
        // Cruise: the ship's consoles chirp now and then.
        if (t >= 6.0F && t < TROUBLE_AT && every(before, t, 4.3F, 0.0F)) {
            ui(SoundEvents.AMETHYST_BLOCK_CHIME, 1.6F + random.nextFloat() * 0.3F, 0.3F);
            ui(SoundEvents.UI_BUTTON_CLICK.value(), 1.8F, 0.08F);
        }
        // Sparks crawling along the hull seam, a rising whine, then the bang.
        for (float at : new float[]{0.3F, 1.0F, 1.7F, 2.4F, 2.9F}) {
            if (crossed(before, t, SPARKS_AT + at)) {
                ui(SoundEvents.FIRE_EXTINGUISH, 1.6F + random.nextFloat() * 0.3F, 0.35F);
            }
        }
        if (crossed(before, t, SPARKS_AT + 1.5F)) {
            ui(SoundEvents.BEACON_POWER_SELECT, 0.5F, 0.8F);
        }
        if (crossed(before, t, BANG_AT)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 0.7F);
            ui(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 0.5F, 0.8F);
        }
        // Reactor surge: power dips, steam vents.
        if (crossed(before, t, TROUBLE_AT)) {
            ui(SoundEvents.BEACON_DEACTIVATE, 0.5F, 1.0F);
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.4F, 0.6F);
        }
        if (crossed(before, t, TROUBLE_AT + 1.5F)) {
            ui(SoundEvents.LAVA_EXTINGUISH, 0.6F, 0.9F);
        }
        // Hull breach: a blast, then the bulkhead slides shut.
        if (crossed(before, t, BREACH_AT)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.8F, 0.5F);
            ui(SoundEvents.GLASS_BREAK, 0.6F, 0.8F);
        }
        if (crossed(before, t, BREACH_AT + 1.2F)) {
            ui(SoundEvents.PISTON_CONTRACT, 0.5F, 0.9F);
        }
        if (crossed(before, t, BREACH_AT + 1.6F)) {
            ui(SoundEvents.IRON_DOOR_CLOSE, 0.6F, 1.0F);
        }
        // Throughout the trouble: the hull clangs under stress and conduits spit sparks.
        if (t >= TROUBLE_AT && t < SNAP_AT) {
            if (every(before, t, 3.7F, 0.9F)) {
                ui(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 0.4F + random.nextFloat() * 0.2F, 0.35F);
            }
            if (every(before, t, 2.3F, 0.4F)) {
                ui(SoundEvents.FIRE_EXTINGUISH, 1.4F + random.nextFloat() * 0.5F, 0.25F);
            }
            if (every(before, t, 6.1F, 2.0F)) {
                ui(SoundEvents.LAVA_EXTINGUISH, 0.7F, 0.35F);
            }
        }
        // Nav array dies: the power winds down.
        if (crossed(before, t, NAV_LOST_AT)) {
            ui(SoundEvents.BEACON_DEACTIVATE, 0.5F, 0.6F);
        }
        for (int i = 0; i < 3; i++) {
            if (crossed(before, t, NAV_LOST_AT + 0.25F * i)) {
                ui(SoundEvents.NOTE_BLOCK_BASS.value(), 1.0F - 0.2F * i, 0.6F);
            }
        }
        // Three cargo pods: clamps let go, each one clunks away.
        for (int i = 0; i < 3; i++) {
            if (crossed(before, t, PODS_AT - 0.15F + i * 0.4F)) {
                ui(SoundEvents.PISTON_EXTEND, 0.6F, 0.7F);
            }
            if (crossed(before, t, PODS_AT + i * 0.4F)) {
                ui(SoundEvents.ANVIL_PLACE, 0.5F, 0.5F);
                if (mc.level != null) {
                    // A puff of gas as the clamps blow.
                    Vec3 at = toWorld(new Vec3(-2.5 + 2.5 * i, -ShipModel.RADIUS, -ShipModel.RADIUS + 1.0), t);
                    for (int k = 0; k < 14; k++) {
                        mc.level.addParticle(ParticleTypes.CLOUD, at.x, at.y, at.z,
                                random.nextGaussian() * 0.15, -0.1 + random.nextGaussian() * 0.1, -0.2 + random.nextGaussian() * 0.1);
                    }
                }
            }
        }
        if (crossed(before, t, PODS_AT + 1.2F)) {
            ui(SoundEvents.FIRECHARGE_USE, 0.5F, 0.6F);
        }
        // Coming down: bits of the ship coming apart.
        if (t >= SNAP_AT && t < IMPACT_AT && every(before, t, 1.6F, 0.5F)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 1.3F + random.nextFloat() * 0.3F, 0.25F);
            ui(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 0.6F, 0.3F);
        }
        if (crossed(before, t, CRASH_CAM_AT)) {
            ui(SoundEvents.FIRECHARGE_USE, 0.5F, 0.8F);
        }
        // Impact: the blast, glass and metal giving way, earth thrown up.
        if (crossed(before, t, IMPACT_AT)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.5F, 1.0F);
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.7F, 1.0F);
            ui(SoundEvents.IRON_GOLEM_DAMAGE, 0.5F, 1.0F);
            ui(SoundEvents.GLASS_BREAK, 0.8F, 0.9F);
            ui(SoundEvents.STONE_BREAK, 0.6F, 1.0F);
            crashBurst(mc);
            PacketDistributor.sendToServer(new CutscenePayloads.ToServer(CutscenePayloads.IMPACT));
        }
        if (crossed(before, t, IMPACT_AT + 0.3F)) {
            ui(SoundEvents.GLASS_BREAK, 1.1F, 0.6F);
        }
        // The reactor goes up a moment after: two more blasts inside the wreck.
        for (float at : new float[]{0.2F, 0.45F}) {
            if (crossed(before, t, IMPACT_AT + at) && mc.level != null) {
                Vec3 w = approachEnd().add(random.nextGaussian() * 2, 1.0, random.nextGaussian() * 2);
                mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, w.x, w.y, w.z, 0, 0, 0);
                ui(SoundEvents.GENERIC_EXPLODE.value(), 0.6F + at, 0.9F);
            }
        }
        // Bigger pieces thud and clank as they come down around the crater.
        if (t >= IMPACT_AT && t < IMPACT_AT + 4.0F) {
            int heard = 0;
            for (int i = 0; i < debris.size() && heard < 2; i += 9) {
                Debris piece = debris.get(i);
                debrisAt(piece, t - IMPACT_AT);
                if (piece.landedAge >= 0.0 && crossed(before - IMPACT_AT, t - IMPACT_AT, (float) piece.landedAge)) {
                    ui(i % 2 == 0 ? SoundEvents.IRON_TRAPDOOR_CLOSE : SoundEvents.STONE_FALL,
                            0.6F + random.nextFloat() * 0.3F, 0.35F);
                    heard++;
                }
            }
        }
        if (crossed(before, t, IMPACT_AT + 0.6F)) {
            ui(SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 0.6F);
            ui(SoundEvents.GRAVEL_BREAK, 0.6F, 0.8F);
        }
        if (crossed(before, t, IMPACT_AT + 1.4F)) {
            ui(SoundEvents.CHAIN_BREAK, 0.5F, 0.5F);
        }
        // The wreck settling and burning.
        if (t >= IMPACT_AT + 1.0F && t < CRASH_FADE_AT + 1.0F) {
            if (every(before, t, 0.9F, 0.0F)) {
                ui(SoundEvents.CAMPFIRE_CRACKLE, 0.8F + random.nextFloat() * 0.4F, 0.6F);
            }
            if (every(before, t, 1.7F, 0.6F)) {
                ui(SoundEvents.LAVA_POP, 0.8F, 0.5F);
            }
        }
        if (crossed(before, t, IMPACT_AT + 0.9F)) {
            ui(SoundEvents.IRON_GOLEM_DAMAGE, 0.6F, 0.6F);
        }
        if (crossed(before, t, IMPACT_AT + 2.6F)) {
            ui(SoundEvents.CHAIN_BREAK, 0.4F, 0.35F);
        }
        if (crossed(before, t, CRASH_FADE_AT + 1.3F)) {
            ui(SoundEvents.NOTE_BLOCK_BIT.value(), 2.0F, 0.4F);
        }
        // Alarm: two-tone, every 0.6 s.
        if (alarm(t) > 0.0F || (t >= TROUBLE_AT && t < SNAP_AT)) {
            if (ticks % 12 == 0) {
                ui(SoundEvents.NOTE_BLOCK_BIT.value(), (ticks / 12) % 2 == 0 ? 1.4F : 1.0F, 0.35F);
            }
        }
        // Whoever is talking babbles a syllable for each letter as it types out.
        Line line = currentLine(t);
        if (line != null) {
            String text = Component.translatable(line.key).getString();
            int now = Math.min(text.length(), (int) ((t - line.t) * TYPE_SPEED));
            int was = Math.max(0, (int) ((before - line.t) * TYPE_SPEED));
            for (int i = now - 1; i >= was && i >= 0; i--) {
                if (Character.isLetter(text.charAt(i))) {
                    speak(line.speaker, text.charAt(i), text, i);
                    break;
                }
            }
        }
    }

    /** True once every {@code period} seconds (shifted by {@code phase}) as the timeline passes it. */
    private static boolean every(float before, float t, float period, float phase) {
        return Math.floor((t - phase) / period) != Math.floor((before - phase) / period);
    }

    private static boolean crossed(float before, float t, float at) {
        return before < at && t >= at;
    }

    /** Outside in space there's no air to carry sound: everything drops to a muffled third. */
    private static float mix(float t) {
        return !interior(t) && t < SNAP_AT ? 0.3F : 1.0F;
    }

    private static void ui(SoundEvent sound, float pitch, float volume) {
        voice(sound, pitch, volume * mix((float) clock));
    }

    /** Speech blips play at full volume whatever the shot. */
    private static void voice(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** Each species' voice: deep Fortis, squeaky little Enoki, flat Automaton. */
    private static float voicePitch(String speaker) {
        return switch (speaker) {
            case "fortis" -> 0.85F;
            case "enoki" -> 1.45F;
            case "teknari" -> 1.2F;
            case "beryl" -> 1.05F;
            case "automaton" -> 0.7F;
            case "tenebris" -> 0.8F;
            default -> 1.0F;
        };
    }

    /**
     * Animal Crossing style babble: the letter just typed, as a quick syllable in the speaker's voice.
     * Questions lift at the end and shouted lines come out louder. The ship's computer just beeps.
     */
    private static void speak(String speaker, char c, String text, int index) {
        if (speaker.equals("ship")) {
            blip(speaker);
            return;
        }
        char letter = Character.toLowerCase(c);
        if (letter < 'a' || letter > 'z') {
            // Accented letters say their base letter; other scripts (Mandarin) still get a syllable.
            String plain = java.text.Normalizer.normalize(String.valueOf(letter), java.text.Normalizer.Form.NFD);
            letter = plain.charAt(0) >= 'a' && plain.charAt(0) <= 'z' ? plain.charAt(0) : (char) ('a' + Math.floorMod(c, 26));
        }
        float pitch = voicePitch(speaker);
        if (!speaker.equals("automaton")) {
            pitch *= 1.0F + (random.nextFloat() - 0.5F) * 0.14F;
        }
        String end = text.strip();
        boolean nearEnd = index > text.length() * 0.75;
        if (nearEnd && end.endsWith("?")) {
            pitch *= 1.15F;
        }
        float volume = end.endsWith("!") ? 0.65F : 0.45F;
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "voice." + letter), SoundSource.MASTER,
                volume, Mth.clamp(pitch, 0.5F, 2.0F), SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
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
        voice(sound, pitch + (random.nextFloat() - 0.5F) * 0.1F, 0.25F);
    }

    /** 1 at cruising speed, down toward 0.15 as the retros brake. */
    private static float cruise(float t) {
        return t < PODS_AT + 1.2F ? 1.0F : Mth.lerp(Mth.clamp((t - PODS_AT - 1.2F) / 4.0F, 0.0F, 1.0F), 1.0F, 0.15F);
    }

    private static final Vec3[] CONSOLES = {new Vec3(-2.5, -1.5, 5.5), new Vec3(3.5, -1.5, 5.5),
            new Vec3(-5.5, -1.5, 0.5), new Vec3(6.5, -1.5, 0.5), new Vec3(0.5, -1.5, -4.5)};

    private static void particles(Minecraft mc, float before, float t) {
        if (mc.level == null) {
            return;
        }
        Vec3 center = shipCenter(t);
        if (t < SNAP_AT && inTransit()) {
            // Dust and ice drifting past show how fast the ship goes; it thins as the retros brake.
            float speed = cruise(t);
            int count = Math.max(1, (int) (5 * speed));
            for (int i = 0; i < count; i++) {
                Vec3 at = center.add(random.nextGaussian() * 14, random.nextGaussian() * 9, 28 + random.nextDouble() * 6);
                mc.level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true, at.x, at.y, at.z, 0, 0, -2.6 * speed);
            }
            // Attitude thrusters trim the drift now and then.
            if (t >= 2.0F && t < TROUBLE_AT && every(before, t, 7.3F, 1.1F)) {
                Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.5, random.nextGaussian()).normalize();
                Vec3 nozzle = toWorld(ShipModel.hullPoint(dir.x, dir.y, dir.z), t);
                for (int i = 0; i < 12; i++) {
                    mc.level.addParticle(ParticleTypes.CLOUD, nozzle.x, nozzle.y, nozzle.z,
                            dir.x * 0.35 + random.nextGaussian() * 0.04, dir.y * 0.35 + random.nextGaussian() * 0.04,
                            dir.z * 0.35 + random.nextGaussian() * 0.04);
                }
                ui(SoundEvents.FIRE_EXTINGUISH, 2.0F, 0.12F);
            }
        }
        if (t < SNAP_AT && inTransit()) {
            if (t < PODS_AT + 1.2F) {
                // The main engine's faint blue exhaust while cruising.
                Vec3 nozzle = toWorld(new Vec3(0.5 + random.nextGaussian() * 0.5, 0.5 + random.nextGaussian() * 0.5, -ShipModel.RADIUS - 0.8), t);
                for (int i = 0; i < 2; i++) {
                    mc.level.addAlwaysVisibleParticle(ParticleTypes.SOUL_FIRE_FLAME, true, nozzle.x, nozzle.y, nozzle.z,
                            random.nextGaussian() * 0.02, random.nextGaussian() * 0.02, -0.35);
                }
            } else {
                // Braking: four retro-thrusters around the canopy fire forward against the ship's speed,
                // where the crew can see them through the front window.
                for (double[] n : new double[][]{{0.32, 0.18}, {-0.32, 0.18}, {0.32, -0.12}, {-0.32, -0.12}}) {
                    Vec3 nozzle = toWorld(ShipModel.hullPoint(n[0], n[1], 0.8).add(0.5, 0.5, 0.5), t);
                    for (int i = 0; i < 5; i++) {
                        mc.level.addAlwaysVisibleParticle(ParticleTypes.FLAME, true, nozzle.x, nozzle.y, nozzle.z,
                                n[0] * 0.15 + random.nextGaussian() * 0.04, n[1] * 0.15 + random.nextGaussian() * 0.04, 0.7 + random.nextDouble() * 0.3);
                    }
                    if (random.nextInt(3) == 0) {
                        mc.level.addParticle(ParticleTypes.LARGE_SMOKE, nozzle.x, nozzle.y, nozzle.z, 0, 0, 0.4);
                    }
                }
            }
        }
        if (t >= TROUBLE_AT && t < SNAP_AT) {
            // Inside: consoles arc on every knock, steam leaks from the floor vents, and the breach
            // drags fog toward the back of the cabin.
            float j = jolt(t);
            if (j > 0.25F || random.nextInt(25) == 0) {
                Vec3 console = toWorld(CONSOLES[random.nextInt(CONSOLES.length)], t);
                for (int i = 0; i < 1 + (int) (j * 8); i++) {
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, console.x, console.y, console.z,
                            random.nextGaussian() * 0.15, random.nextDouble() * 0.15, random.nextGaussian() * 0.15);
                }
            }
            if (random.nextInt(3) == 0) {
                Vec3 vent = toWorld(new Vec3(random.nextBoolean() ? -4.5 : 5.5, -2.9, random.nextBoolean() ? -2.5 : 3.5), t);
                mc.level.addParticle(ParticleTypes.CLOUD, vent.x, vent.y, vent.z, 0, 0.06, 0);
            }
            float pull = decompression(t);
            if (pull > 0.0F) {
                for (int i = 0; i < (int) (8 * pull); i++) {
                    Vec3 at = toWorld(new Vec3(random.nextGaussian() * 3, -1.5 + random.nextGaussian(), 2 + random.nextDouble() * 4), t);
                    mc.level.addParticle(ParticleTypes.CLOUD, at.x, at.y, at.z, 0, -0.05, -0.55);
                }
            }
        }
        if (t >= BANG_AT && t < SNAP_AT) {
            // Deck 2 keeps venting through the split seam.
            Vec3 seam = ShipModel.hullPoint(0.6, 0.35, 0.7);
            Vec3 at = toWorld(seam, t);
            Vec3 out = seam.normalize();
            for (int i = 0; i < 2; i++) {
                mc.level.addParticle(ParticleTypes.CLOUD, at.x, at.y, at.z,
                        out.x * 0.5 + random.nextGaussian() * 0.05, out.y * 0.5 + random.nextGaussian() * 0.05,
                        out.z * 0.5 + random.nextGaussian() * 0.05);
            }
        }
        if (t >= SPARKS_AT && t < SNAP_AT) {
            Vec3 hull = center.add(ShipModel.hullPoint(0.6, 0.35, 0.7));
            int sparks = t < TROUBLE_AT ? 4 : 1;
            for (int i = 0; i < sparks; i++) {
                mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, hull.x, hull.y, hull.z,
                        random.nextGaussian() * 0.3, random.nextGaussian() * 0.3, random.nextGaussian() * 0.3);
            }
            if (t >= BANG_AT) {
                mc.level.addParticle(ParticleTypes.CLOUD, hull.x, hull.y, hull.z, 0.15, 0.05, 0.1);
            }
            if (t >= BANG_AT && t < BANG_AT + 0.1F) {
                mc.level.addParticle(ParticleTypes.EXPLOSION, hull.x, hull.y, hull.z, 0, 0, 0);
            }
        }
        if (t >= IMPACT_AT && t < IMPACT_AT + 3.0F) {
            // Burning pieces trail smoke as they fly.
            for (Debris piece : debris) {
                Vec3 at = debrisAt(piece, t - IMPACT_AT);
                if (piece.smokes && piece.landedAt == null && !piece.gone) {
                    mc.level.addParticle(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 0, 0.02, 0);
                    mc.level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, 0, 0, 0);
                }
            }
        }
        if (t >= IMPACT_AT && t < END_AT) {
            // Burning pieces keep smouldering where they came down.
            for (Debris piece : debris) {
                if (piece.smokes && piece.landedAt != null && random.nextInt(4) == 0) {
                    Vec3 at = piece.landedAt;
                    mc.level.addParticle(ParticleTypes.SMOKE, at.x, at.y + 0.4, at.z, 0, 0.04, 0);
                    if (random.nextInt(3) == 0) {
                        mc.level.addParticle(ParticleTypes.FLAME, at.x, at.y + 0.3, at.z, 0, 0.01, 0);
                    }
                }
            }
        }
        if (t >= IMPACT_AT && t < END_AT) {
            // The wreck burns: smoke columns, fire and the odd spark.
            Vec3 wreck = approachEnd();
            for (int i = 0; i < 4; i++) {
                double x = wreck.x + random.nextGaussian() * 4;
                double z = wreck.z + random.nextGaussian() * 4;
                mc.level.addAlwaysVisibleParticle(i == 0 ? ParticleTypes.CAMPFIRE_SIGNAL_SMOKE : ParticleTypes.LARGE_SMOKE, true,
                        x, wreck.y + random.nextDouble() * 4, z, 0, 0.08, 0);
                mc.level.addParticle(ParticleTypes.FLAME, x, wreck.y - 1 + random.nextDouble() * 3, z, 0, 0.03, 0);
            }
            if (random.nextInt(3) == 0) {
                mc.level.addParticle(ParticleTypes.LAVA, wreck.x + random.nextGaussian() * 3, wreck.y + 2, wreck.z + random.nextGaussian() * 3, 0, 0, 0);
            }
        }
        if (t >= SNAP_AT && t < IMPACT_AT) {
            // Re-entry heat: fire blooming off the nose and streaming back past the windows.
            for (int i = 0; i < 6; i++) {
                Vec3 nose = toWorld(new Vec3(random.nextGaussian() * 3, random.nextGaussian() * 3, ShipModel.RADIUS + 1.0), t);
                mc.level.addParticle(i % 3 == 0 ? ParticleTypes.LAVA : ParticleTypes.FLAME, nose.x, nose.y, nose.z, 0, 0, 0);
            }
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

    /** Breaks the ship into pieces flung out of the blast: about one in five hull blocks. */
    private static void buildDebris() {
        debris.clear();
        if (shipModel == null) {
            shipModel = new ShipModel();
        }
        Vec3 center = approachEnd();
        float yaw = shipYaw(IMPACT_AT) * Mth.DEG_TO_RAD;
        RandomSource r = RandomSource.create(stand.asLong());
        for (Map.Entry<BlockPos, BlockState> entry : shipModel.blocks().entrySet()) {
            BlockPos local = entry.getKey();
            double d = Math.sqrt(local.distSqr(BlockPos.ZERO));
            if (d < ShipModel.RADIUS - 1.5 || r.nextInt(5) != 0) {
                continue;
            }
            Vec3 at = Vec3.atCenterOf(local).yRot(-yaw);
            Vec3 out = at.normalize();
            double speed = 6.0 + r.nextDouble() * 12.0;
            Vec3 velocity = new Vec3(out.x * speed, 5.0 + r.nextDouble() * 11.0 + Math.max(0.0, out.y) * 6.0, out.z * speed);
            Vec3 axis = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            debris.add(new Debris(entry.getValue(), center.add(at), velocity, axis, 200.0F + r.nextFloat() * 500.0F, r.nextInt(6) == 0));
        }
    }

    /** Where a piece would be {@code age} seconds after the blast if nothing stopped it. */
    private static Vec3 flight(Debris piece, double age) {
        return new Vec3(piece.from.x + piece.velocity.x * age,
                piece.from.y + piece.velocity.y * age - 0.5 * GRAVITY * age * age,
                piece.from.z + piece.velocity.z * age);
    }

    /**
     * Where a piece is {@code age} seconds after the blast: flying, then resting wherever it first came
     * down on something solid in the world (the fresh crater, a slope, a tree), never mid-air.
     */
    private static Vec3 debrisAt(Debris piece, float age) {
        Minecraft mc = Minecraft.getInstance();
        if (piece.landedAt == null && !piece.gone && mc.level != null) {
            // Step through the flight in small slices so fast pieces can't skip through the ground.
            double a = piece.checkedAge;
            for (; a < age; a += 0.02) {
                Vec3 pos = flight(piece, a);
                if (pos.y < approachEnd().y - 70.0) {
                    piece.gone = true;
                    break;
                }
                boolean falling = piece.velocity.y - GRAVITY * a < 0.0;
                BlockPos under = BlockPos.containing(pos.x, pos.y - 0.5, pos.z);
                BlockState below = mc.level.getBlockState(under);
                if (falling && a > 0.1) {
                    var shape = below.getCollisionShape(mc.level, under);
                    if (!shape.isEmpty()) {
                        piece.landedAt = new Vec3(pos.x, under.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) + 0.5, pos.z);
                        piece.landedAge = a;
                        break;
                    }
                }
            }
            piece.checkedAge = a;
        }
        return piece.landedAt != null ? piece.landedAt : flight(piece, age);
    }

    private static void renderDebris(float t, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        if (debris.isEmpty()) {
            buildDebris();
        }
        Minecraft mc = Minecraft.getInstance();
        float age = t - IMPACT_AT;
        for (Debris piece : debris) {
            Vec3 at = debrisAt(piece, age);
            if (piece.gone) {
                continue;
            }
            float tumble = (float) (piece.landedAge >= 0.0 ? piece.landedAge : age) * piece.spin;
            PoseStack pose = new PoseStack();
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            pose.mulPose(Axis.of(piece.axis.toVector3f()).rotationDegrees(tumble));
            pose.translate(-0.5F, -0.5F, -0.5F);
            int light = mc.level == null ? LightTexture.FULL_BRIGHT : LevelRenderer.getLightColor(mc.level, BlockPos.containing(at));
            mc.getBlockRenderer().renderSingleBlock(piece.state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        }
    }

    /** The moment of impact: a blast, and earth and hull plates thrown into the air. */
    private static void crashBurst(Minecraft mc) {
        if (mc.level == null) {
            return;
        }
        Vec3 wreck = approachEnd();
        buildDebris();
        // The blast flattens out along the ground in a ring of dust.
        double ground = wreck.y - 2.5;
        for (int i = 0; i < 90; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double speed = 0.8 + random.nextDouble() * 0.7;
            mc.level.addAlwaysVisibleParticle(i % 3 == 0 ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.CLOUD, true,
                    wreck.x + Math.cos(a) * 2, ground, wreck.z + Math.sin(a) * 2,
                    Math.cos(a) * speed, 0.03 + random.nextDouble() * 0.05, Math.sin(a) * speed);
        }
        mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, wreck.x, wreck.y, wreck.z, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            mc.level.addParticle(ParticleTypes.FLAME, wreck.x + random.nextGaussian() * 2, wreck.y + random.nextGaussian() * 2,
                    wreck.z + random.nextGaussian() * 2, random.nextGaussian() * 0.4, 0.2 + random.nextDouble() * 0.4, random.nextGaussian() * 0.4);
        }
        BlockState[] debris = {Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), SkyloreBlocks.SHIP_HULL.get().defaultBlockState()};
        for (int i = 0; i < 120; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double speed = 0.3 + random.nextDouble() * 0.6;
            mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, debris[i % debris.length]),
                    wreck.x + Math.cos(a) * 2, wreck.y, wreck.z + Math.sin(a) * 2,
                    Math.cos(a) * speed, 0.5 + random.nextDouble() * 0.8, Math.sin(a) * speed);
        }
        for (int i = 0; i < 30; i++) {
            mc.level.addAlwaysVisibleParticle(ParticleTypes.LARGE_SMOKE, true, wreck.x + random.nextGaussian() * 3,
                    wreck.y + random.nextDouble() * 3, wreck.z + random.nextGaussian() * 3,
                    random.nextGaussian() * 0.2, 0.15, random.nextGaussian() * 0.2);
        }
    }

    // ---- score ----

    /** On the music channel, so the player's music volume applies; not muffled outside like the effects. */
    private static SoundInstance playMusic(ResourceLocation part) {
        SoundInstance music = new SimpleSoundInstance(part, SoundSource.MUSIC, 1.0F, 1.0F,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
        Minecraft.getInstance().getSoundManager().play(music);
        return music;
    }

    private static void stopMusic() {
        var sounds = Minecraft.getInstance().getSoundManager();
        if (musicA != null) {
            sounds.stop(musicA);
            musicA = null;
        }
        if (musicB != null) {
            sounds.stop(musicB);
            musicB = null;
        }
    }

    // ---- sounds that loop ----

    private static void startLoops() {
        stopLoops();
        // Ship's hum and air handling while all is well.
        loops.add(new LoopSound(SoundEvents.BEACON_AMBIENT, 0.5F, t -> t < SNAP_AT ? 0.5F : 0.0F));
        loops.add(new LoopSound(SoundEvents.CONDUIT_AMBIENT, 0.7F, t -> t < TROUBLE_AT ? 0.35F : t < SNAP_AT ? 0.15F : 0.0F));
        // Hull rumble that builds through the trouble and rattles everything on the way down.
        loops.add(new LoopSound(SoundEvents.MINECART_RIDING, 0.5F, t -> t < TROUBLE_AT ? 0.0F
                : t < SNAP_AT ? 0.15F + 0.45F * (t - TROUBLE_AT) / (SNAP_AT - TROUBLE_AT)
                : t < IMPACT_AT ? 1.0F : 0.0F));
        // Air screaming out of the breach until the bulkhead seals.
        loops.add(new LoopSound(SoundEvents.ELYTRA_FLYING, 1.4F, t -> t >= BREACH_AT && t < BREACH_AT + 1.6F ? 0.7F : 0.0F));
        // Retro thrusters fighting to brake.
        loops.add(new LoopSound(SoundEvents.BLAZE_BURN, 0.6F, t -> t >= PODS_AT + 1.2F && t < SNAP_AT ? 0.7F : 0.0F));
        // Atmosphere: wind, then fire on the hull and at the wreck.
        loops.add(new LoopSound(SoundEvents.ELYTRA_FLYING, 1.0F, t -> t >= SNAP_AT && t < IMPACT_AT ? 1.0F : 0.0F));
        loops.add(new LoopSound(SoundEvents.FIRE_AMBIENT, 0.8F, t -> t >= SNAP_AT && t < CRASH_FADE_AT + 1.0F ? 1.0F : 0.0F));
        for (LoopSound loop : loops) {
            Minecraft.getInstance().getSoundManager().play(loop);
        }
    }

    private static void stopLoops() {
        loops.forEach(LoopSound::finish);
        loops.clear();
    }

    /** How loud a looping sound is at each moment of the timeline. */
    private interface Envelope {
        float at(float t);
    }

    private static final class LoopSound extends AbstractTickableSoundInstance {
        private final Envelope envelope;

        LoopSound(SoundEvent sound, float pitch, Envelope envelope) {
            super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.pitch = pitch;
            this.volume = 0.0001F;
            this.envelope = envelope;
        }

        void update(float t) {
            volume = Math.max(envelope.at(t) * mix(t), 0.0001F);
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
        if (t >= CRASH_FADE_AT + 1.0F) {
            return;
        }
        if (ship == null) {
            shipModel = new ShipModel();
            ship = new ShipRenderer(shipModel);
        }
        Vec3 cam = event.getCamera().getPosition();
        Vec3 center = shipCenter(t);
        float yaw = shipYaw(t);
        float light = interior(t) ? brightness(t) : 1.0F;
        float red = interior(t) ? emergencyRed(t) : 0.0F;

        PoseStack pose = new PoseStack();
        pose.translate(center.x - cam.x, center.y - cam.y, center.z - cam.z);
        pose.mulPose(shipRotation(t));
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        if (t >= CRASH_CAM_AT) {
            // Seen from outside: the ship comes in whole, then on impact it blows apart and what is
            // left on the island is the crash site's own wreckage.
            if (t < IMPACT_AT) {
                ship.render(pose, buffers, 1.0F, 0.0F);
                navLights(pose, buffers, t);
            } else {
                renderDebris(t, cam, buffers);
            }
            buffers.endBatch();
            return;
        }
        ship.render(pose, buffers, light, red);
        Line line = currentLine(t);
        float brace = Mth.clamp((t - PODS_AT) / 1.5F, 0.0F, 1.0F);
        float grip = t >= TROUBLE_AT && t < SNAP_AT ? 1.0F : t >= SNAP_AT ? 1.0F : 0.0F;
        crew.render(pose, buffers, aboard, line == null ? "" : line.speaker, brace, shake(t), t, light,
                jolt(t) - decompression(t) * 0.8F, grip, reaction(t));
        navLights(pose, buffers, t);

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
        float until = next < lines.size() ? lines.get(next).t : found.t + LINE_HOLD;
        return t < Math.min(until, found.t + LINE_HOLD) && t < IMPACT_AT ? found : null;
    }

    private static int typed(Line line) {
        return Component.translatable(line.key).getString().length();
    }

    /** Splits a line into rows that fit {@code width}, breaking at spaces (or anywhere, for Mandarin). */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = row.isEmpty() ? word : row + " " + word;
            if (font.width(candidate) <= width) {
                row.setLength(0);
                row.append(candidate);
                continue;
            }
            if (!row.isEmpty()) {
                rows.add(row.toString());
                row.setLength(0);
            }
            // A single word (or an unspaced Mandarin line) wider than a row is cut by width.
            String rest = word;
            while (font.width(rest) > width) {
                String head = font.plainSubstrByWidth(rest, width);
                if (head.isEmpty()) {
                    break;
                }
                rows.add(head);
                rest = rest.substring(head.length());
            }
            row.append(rest);
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
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
            g.fill(0, 0, w, h, ((int) (alarm * 16) << 24) | 0xC00000);
        }
        if (interior(t) && t >= SNAP_AT && t < CRASH_CAM_AT) {
            // Re-entry fire flickering through the windows.
            float glow = 0.10F + 0.06F * Mth.sin(t * 17.0F) + 0.04F * Mth.sin(t * 41.0F);
            g.fill(0, 0, w, h, ((int) (Math.max(0.0F, glow) * 255) << 24) | 0xFF7A20);
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
            int shown = Math.min(text.length(), (int) ((t - line.t) * TYPE_SPEED));
            boolean ship = line.speaker.equals("ship");
            int color = ship ? 0xFF5CE1E6 : Crew.SPECIES.get(line.speaker).color();
            Component name = Component.translatable("cutscene.skylore_islands.speaker." + line.speaker);
            // Big text, wrapped to the screen width, on a dark band sitting on the bottom letterbox bar.
            float scale = SUBTITLE_SCALE;
            List<String> rows = wrap(font, text, (int) ((w - 24) / scale));
            int rowHeight = 11;
            int blockHeight = (int) ((rowHeight * (rows.size() + 1) + 4) * scale);
            int top = h - bar / 2 - blockHeight + (int) (4 * scale);
            g.fill(0, top - (int) (3 * scale), w, h - bar + 1, 0x99000000);
            g.pose().pushPose();
            g.pose().translate(w / 2.0F, top, 0.0F);
            g.pose().scale(scale, scale, 1.0F);
            g.drawCenteredString(font, name, 0, 0, color);
            int left = shown;
            for (int i = 0; i < rows.size(); i++) {
                String row = rows.get(i);
                // The typewriter runs on across the wrapped rows.
                String visible = row.substring(0, Math.max(0, Math.min(row.length(), left)));
                left -= row.length() + 1;
                // Centre on the full row's width so text doesn't slide sideways as it types.
                int x = -font.width(row) / 2;
                g.drawString(font, visible, x, rowHeight * (i + 1) + 2, ship ? 0xFFB8F4F6 : 0xFFFFFFFF);
            }
            g.pose().popPose();
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

        // Fade in from black, a white flash on impact, then the wreck fades to black before the island wake-up.
        if (t < 1.5F) {
            g.fill(0, 0, w, h, ((int) (Mth.clamp(1.0F - t / 1.5F, 0.0F, 1.0F) * 255) << 24));
        }
        if (t >= IMPACT_AT && t < IMPACT_AT + 0.12F) {
            g.fill(0, 0, w, h, 0xFFFFFFFF);
        }
        if (t >= CRASH_FADE_AT) {
            g.fill(0, 0, w, h, (int) (Mth.clamp(t - CRASH_FADE_AT, 0.0F, 1.0F) * 255) << 24);
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

    /**
     * While the cutscene plays, only its own sounds (effects on the master channel, the score on music) get through:
     * no background music, ambience or world noise from wherever the player is parked.
     */
    public static void onPlaySound(PlaySoundEvent event) {
        if (active() && event.getSound() != null && event.getSound().getSource() != SoundSource.MASTER
                && !event.getSound().getLocation().getNamespace().equals(SkyloreIslands.MOD_ID)) {
            event.setSound(null);
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
