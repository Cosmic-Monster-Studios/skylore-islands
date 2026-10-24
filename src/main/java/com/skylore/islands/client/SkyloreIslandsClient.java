package com.skylore.islands.client;

import com.skylore.islands.SkyloreIslands;
import com.skylore.islands.client.cutscene.CutscenePlayer;
import com.skylore.islands.client.cutscene.TransitSkyEffects;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only wiring: the transit sky and the arrival cutscene's camera, overlay and input lock. */
@Mod(value = SkyloreIslands.MOD_ID, dist = Dist.CLIENT)
public final class SkyloreIslandsClient {
    public SkyloreIslandsClient(IEventBus modEventBus) {
        modEventBus.addListener(SkyloreIslandsClient::registerSky);

        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onClientTick);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, CutscenePlayer::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onMovementInput);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onInteract);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onRenderHand);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onHighlight);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onPlaySound);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onFog);
        NeoForge.EVENT_BUS.addListener(CutscenePlayer::onFogColor);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> CutscenePlayer.onLoggedOut());
    }

    private static void registerSky(RegisterDimensionSpecialEffectsEvent event) {
        // Keyed to the End: see TransitSkyEffects.
        event.register(BuiltinDimensionTypes.END_EFFECTS, new TransitSkyEffects());
    }
}
