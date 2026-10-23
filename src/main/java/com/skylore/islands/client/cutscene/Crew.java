package com.skylore.islands.client.cutscene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

/** The six (or seven) crew members in their seats, drawn straight from a player model. */
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

    /** Where a crew member's seat is: seat index 1.. maps to the ship's crew seats. */
    public static float seatAngle(int crewIndex) {
        return ShipModel.SEAT_ANGLES[crewIndex + 1];
    }

    /** Ship-local position of a crew member's eyes, so others can look at the speaker. */
    public static Vec3 eye(List<String> aboard, String species) {
        int i = aboard.indexOf(species);
        if (i < 0) {
            return ShipModel.PLAYER_EYE;
        }
        Vec3 seat = ShipModel.seat(seatAngle(i));
        return seat.add(0.0, 0.9 * SPECIES.get(species).scale(), 0.0);
    }

    /**
     * @param speaker   who is talking now ("" for nobody), the others turn to them
     * @param brace     0..1, crew curl up with their arms over their heads
     * @param shake     0..1, jolts everyone around
     */
    public void render(PoseStack pose, MultiBufferSource buffers, List<String> aboard, String speaker,
                       float brace, float shake, float time, float brightness) {
        if (model == null) {
            model = new PlayerModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        int light = LightTexture.FULL_BRIGHT;
        for (int i = 0; i < aboard.size(); i++) {
            Species species = SPECIES.get(aboard.get(i));
            float angle = seatAngle(i);
            Vec3 seat = ShipModel.seat(angle);
            float scale = species.scale();
            boolean talking = species.id().equals(speaker);

            pose.pushPose();
            float jolt = shake * (float) Math.sin(time * 37.0F + i * 2.1F) * 0.06F;
            pose.translate(seat.x + jolt, seat.y - 0.75 * scale + Math.abs(jolt), seat.z);
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F + angle));
            pose.scale(-scale, -scale, scale);
            pose.translate(0.0F, -1.501F, 0.0F);

            sit(model);
            // Turn the head toward whoever is talking.
            Vec3 target = speaker.isEmpty() ? null : eye(aboard, speaker);
            if (target != null && !talking) {
                Vec3 me = seat.add(0.0, 0.9 * scale, 0.0);
                Vec3 d = target.subtract(me);
                float worldYaw = (float) Math.toDegrees(Math.atan2(d.x, d.z));
                model.head.yRot = Mth.clamp(Mth.wrapDegrees(worldYaw - angle), -70.0F, 70.0F) * Mth.DEG_TO_RAD * -1.0F;
                model.head.xRot = (float) -Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z));
            }
            if (talking) {
                model.head.xRot = (float) Math.sin(time * 9.0F) * 0.08F;
                model.rightArm.xRot = -0.9F + (float) Math.sin(time * 5.0F) * 0.25F;
                model.rightArm.zRot = -0.2F;
            }
            if (brace > 0.0F) {
                model.head.xRot = Mth.lerp(brace, model.head.xRot, 0.6F);
                model.head.yRot = Mth.lerp(brace, model.head.yRot, 0.0F);
                model.rightArm.xRot = Mth.lerp(brace, model.rightArm.xRot, -2.6F);
                model.leftArm.xRot = Mth.lerp(brace, model.leftArm.xRot, -2.6F);
                model.rightArm.zRot = Mth.lerp(brace, model.rightArm.zRot, 0.5F);
                model.leftArm.zRot = Mth.lerp(brace, model.leftArm.zRot, -0.5F);
            }
            copyLayers(model);

            int c = species.tint();
            int r = (int) (((c >> 16) & 0xFF) * brightness);
            int g = (int) (((c >> 8) & 0xFF) * brightness);
            int b = (int) ((c & 0xFF) * brightness);
            int color = 0xFF000000 | r << 16 | g << 8 | b;
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(species.skin()));
            model.renderToBuffer(pose, consumer, light, OverlayTexture.NO_OVERLAY, color);
            pose.popPose();
        }
    }

    private static void sit(PlayerModel<LivingEntity> model) {
        model.head.xRot = 0.0F;
        model.head.yRot = 0.0F;
        model.body.xRot = 0.0F;
        model.rightArm.xRot = -0.62831855F;
        model.rightArm.yRot = 0.0F;
        model.rightArm.zRot = 0.0F;
        model.leftArm.xRot = -0.62831855F;
        model.leftArm.yRot = 0.0F;
        model.leftArm.zRot = 0.0F;
        model.rightLeg.xRot = -1.4137167F;
        model.rightLeg.yRot = 0.31415927F;
        model.rightLeg.zRot = 0.07853982F;
        model.leftLeg.xRot = -1.4137167F;
        model.leftLeg.yRot = -0.31415927F;
        model.leftLeg.zRot = -0.07853982F;
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
