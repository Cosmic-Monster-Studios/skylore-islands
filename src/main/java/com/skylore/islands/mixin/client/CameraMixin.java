package com.skylore.islands.mixin.client;

import com.skylore.islands.client.cutscene.CutscenePlayer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the arrival cutscene put the camera in its seat aboard the ship or out on a chase shot. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void skylore$cutsceneCamera(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (CutscenePlayer.active() && CutscenePlayer.cameraReady()) {
            double[] cam = CutscenePlayer.camera(partialTick);
            setRotation((float) cam[3], (float) cam[4], (float) cam[5]);
            setPosition(cam[0], cam[1], cam[2]);
        }
    }
}
