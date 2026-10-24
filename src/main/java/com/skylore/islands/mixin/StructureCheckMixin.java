package com.skylore.islands.mixin;

import com.skylore.islands.worldgen.StructureRelocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Structure searches (compasses) check starts per level; bind that level's island layout for relocation. */
@Mixin(StructureCheck.class)
public abstract class StructureCheckMixin {

    @Shadow @Final private ChunkGenerator chunkGenerator;

    @Inject(method = "checkStart", at = @At("HEAD"))
    private void skylore$bindRelocation(ChunkPos pos, Structure structure, StructurePlacement placement, boolean skipKnown,
            CallbackInfoReturnable<StructureCheckResult> cir) {
        StructureRelocation.bind(this.chunkGenerator);
    }
}
