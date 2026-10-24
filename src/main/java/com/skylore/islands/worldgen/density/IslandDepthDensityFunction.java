package com.skylore.islands.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skylore.islands.worldgen.IslandLayout;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Climate {@code depth} measured down from each island's ground level, like vanilla measures it
 * from sea level. With a flat 0 the cave biomes (lush caves, dripstone caves, deep dark) could never
 * be picked; now thick island bellies can hold them. Only island rock gets depth: void columns
 * and the open air under an island stay at 0.
 */
public class IslandDepthDensityFunction implements DensityFunction.SimpleFunction {

    /**
     * Blocks of depth per 1.0 of climate depth. Vanilla uses 128, but islands are at most ~160 thick,
     * so the deep dark (depth 1.1) would never fit. At 80 it starts ~112 blocks under the altitude.
     */
    private static final double BLOCKS_PER_DEPTH = 80.0;
    /** Lake beds and basin floors sit up to ~30 blocks under the island altitude; keep them surface biomes. */
    private static final int SURFACE_MARGIN = 24;

    public static final MapCodec<IslandDepthDensityFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.LONG.optionalFieldOf("layout_salt", CellularIslandDensityFunction.OVERWORLD_SALT).forGetter(d -> d.layoutSalt)
            ).apply(instance, IslandDepthDensityFunction::new)
    );

    public static final KeyDispatchDataCodec<IslandDepthDensityFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private final long layoutSalt;

    public IslandDepthDensityFunction(long layoutSalt) {
        this.layoutSalt = layoutSalt;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        CellularIslandDensityFunction.setLayoutSalt(this.layoutSalt);
        CellularIslandDensityFunction.IslandLayout layout = IslandLayout.layoutContaining(context.blockX(), context.blockZ());
        if (layout == null) {
            return 0.0;
        }
        int below = layout.altitude - SURFACE_MARGIN - context.blockY();
        if (below <= 0 || !CellularIslandDensityFunction.isInsideIslandStone(context.blockX(), context.blockY(), context.blockZ())) {
            // Under the island is open void; cave biomes there would only mislead /locate.
            return 0.0;
        }
        return below / BLOCKS_PER_DEPTH;
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 3.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
