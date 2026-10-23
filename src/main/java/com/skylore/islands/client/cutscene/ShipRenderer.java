package com.skylore.islands.client.cutscene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the whole ship as one moving object. Blocks go through the entity render types, the way
 * vanilla draws falling blocks, so shader packs light it like any entity; faces between neighbouring
 * ship blocks are culled like in a chunk.
 */
public final class ShipRenderer {
    private final ShipLevel level;
    private final List<Part> parts = new ArrayList<>();
    private final RandomSource random = RandomSource.create();

    private record Part(BlockPos pos, BlockState state, BakedModel model, RenderType chunkType, RenderType entityType) {}

    public ShipRenderer(ShipModel model) {
        this.level = new ShipLevel(model);
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (Map.Entry<BlockPos, BlockState> entry : model.blocks().entrySet()) {
            BlockState state = entry.getValue();
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            BakedModel baked = dispatcher.getBlockModel(state);
            random.setSeed(42L);
            for (RenderType type : baked.getRenderTypes(state, random, ModelData.EMPTY)) {
                parts.add(new Part(entry.getKey(), state, baked, type, RenderTypeHelper.getEntityRenderType(type, false)));
            }
        }
    }

    /**
     * @param pose       already moved to the ship centre and turned to its heading
     * @param brightness 0..1 multiplier for flickering lights
     * @param red        0..1 alarm tint
     */
    public void render(PoseStack pose, MultiBufferSource buffers, float brightness, float red) {
        ModelBlockRenderer blocks = Minecraft.getInstance().getBlockRenderer().getModelRenderer();
        float green = brightness * (1.0F - red);
        for (Part part : parts) {
            BlockPos pos = part.pos;
            pose.pushPose();
            pose.translate(pos.getX(), pos.getY(), pos.getZ());
            VertexConsumer consumer = new Tinted(buffers.getBuffer(part.entityType), brightness, green, green);
            blocks.tesselateBlock(level, part.model, part.state, pos, pose, consumer, true, random, 42L,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, part.chunkType);
            pose.popPose();
        }
    }

    /** Scales vertex colours, for the flicker and the red alarm wash. */
    private record Tinted(VertexConsumer inner, float r, float g, float b) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            inner.setColor((int) (red * r), (int) (green * g), (int) (blue * b), alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            inner.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            inner.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }

    /** Just enough of a level for block meshing: the ship's blocks, lit everywhere. */
    private record ShipLevel(ShipModel model) implements BlockAndTintGetter {
        @Override
        public float getShade(Direction direction, boolean shade) {
            if (!shade) {
                return 1.0F;
            }
            return switch (direction) {
                case DOWN -> 0.5F;
                case UP -> 1.0F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return Minecraft.getInstance().level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return 0xFFFFFF;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return model.get(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return model.get(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 64;
        }

        @Override
        public int getMinBuildHeight() {
            return -32;
        }
    }
}
