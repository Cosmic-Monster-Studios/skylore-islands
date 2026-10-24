package com.skylore.islands.mixin;

import com.skylore.islands.worldgen.LandSurfacePlacement;
import com.skylore.islands.worldgen.StructureIslandPolicy;
import com.skylore.islands.worldgen.StructureStubMover;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Universal Modded Jigsaw Structure Adapter for 1.21.1:
 * 1. Anchors all ground/surface structures directly onto solid island terrain.
 * 2. Elevates underground dungeons into the solid stone core.
 * 3. Rejects ground structures in empty void. Only {@code #skylore_islands:void_craft} may spawn in open air.
 * 4. Huge footprints only on mega islands.
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin extends Structure {

    @Shadow @Final private Holder<StructureTemplatePool> startPool;
    @Shadow @Final private Optional<ResourceLocation> startJigsawName;
    @Shadow @Final private int maxDepth;
    @Shadow @Final private HeightProvider startHeight;
    @Shadow @Final private boolean useExpansionHack;
    @Shadow @Final private Optional<Heightmap.Types> projectStartToHeightmap;
    @Shadow @Final private int maxDistanceFromCenter;
    @Shadow @Final private List<PoolAliasBinding> poolAliases;
    @Shadow @Final private DimensionPadding dimensionPadding;
    @Shadow @Final private LiquidSettings liquidSettings;

    /** Rock left between an encapsulated structure's roof and the island surface. */
    private static final int ENCAPSULATED_COVER = 10;

    protected JigsawStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void skylore$adaptJigsawStructuresToSkyIslands(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        CellularIslandDensityFunction.bindFromGenerator(context.chunkGenerator());
        ChunkPos chunkpos = context.chunkPos();
        int blockX = chunkpos.getMiddleBlockX();
        int blockZ = chunkpos.getMiddleBlockZ();

        String poolName = this.startPool.unwrapKey().map(k -> k.location().toString().toLowerCase()).orElse("");
        boolean isVoidCraft = StructureIslandPolicy.isVoidCraft(
                (JigsawStructure) (Object) this, context.registryAccess());
        boolean isAdAstraOil = LandSurfacePlacement.isAdAstraOilPool(poolName);

        if (isVoidCraft && CellularIslandDensityFunction.hasIslandColumn(blockX, blockZ)) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        if (!isVoidCraft && !CellularIslandDensityFunction.hasIslandColumn(blockX, blockZ)) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        if (!isVoidCraft && StructureIslandPolicy.rejectHugeHere(
                poolName, this.maxDistanceFromCenter, blockX, blockZ,
                (JigsawStructure) (Object) this, context.registryAccess())) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        if (isAdAstraOil) {
            BlockPos dry = LandSurfacePlacement.findDryLandStart(context, blockX, blockZ);
            if (dry == null) {
                cir.setReturnValue(Optional.empty());
                return;
            }
            int oilOffset = this.startHeight.sample(
                    context.random(),
                    new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor()));
            BlockPos oilStart = new BlockPos(dry.getX(), dry.getY() + oilOffset, dry.getZ());
            cir.setReturnValue(addPieces(context, oilStart));
            return;
        }

        int surfaceY = highestSurfaceY(context, blockX, blockZ);

        if (surfaceY <= 35 && !isVoidCraft) {
            cir.setReturnValue(Optional.empty());
            return;
        }

        if (!isVoidCraft && this.terrainAdaptation() == TerrainAdjustment.ENCAPSULATE) {
            // Encapsulated complexes (trial chambers) belong inside rock: hang the whole box under the
            // lowest nearby surface so no copper or vaults poke out of the island top.
            Optional<Structure.GenerationStub> stub = addPieces(context, new BlockPos(blockX, surfaceY, blockZ));
            cir.setReturnValue(stub.flatMap(s -> StructureStubMover.topAtY(
                    s, lowestSurfaceY(context, blockX, blockZ, surfaceY) - ENCAPSULATED_COVER)));
            return;
        }

        int targetY;
        if (isVoidCraft) {
            targetY = StructureIslandPolicy.AIRBORNE_START_Y;
        } else if (this.terrainAdaptation() == TerrainAdjustment.BURY || poolName.contains("buried") || poolName.contains("underground") || poolName.contains("mining_complex") || poolName.contains("asylum") || poolName.contains("foundry")) {
            targetY = Math.max(40, surfaceY - 14);
        } else {
            targetY = surfaceY;
            CellularIslandDensityFunction.WaterColumn column = CellularIslandDensityFunction.waterColumnAt(blockX, blockZ);
            if (column != null && (poolName.contains("ocean") || poolName.contains("shipwreck") || poolName.contains("ruin") || CellularIslandDensityFunction.isInsideWaterBasin(blockX, column.waterLevel, blockZ))) {
                targetY = column.oceanBowl ? column.waterLevel : targetY;
            }
        }

        if (isVoidCraft) {
            StructureIslandPolicy.markVoidCraftStub(true);
        }
        cir.setReturnValue(addPieces(context, new BlockPos(blockX, targetY, blockZ)));
    }

    private Optional<Structure.GenerationStub> addPieces(Structure.GenerationContext context, BlockPos startPos) {
        return JigsawPlacement.addPieces(
                context,
                this.startPool,
                this.startJigsawName,
                this.maxDepth,
                startPos,
                this.useExpansionHack,
                Optional.empty(),
                this.maxDistanceFromCenter,
                PoolAliasLookup.create(this.poolAliases, startPos, context.seed()),
                this.dimensionPadding,
                this.liquidSettings
        );
    }

    private static int highestSurfaceY(Structure.GenerationContext context, int blockX, int blockZ) {
        int best = sampleSurface(context, blockX, blockZ);
        best = Math.max(best, sampleSurface(context, blockX + 8, blockZ));
        best = Math.max(best, sampleSurface(context, blockX - 8, blockZ));
        best = Math.max(best, sampleSurface(context, blockX, blockZ + 8));
        best = Math.max(best, sampleSurface(context, blockX, blockZ - 8));
        return best;
    }

    /** Lowest island surface around the start; void columns (heightmap at the world floor) are skipped. */
    private static int lowestSurfaceY(Structure.GenerationContext context, int blockX, int blockZ, int fallback) {
        int low = fallback;
        for (int d = 16; d <= 32; d += 16) {
            low = lowerLand(low, sampleSurface(context, blockX + d, blockZ));
            low = lowerLand(low, sampleSurface(context, blockX - d, blockZ));
            low = lowerLand(low, sampleSurface(context, blockX, blockZ + d));
            low = lowerLand(low, sampleSurface(context, blockX, blockZ - d));
        }
        return low;
    }

    private static int lowerLand(int low, int sample) {
        return sample > 35 ? Math.min(low, sample) : low;
    }

    private static int sampleSurface(Structure.GenerationContext context, int x, int z) {
        return context.chunkGenerator().getBaseHeight(
                x, z,
                Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(),
                context.randomState()
        );
    }
}
