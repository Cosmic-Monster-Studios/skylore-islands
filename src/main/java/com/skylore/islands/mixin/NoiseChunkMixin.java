package com.skylore.islands.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.skylore.islands.worldgen.ChunkGenColumnCache;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Fills sky ocean basins and crater lakes with water source blocks with per-block precision.
 * Runs for every block of every filled chunk, so it is one cache lookup and no allocation.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {

    @Shadow public abstract int blockX();
    @Shadow public abstract int blockY();
    @Shadow public abstract int blockZ();

    @ModifyReturnValue(method = "getInterpolatedState", at = @At("RETURN"))
    private BlockState skylore$fillWaterBasins(BlockState original) {
        if (original != null && !original.isAir()) {
            return original;
        }
        switch (ChunkGenColumnCache.basinFill(this.blockX(), this.blockY(), this.blockZ())) {
            case ChunkGenColumnCache.FILL_FLOOR:
                return CellularIslandDensityFunction.usingNetherLayout()
                        ? Blocks.NETHERRACK.defaultBlockState()
                        : Blocks.STONE.defaultBlockState();
            case ChunkGenColumnCache.FILL_FLUID:
                return CellularIslandDensityFunction.basinFluid();
            default:
                return original;
        }
    }
}
