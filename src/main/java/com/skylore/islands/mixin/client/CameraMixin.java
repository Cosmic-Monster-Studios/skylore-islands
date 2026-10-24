package com.skylore.islands.mixin.client;

import com.skylore.islands.client.cutscene.CutscenePlayer;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the arrival cutscene put the camera in its seat aboard the ship or out on a chase shot.
 * The rotation is set as a whole quaternion so the view can pitch and roll with the ship exactly.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private static Vector3f FORWARDS;
    @Shadow @Final private static Vector3f UP;
    @Shadow @Final private static Vector3f LEFT;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;
    @Shadow private float xRot;
    @Shadow private float yRot;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("TAIL"))
    private void skylore$cutsceneCamera(BlockGetter level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
        if (CutscenePlayer.active() && CutscenePlayer.cameraReady()) {
            CutscenePlayer.CameraPose pose = CutscenePlayer.camera(partialTick);
            setPosition(pose.position());
            rotation.set(pose.rotation());
            FORWARDS.rotate(rotation, forwards);
            UP.rotate(rotation, up);
            LEFT.rotate(rotation, left);
            xRot = pose.pitch();
            yRot = pose.yaw();
        }
    }
}
