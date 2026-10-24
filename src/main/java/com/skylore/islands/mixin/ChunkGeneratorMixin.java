package com.skylore.islands.mixin;

import com.skylore.islands.worldgen.ChunkOccupancy;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.SkyCaches;
import com.skylore.islands.worldgen.StructureRelocation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep structure placement; skip biome features on VOID / VOID_STRUCTURE via PlacedFeatureMixin.
 * Empty void chunks with no structure starts or references skip decoration entirely.
 * Island chunks then get their Sky Caches.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "createStructures", at = @At("HEAD"))
    private void skylore$bindStructureRelocation(
            RegistryAccess access,
            ChunkGeneratorStructureState state,
            StructureManager structures,
            ChunkAccess chunk,
            StructureTemplateManager templates,
            CallbackInfo ci
    ) {
        StructureRelocation.bind((ChunkGenerator) (Object) this);
    }

    @Inject(method = "findNearestMapStructure", at = @At("HEAD"))
    private void skylore$bindLocateRelocation(
            ServerLevel level,
            HolderSet<Structure> targets,
            BlockPos origin,
            int radius,
            boolean skipKnown,
            CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir
    ) {
        StructureRelocation.bind((ChunkGenerator) (Object) this);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"), cancellable = true)
    private void skylore$beginVoidDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structures,
            CallbackInfo ci
    ) {
        if (ChunkOccupancy.get(chunk).kind == ChunkOccupancy.Kind.VOID && chunk.getAllReferences().isEmpty()) {
            ci.cancel();
            return;
        }
        ChunkOccupancy.beginDecoration(chunk);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void skylore$endVoidDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structures,
            CallbackInfo ci
    ) {
        ChunkOccupancy.endDecoration();
        if (ChunkOccupancy.get(chunk).kind == ChunkOccupancy.Kind.ISLAND) {
            IslandLayoutBinder.bind((ChunkGenerator) (Object) this);
            SkyCaches.decorate(level, chunk);
        }
    }
}
