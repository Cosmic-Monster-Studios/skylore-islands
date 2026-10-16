package com.skylore.islands;

import com.mojang.logging.LogUtils;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.event.ChunkOccupancyHandler;
import com.skylore.islands.event.CrashIslandHandler;
import com.skylore.islands.event.IslandSpawnHandler;
import com.skylore.islands.gameplay.PlayerTasks;
import com.skylore.islands.gameplay.VoidFallProtectionHandler;
import com.skylore.islands.init.SkyloreBlocks;
import com.skylore.islands.init.SkyloreDensityFunctions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(SkyloreIslands.MOD_ID)
public class SkyloreIslands {
    public static final String MOD_ID = "skylore_islands";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SkyloreIslands(IEventBus modEventBus, ModContainer modContainer) {
        SkyloreDensityFunctions.register(modEventBus);
        SkyloreBlocks.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        modContainer.registerConfig(ModConfig.Type.SERVER, SkyloreConfig.SERVER_SPEC, "skylore_islands-server.toml");
        NeoForge.EVENT_BUS.register(IslandSpawnHandler.class);
        NeoForge.EVENT_BUS.register(CrashIslandHandler.class);
        NeoForge.EVENT_BUS.register(ChunkOccupancyHandler.class);
        NeoForge.EVENT_BUS.register(VoidFallProtectionHandler.class);
        NeoForge.EVENT_BUS.register(PlayerTasks.class);

        LOGGER.info("Skylore Islands 1.21.1 initialized — expansive 3D cellular sky islands active.");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Skylore Islands 1.21.1 common setup complete.");
    }
}
