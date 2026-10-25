package com.skylore.islands.mixin;

import com.mojang.datafixers.util.Either;
import com.skylore.islands.worldgen.StructureIslandPolicy;
import com.skylore.islands.worldgen.StructureRelocation;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionStructure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Void / under-island filter only. Specific structure mixins own vertical placement; this must not
 * snap buried mineshafts/strongholds/jigsaws back to the surface.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    @Shadow
    public abstract HolderSet<Biome> biomes();

    @Shadow
    public abstract TerrainAdjustment terrainAdaptation();

    /** Depths below the island surface also sampled, so cave-biome structures still pass. */
    private static final int[] BIOME_PROBE_DEPTHS = {0, 40, 90};

    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void skylore$bindIslandLayout(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        CellularIslandDensityFunction.bindFromGenerator(context.chunkGenerator());
        // Vanilla lays out the whole structure (jigsaw assembly, template loads) and only then checks
        // the biome. Relocation puts nearly every candidate on land, so skip the layout up front when
        // no biome the structure accepts is anywhere in this column.
        if (StructureRelocation.isIslandGenerator(context.chunkGenerator()) && !skylore$biomeMayMatch(context)) {
            cir.setReturnValue(Optional.empty());
        }
    }

    private String skylore$structureId(Structure.GenerationContext context) {
        return context.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getResourceKey((Structure) (Object) this)
                .map(key -> key.location().toString())
                .orElse("");
    }

    private boolean skylore$biomeMayMatch(Structure.GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        int surface = context.chunkGenerator().getBaseHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        if (surface <= context.heightAccessor().getMinBuildHeight()) {
            // Open void: the void filters decide, not the biome.
            return true;
        }
        HolderSet<Biome> allowed = this.biomes();
        Climate.Sampler sampler = context.randomState().sampler();
        for (int depth : BIOME_PROBE_DEPTHS) {
            int y = Math.max(context.heightAccessor().getMinBuildHeight(), surface - depth);
            Holder<Biome> biome = context.biomeSource().getNoiseBiome(
                    QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler);
            if (allowed.contains(biome)) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true)
    private void skylore$filterVoidStructures(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        try {
            CellularIslandDensityFunction.bindFromGenerator(context.chunkGenerator());
            Optional<Structure.GenerationStub> opt = cir.getReturnValue();
            if (opt == null || opt.isEmpty()) {
                return;
            }

            Structure.GenerationStub stub = opt.get();
            BlockPos pos = stub.position();

            int actualSurfaceY = context.chunkGenerator().getBaseHeight(
                    pos.getX(), pos.getZ(),
                    Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(),
                    context.randomState()
            );

            if (!StructureIslandPolicy.allowsGenerationStub(
                    pos.getX(), pos.getY(), pos.getZ(), actualSurfaceY,
                    (Structure) (Object) this, context.registryAccess())) {
                cir.setReturnValue(Optional.empty());
                return;
            }
            if (StructureRelocation.isIslandGenerator(context.chunkGenerator())
                    && !StructureIslandPolicy.isVoidCraft((Structure) (Object) this, context.registryAccess())) {
                // Lay the pieces out now (cached in the stub) and drop starts that hang off the rim.
                StructurePiecesBuilder pieces = stub.getPiecesBuilder();
                List<StructurePiece> pieceList = ((StructurePiecesBuilderAccessor) pieces).skylore$getPieces();
                StructureIslandPolicy.SurfaceSampler ground = (x, z) -> context.chunkGenerator().getBaseHeight(
                        x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
                int floorY = context.heightAccessor().getMinBuildHeight();
                if (pieces.isEmpty()
                        || StructureIslandPolicy.voidFootprintShare(pieceList, ground, floorY) > StructureIslandPolicy.MAX_VOID_FOOTPRINT) {
                    cir.setReturnValue(Optional.empty());
                    return;
                }
                TerrainAdjustment adaptation = this.terrainAdaptation();
                if (adaptation != TerrainAdjustment.BURY && adaptation != TerrainAdjustment.ENCAPSULATE
                        && !StructureIslandPolicy.isVoidTolerantId(skylore$structureId(context))) {
                    int sink = StructureIslandPolicy.settleDepth(pieceList, ground, floorY);
                    if (sink > 0) {
                        pieces.offsetPiecesVertically(-sink);
                    }
                }
                cir.setReturnValue(Optional.of(new Structure.GenerationStub(pos, Either.right(pieces))));
            }
            if ((Object) this instanceof WoodlandMansionStructure) {
                ChunkPos chunk = context.chunkPos();
                if (!StructureIslandPolicy.isMegaIslandAt(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ())) {
                    cir.setReturnValue(Optional.empty());
                }
            }
        } finally {
            StructureIslandPolicy.clearVoidCraftStub();
        }
    }

    @Inject(method = "terrainAdaptation", at = @At("RETURN"), cancellable = true)
    private void skylore$disableBeardLegs(CallbackInfoReturnable<TerrainAdjustment> cir) {
        TerrainAdjustment adjustment = cir.getReturnValue();
        if (adjustment == TerrainAdjustment.BEARD_THIN || adjustment == TerrainAdjustment.BEARD_BOX) {
            cir.setReturnValue(TerrainAdjustment.NONE);
        }
    }
}
