package com.skylore.islands.block;

import com.skylore.islands.init.SkyloreBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hot wreckage: smokes, sparks and creaks client-side, and cools into plain hull over a few in-game days.
 */
public class SmolderingHullBlock extends Block {
    /** ~1 in 50 random ticks cools it; at the default tick speed that is roughly 1 hour real time (3 in-game days). */
    private static final int COOL_CHANCE = 50;

    public SmolderingHullBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(COOL_CHANCE) == 0) {
            level.setBlock(pos, SkyloreBlocks.SHIP_HULL.get().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos.above()).isAir()) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5;
        if (random.nextInt(3) == 0) {
            level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                    x + random.nextDouble() * 0.4 - 0.2, y, z + random.nextDouble() * 0.4 - 0.2,
                    0.0, 0.05 + random.nextDouble() * 0.03, 0.0);
        }
        if (random.nextInt(8) == 0) {
            level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0);
        }
        if (random.nextInt(14) == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + random.nextDouble() - 0.5, y - 0.2,
                    z + random.nextDouble() - 0.5, random.nextGaussian() * 0.1, 0.1, random.nextGaussian() * 0.1);
            level.playLocalSound(pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.15F, 1.6F + random.nextFloat() * 0.4F, false);
        }
        if (random.nextInt(60) == 0) {
            level.playLocalSound(pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.5F, 0.5F + random.nextFloat() * 0.2F, false);
        }
    }
}
