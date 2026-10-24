package com.skylore.islands.client.cutscene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.skylore.islands.SkyloreIslands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * The six (or seven) crew members in their chairs. Drawn straight from a player model rather than
 * through the player renderer, so mods that restyle players (limb replacements, extra animations)
 * can't bend them out of their poses.
 */
public final class Crew {
    /** Name colour, skin tint, model scale. A species' own skin overrides the placeholder vanilla one. */
    public record Species(String id, int color, int tint, float scale, String placeholderSkin) {
        public ResourceLocation skin() {
            ResourceLocation own = ResourceLocation.fromNamespaceAndPath(SkyloreIslands.MOD_ID, "textures/entity/crew/" + id + ".png");
            if (Minecraft.getInstance().getResourceManager().getResource(own).isPresent()) {
                return own;
            }
            return ResourceLocation.withDefaultNamespace("textures/entity/player/wide/" + placeholderSkin + ".png");
        }
    }

    public static final Map<String, Species> SPECIES = Map.of(
            "fortis", new Species("fortis", 0xFFFFC857, 0xFFFFE6B0, 1.05F, "steve"),
            "enoki", new Species("enoki", 0xFFD9A066, 0xFFE8C9A0, 0.75F, "sunny"),
            "teknari", new Species("teknari", 0xFF5CE1E6, 0xFFC0F0FF, 1.0F, "kai"),
            "beryl", new Species("beryl", 0xFF7CF29C, 0xFFC8FFD0, 1.0F, "zuri"),
            "automaton", new Species("automaton", 0xFFB8C4D0, 0xFFC8D0D8, 1.0F, "efe"),
            "tenebris", new Species("tenebris", 0xFFB07AFF, 0xFF9A88C0, 1.0F, "noor"),
            "laevis", new Species("laevis", 0xFFFFB2E6, 0xFFFFE0F0, 1.0F, "alex"));
    public static final List<String> ORDER = List.of("fortis", "teknari", "automaton", "laevis", "beryl", "enoki", "tenebris");

    private PlayerModel<LivingEntity> model;

    /** Crew members aboard for a player of this species, in seat order (seat 0 is the player's). */
    public static List<String> aboard(String playerSpecies) {
        return ORDER.stream().filter(s -> !s.equals(playerSpecies)).toList();
    }

    /** A crew member's chair: crew index 0.. maps to the ship's seats after the player's. */
    public static ShipModel.Seat seat(int crewIndex) {
        return ShipModel.SEATS.get(crewIndex + 1);
    }

    /** Ship-local position of a crew member's eyes, so others can look at the speaker. */
    public static Vec3 eye(List<String> aboard, String species) {
        int i = aboard.indexOf(species);
        if (i < 0) {
            return ShipModel.PLAYER_EYE;
        }
        return hips(i).add(0.0, 0.95 * SPECIES.get(species).scale(), 0.0);
    }

    /** A sitter's hips: a little forward of the chair's middle so the torso clears the backrest. */
    private static Vec3 hips(int crewIndex) {
        ShipModel.Seat chair = seat(crewIndex);
        float a = chair.yaw() * Mth.DEG_TO_RAD;
        return chair.hips().add(Mth.sin(a) * 0.15, 0.0, Mth.cos(a) * 0.15);
    }

    /**
     * @param pose       already moved to the ship centre and turned to its heading
     * @param speaker    who is talking now ("" for nobody), the others turn to them
     * @param brace      0..1, crew duck and cover their heads
     * @param shake      0..1, jolts everyone around
     * @param brightness 0..1 for the flickering lights
     * @param lurch      how hard a knock throws them forward (negative: pulled back)
     * @param grip       0..1, hands off their laps and clamped on the armrests
     */
    public void render(PoseStack pose, MultiBufferSource buffers, List<String> aboard, String speaker,
                       float brace, float shake, float time, float brightness, float lurch, float grip) {
        if (model == null) {
            model = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
            // Entity models start out as babies (big head, tiny body); these are grown-ups.
            model.young = false;
        }
        for (int i = 0; i < aboard.size(); i++) {
            Species species = SPECIES.get(aboard.get(i));
            float angle = seat(i).yaw();
            Vec3 hips = hips(i);
            float scale = species.scale();
            boolean talking = species.id().equals(speaker);

            pose.pushPose();
            float jolt = shake * Mth.sin(time * 37.0F + i * 2.1F) * 0.06F;
            // The model's hips sit 12 px (0.75) above its feet.
            pose.translate(hips.x + jolt, hips.y - 0.75 * scale + Math.abs(jolt), hips.z);
            // Same turn as the entity renderer: facing (sin a, cos a) is a Minecraft yaw of -a.
            pose.mulPose(Axis.YP.rotationDegrees(180.0F + angle));
            // Thrown forward against the knock (each a beat apart so they don't move as one).
            pose.mulPose(Axis.XP.rotationDegrees(-lurch * (9.0F + i % 3 * 2.0F)));
            pose.scale(-scale, -scale, scale);
            pose.translate(0.0F, -1.501F, 0.0F);

            sit(model, time + i * 1.7F);
            if (grip > 0.0F) {
                // Arms down at their sides, hands on the armrests.
                model.rightArm.xRot = Mth.lerp(grip, model.rightArm.xRot, -0.3F);
                model.leftArm.xRot = Mth.lerp(grip, model.leftArm.xRot, -0.3F);
                model.rightArm.zRot = Mth.lerp(grip, model.rightArm.zRot, 0.35F);
                model.leftArm.zRot = Mth.lerp(grip, model.leftArm.zRot, -0.35F);
            }
            Vec3 target = speaker.isEmpty() ? null : eye(aboard, speaker);
            if (target != null && !talking) {
                // Turn to whoever is talking, as far as a neck goes.
                Vec3 d = target.subtract(hips.add(0.0, 0.95 * scale, 0.0));
                float look = (float) Math.toDegrees(Math.atan2(d.x, d.z));
                model.head.yRot = -Mth.clamp(Mth.wrapDegrees(look - angle), -70.0F, 70.0F) * Mth.DEG_TO_RAD;
                model.head.xRot = (float) -Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z));
            }
            if (talking) {
                model.head.xRot = Mth.sin(time * 9.0F) * 0.08F;
                model.rightArm.xRot = -1.1F + Mth.sin(time * 5.0F) * 0.25F;
                model.rightArm.zRot = 0.15F;
            }
            if (brace > 0.0F) {
                // Head down, forearms over it.
                model.head.xRot = Mth.lerp(brace, model.head.xRot, 0.7F);
                model.head.yRot = Mth.lerp(brace, model.head.yRot, 0.0F);
                model.rightArm.xRot = Mth.lerp(brace, model.rightArm.xRot, -2.7F);
                model.leftArm.xRot = Mth.lerp(brace, model.leftArm.xRot, -2.7F);
                model.rightArm.zRot = Mth.lerp(brace, model.rightArm.zRot, 0.45F);
                model.leftArm.zRot = Mth.lerp(brace, model.leftArm.zRot, -0.45F);
            }
            copyLayers(model);

            int c = species.tint();
            int r = (int) (((c >> 16) & 0xFF) * brightness);
            int g = (int) (((c >> 8) & 0xFF) * brightness);
            int b = (int) ((c & 0xFF) * brightness);
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(species.skin()));
            model.renderToBuffer(pose, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFF000000 | r << 16 | g << 8 | b);
            pose.popPose();
        }
    }

    /** Seated: thighs forward, hands resting in the lap, breathing a little. */
    private static void sit(PlayerModel<LivingEntity> model, float time) {
        model.head.xRot = 0.0F;
        model.head.yRot = 0.0F;
        float breath = Mth.sin(time * 1.6F) * 0.03F;
        model.rightArm.xRot = -0.75F + breath;
        model.rightArm.yRot = -0.1F;
        model.rightArm.zRot = 0.05F;
        model.leftArm.xRot = -0.75F + breath;
        model.leftArm.yRot = 0.1F;
        model.leftArm.zRot = -0.05F;
        model.rightLeg.xRot = -1.4137167F;
        model.rightLeg.yRot = 0.15F;
        model.rightLeg.zRot = 0.05F;
        model.leftLeg.xRot = -1.4137167F;
        model.leftLeg.yRot = -0.15F;
        model.leftLeg.zRot = -0.05F;
    }

    private static void copyLayers(PlayerModel<LivingEntity> model) {
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
    }
}
