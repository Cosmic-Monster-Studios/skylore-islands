package com.skylore.islands.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla: shouldSkipAirCheck == true means SKIP the air test and place anyway.
 * Returning true here made surface ores worse. Always return false so canPlaceOre
 * requires !isAdjacentToAir (same as discardChanceOnAirExposure = 1.0).
 * Runs for every ore block attempt, so it skips the original (and its random roll) with no
 * callback allocation.
 */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {

    @WrapMethod(method = "shouldSkipAirCheck")
    private static boolean skylore$neverSkipAirExposure(RandomSource random, float discardChanceOnAirExposure, Operation<Boolean> original) {
        return false;
    }
}
