package com.skylore.islands.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.skylore.islands.worldgen.ChunkGenColumnCache;
import com.skylore.islands.worldgen.ChunkOccupancy;
import com.skylore.islands.worldgen.IslandLayout;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.LongKeyedCache;
import com.skylore.islands.worldgen.density.CellularIslandDensityFunction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Skip carvers on ocean/lava bowls and empty void chunks. Bind Overworld vs Ignis layout
 * from this generator's default block so structure checks use the right island map.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin implements IslandLayoutBinder {

    @Unique
    @SuppressWarnings("unchecked")
    private static final ThreadLocal<LongKeyedCache<Integer>[]> BASE_HEIGHT_CACHE =
            ThreadLocal.withInitial(() -> new LongKeyedCache[]{new LongKeyedCache<Integer>(4096), new LongKeyedCache<Integer>(4096)});

    @Override
    public void skylore$bindIslandLayout() {
        boolean nether = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value().defaultBlock().is(Blocks.NETHERRACK);
        CellularIslandDensityFunction.setLayoutSalt(
                nether ? CellularIslandDensityFunction.NETHER_SALT : CellularIslandDensityFunction.OVERWORLD_SALT);
    }

    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void skylore$skipOceanIslandCarvers(
            WorldGenRegion region,
            long seed,
            RandomState random,
            BiomeManager biomes,
            StructureManager structures,
            ChunkAccess chunk,
            GenerationStep.Carving step,
            CallbackInfo ci
    ) {
        skylore$bindIslandLayout();
        ChunkPos pos = chunk.getPos();
        if (!CellularIslandDensityFunction.chunkMightContainIsland(pos.x, pos.z)
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMiddleBlockX(), pos.getMiddleBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMinBlockX(), pos.getMinBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMaxBlockX(), pos.getMaxBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMinBlockX(), pos.getMaxBlockZ())
                || CellularIslandDensityFunction.isOceanIslandColumn(pos.getMaxBlockX(), pos.getMinBlockZ())) {
            ci.cancel();
        }
    }

    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor height,
            RandomState random,
            CallbackInfoReturnable<Integer> cir
    ) {
        skylore$bindIslandLayout();
        if (!CellularIslandDensityFunction.hasIslandColumn(x, z)) {
            cir.setReturnValue(height.getMinBuildHeight());
            return;
        }
        Integer cached = skylore$baseHeightCache(height, random).get(skylore$baseHeightKey(x, z, type));
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    /** Vanilla builds a fresh one-column noise chunk per call; structure checks ask the same columns repeatedly. */
    @Inject(method = "getBaseHeight", at = @At("RETURN"))
    private void skylore$rememberHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor height,
            RandomState random,
            CallbackInfoReturnable<Integer> cir
    ) {
        skylore$baseHeightCache(height, random).put(skylore$baseHeightKey(x, z, type), cir.getReturnValue());
    }

    @Unique
    private static LongKeyedCache<Integer> skylore$baseHeightCache(LevelHeightAccessor height, RandomState random) {
        // One cache per dimension so a worker alternating Overworld and Nether chunks keeps both.
        LongKeyedCache<Integer> cache = BASE_HEIGHT_CACHE.get()[CellularIslandDensityFunction.usingNetherLayout() ? 1 : 0];
        long stamp = IslandLayout.columnStamp();
        stamp = stamp * 31 + System.identityHashCode(random);
        stamp = stamp * 31 + height.getMinBuildHeight();
        stamp = stamp * 31 + height.getHeight();
        cache.validate(stamp);
        return cache;
    }

    /** Exact for |x|, |z| below 2^25, which covers the 30M world border. */
    @Unique
    private static long skylore$baseHeightKey(int x, int z, Heightmap.Types type) {
        return ((long) (x & 0x3FFFFFF) << 29) | ((long) (z & 0x3FFFFFF) << 3) | type.ordinal();
    }

    /** Void chunks skipped noise fill, so there is no terrain for surface rules to touch. */
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidSurface(
            WorldGenRegion region,
            StructureManager structures,
            RandomState random,
            ChunkAccess chunk,
            CallbackInfo ci
    ) {
        if (ChunkOccupancy.get(chunk).skipBiomeFeatures()) {
            ci.cancel();
        }
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void skylore$skipVoidNoiseFill(
            Blender blender,
            RandomState random,
            StructureManager structures,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        skylore$bindIslandLayout();
        ChunkPos pos = chunk.getPos();
        boolean inside = CellularIslandDensityFunction.chunkMightContainIsland(pos.x, pos.z);
        if (!inside) {
            ChunkOccupancy.stampVoid(chunk);
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
            return;
        }
        ChunkOccupancy.stampIsland(chunk);
    }

    /**
     * doFill runs on a background worker; set up that thread's salt and column cache there.
     * Cells above everything solid in the chunk are all air, so stop the fill below them.
     */
    @WrapMethod(method = "doFill")
    private ChunkAccess skylore$fillIslandBand(
            Blender blender,
            StructureManager structures,
            RandomState random,
            ChunkAccess chunk,
            int cellMinY,
            int cellCountY,
            Operation<ChunkAccess> original
    ) {
        skylore$bindIslandLayout();
        ChunkPos pos = chunk.getPos();
        ChunkGenColumnCache.ensureFor(pos.getMinBlockX(), pos.getMinBlockZ());
        NoiseSettings noise = ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value().noiseSettings();
        int topY = ChunkGenColumnCache.solidTopY(pos.getMinBlockX(), pos.getMinBlockZ(), noise.getCellWidth());
        int count = cellCountY;
        if (topY != Integer.MAX_VALUE) {
            count = Math.max(0, Math.min(cellCountY, Math.floorDiv(topY, noise.getCellHeight()) - cellMinY + 1));
        }
        return original.call(blender, structures, random, chunk, cellMinY, count);
    }
}
