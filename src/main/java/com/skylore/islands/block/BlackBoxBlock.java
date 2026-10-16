package com.skylore.islands.block;

import com.mojang.serialization.MapCodec;
import com.skylore.islands.gameplay.CrashArrival;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3f;

/**
 * Crash-site flight recorder. While SIGNAL is on it blinks and beeps; the first open
 * plays the corrupted flight log and adds the log book, then it goes quiet.
 */
public class BlackBoxBlock extends WreckContainerBlock {
    public static final MapCodec<BlackBoxBlock> CODEC = simpleCodec(BlackBoxBlock::new);
    public static final BooleanProperty SIGNAL = BooleanProperty.create("signal");
    private static final DustParticleOptions BLINK = new DustParticleOptions(new Vector3f(0.5F, 0.96F, 0.91F), 1.2F);

    public BlackBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(SIGNAL, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIGNAL);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && state.getValue(SIGNAL)
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WreckContainerBlockEntity box) {
            level.setBlock(pos, state.setValue(SIGNAL, false), Block.UPDATE_ALL);
            CrashArrival.playFlightLog(serverPlayer, box);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(SIGNAL)) {
            return;
        }
        if (level.getGameTime() % 40 < 2) {
            level.addParticle(BLINK, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        }
        if (random.nextInt(6) == 0) {
            level.playLocalSound(pos, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.BLOCKS, 0.6F, 1.9F, false);
        }
        if (random.nextInt(10) == 0) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.getX() + random.nextDouble(), pos.getY() + 1.0,
                    pos.getZ() + random.nextDouble(), 0.0, 0.05, 0.0);
        }
    }
}
