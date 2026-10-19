package com.skylore.islands.mixin;

import com.skylore.islands.worldgen.ChunkGenColumnCache;
import com.skylore.islands.worldgen.ChunkOccupancy;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Skip carvers on ocean/lava bowls and empty void chunks. Bind Overworld vs Ignis layout
 * from this generator's default block so structure checks use the right island map.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin implements IslandLayoutBinder {

    @Override
    public void skylore$bindIslandLayout() {
        boolean nether = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value().defaultBlock().is(Blocks.NETHERRACK);
        CellularIslandDensityFunction.setLayoutSalt(
                nether ? CellularIslandDensityFunction.NETHER_SALT : CellularIslandDensityFunction.OVERWORLD_SALT);
    }

    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void skylore$skipOceanIslandCarvers(
            WorldGenRegion region,
            long seed,
            RandomState random,
            BiomeManager biomes,
            StructureManager structures,
            ChunkAccess chunk,
            GenerationStep.Carving step,
            CallbackInfo ci
    ) {
        skylore$bindIslandLayout();
        ChunkPos pos = chunk.getPos();
        if (!CellularIslandDensityFunction.chunkMightContainIsland(pos.x, pos.z)
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMiddleBlockX(), pos.getMiddleBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMinBlockX(), pos.getMinBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMaxBlockX(), pos.getMaxBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMinBlockX(), pos.getMaxBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMaxBlockX(), pos.getMinBlockZ())) {
            ci.cancel();
        }
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor height,
            RandomState random,
            CallbackInfoReturnable<Integer> cir
    ) {
        skylore$bindIslandLayout();
        if (!CellularIslandDensityFunction.hasIslandColumn(x, z)) {
            cir.setReturnValue(height.getMinBuildHeight());
        }
    }

    /** Void chunks skipped noise fill, so there is no terrain for surface rules to touch. */
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidSurface(
            WorldGenRegion region,
            StructureManager structures,
            RandomState random,
            ChunkAccess chunk,
            CallbackInfo ci
    ) {
        if (ChunkOccupancy.get(chunk).skipBiomeFeatures()) {
            ci.cancel();
        }
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidNoiseFill(
            Blender blender,
            RandomState random,
            StructureManager structures,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        skylore$bindIslandLayout();
        ChunkPos pos = chunk.getPos();
        boolean inside = CellularIslandDensityFunction.chunkMightContainIsland(pos.x, pos.z);
        if (!inside) {
            ChunkOccupancy.stampVoid(chunk);
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
            return;
        }
        ChunkOccupancy.stampIsland(chunk);
        ChunkGenColumnCache.ensureFor(pos.getMinBlockX(), pos.getMinBlockZ());
    }
}
