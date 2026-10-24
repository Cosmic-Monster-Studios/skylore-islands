package com.skylore.islands.mixin;

import com.skylore.islands.worldgen.StructureRelocation;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Moves void candidates onto land; see {@link StructureRelocation}. */
@Mixin(RandomSpreadStructurePlacement.class)
public abstract class RandomSpreadStructurePlacementMixin extends StructurePlacement {

    @Shadow @Final private int spacing;

    @SuppressWarnings("deprecation")
    protected RandomSpreadStructurePlacementMixin(Vec3i locateOffset, FrequencyReductionMethod method, float frequency,
            int salt, Optional<ExclusionZone> exclusionZone) {
        super(locateOffset, method, frequency, salt, exclusionZone);
    }

    @Inject(method = "getPotentialStructureChunk", at = @At("RETURN"), cancellable = true)
    private void skylore$relocateOntoIsland(long seed, int chunkX, int chunkZ, CallbackInfoReturnable<ChunkPos> cir) {
        ChunkPos candidate = cir.getReturnValue();
        ChunkPos moved = StructureRelocation.relocate(candidate, this.spacing, this.salt());
        if (moved != candidate) {
            cir.setReturnValue(moved);
        }
    }
}
