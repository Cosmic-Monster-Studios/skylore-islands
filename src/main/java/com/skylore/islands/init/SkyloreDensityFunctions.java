package com.skylore.islands.init;

import com.mojang.serialization.MapCodec;
import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import com.skylore.islands.worldgen.density.IslandDepthDensityFunction;
import com.skylore.islands.worldgen.density.IslandOccupancyDensityFunction;
import com.skylore.islands.worldgen.density.OceanBowlContinentalnessDensityFunction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class SkyloreDensityFunctions {

    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTIONS =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, SkyloreIslands.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<CellularIslandDensityFunction>> CELLULAR_ISLAND =
            DENSITY_FUNCTIONS.register("cellular_island", () -> CellularIslandDensityFunction.MAP_CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<IslandOccupancyDensityFunction>> CELLULAR_ISLAND_OCCUPANCY =
            DENSITY_FUNCTIONS.register("cellular_island_occupancy", () -> IslandOccupancyDensityFunction.MAP_CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<OceanBowlContinentalnessDensityFunction>> OCEAN_BOWL_CONTINENTALNESS =
            DENSITY_FUNCTIONS.register("ocean_continentalness", () -> OceanBowlContinentalnessDensityFunction.MAP_CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<IslandDepthDensityFunction>> ISLAND_DEPTH =
            DENSITY_FUNCTIONS.register("island_depth", () -> IslandDepthDensityFunction.MAP_CODEC);

    public static void register(IEventBus eventBus) {
        DENSITY_FUNCTIONS.register(eventBus);
    }
}
