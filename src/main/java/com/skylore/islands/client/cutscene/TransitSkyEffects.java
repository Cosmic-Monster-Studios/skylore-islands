package com.skylore.islands.client.cutscene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.skylore.islands.cutscene.ArrivalCutscene;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Deep space for the transit dimension: a black sky, a dense moving star field and a far planet.
 * The transit dimension uses the End's effects id so shader packs (Iris picks a pack's world by it)
 * draw an End-style void instead of an overworld sky with clouds, so this replaces the End effects:
 * anywhere but the transit dimension it behaves exactly like vanilla's.
 */
public final class TransitSkyEffects extends DimensionSpecialEffects.EndEffects {
    private static final int STARS = 2200;
    private final float[] stars = new float[STARS * 5];

    public TransitSkyEffects() {
        super();
        RandomSource random = RandomSource.create(7031L);
        for (int i = 0; i < STARS; i++) {
            Vector3f dir = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
            if (dir.lengthSquared() < 0.01F || dir.lengthSquared() > 1.0F) {
                i--;
                continue;
            }
            dir.normalize(100.0F);
            stars[i * 5] = dir.x;
            stars[i * 5 + 1] = dir.y;
            stars[i * 5 + 2] = dir.z;
            stars[i * 5 + 3] = 0.12F + random.nextFloat() * random.nextFloat() * 0.35F;
            stars[i * 5 + 4] = 0.35F + random.nextFloat() * 0.65F;
        }
    }

    private static boolean transit() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension() == ArrivalCutscene.TRANSIT;
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return transit() ? Vec3.ZERO : super.getBrightnessDependentFogColor(fogColor, brightness);
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        if (level.dimension() != ArrivalCutscene.TRANSIT) {
            return false;
        }
        FogRenderer.setupNoFog();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // Stars are too far away to move with the ship's speed; they only creep. Speed shows in the dust.
        Matrix4f starPose = new Matrix4f(modelViewMatrix).rotateX(CutscenePlayer.starTravel(partialTick) * 0.02F * Mth.DEG_TO_RAD);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Vector3f dir = new Vector3f();
        Vector3f side = new Vector3f();
        Vector3f up = new Vector3f();
        for (int i = 0; i < STARS; i++) {
            dir.set(stars[i * 5], stars[i * 5 + 1], stars[i * 5 + 2]);
            float size = stars[i * 5 + 3];
            int alpha = (int) (stars[i * 5 + 4] * 255);
            dir.cross(Math.abs(dir.y) > 90 ? 1 : 0, Math.abs(dir.y) > 90 ? 0 : 1, 0, side).normalize(size);
            dir.cross(side, up).normalize(size);
            builder.addVertex(starPose, dir.x - side.x - up.x, dir.y - side.y - up.y, dir.z - side.z - up.z).setColor(255, 255, 255, alpha);
            builder.addVertex(starPose, dir.x + side.x - up.x, dir.y + side.y - up.y, dir.z + side.z - up.z).setColor(255, 255, 255, alpha);
            builder.addVertex(starPose, dir.x + side.x + up.x, dir.y + side.y + up.y, dir.z + side.z + up.z).setColor(255, 255, 255, alpha);
            builder.addVertex(starPose, dir.x - side.x + up.x, dir.y - side.y + up.y, dir.z - side.z + up.z).setColor(255, 255, 255, alpha);
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());

        // A far blue-violet planet low off the port bow, drifting with the stars.
        Matrix4f planetPose = new Matrix4f(modelViewMatrix)
                .rotateX(CutscenePlayer.starTravel(partialTick) * 0.004F * Mth.DEG_TO_RAD)
                .rotateY(-50.0F * Mth.DEG_TO_RAD).rotateX(-14.0F * Mth.DEG_TO_RAD);
        BufferBuilder planet = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        float r = 14.0F;
        planet.addVertex(planetPose, -3.0F, 3.0F, 90.0F).setColor(150, 160, 255, 255);
        for (int i = 0; i <= 48; i++) {
            double a = Math.PI * 2 * i / 48;
            float x = (float) Math.cos(a) * r;
            float y = (float) Math.sin(a) * r;
            float lit = (float) Math.max(0.0, Math.cos(a - 2.4));
            planet.addVertex(planetPose, x, y, 90.0F).setColor((int) (30 + 60 * lit), (int) (25 + 50 * lit), (int) (70 + 110 * lit), 255);
        }
        BufferUploader.drawWithShader(planet.buildOrThrow());

        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        return true;
    }
}
