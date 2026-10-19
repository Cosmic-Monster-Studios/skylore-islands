package com.skylore.islands.worldgen.density;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skylore.islands.config.SkyloreConfig;
import com.skylore.islands.worldgen.IslandLayoutBinder;
import com.skylore.islands.worldgen.IslandVolume;
import com.skylore.islands.worldgen.IslandWater;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;

public class CellularIslandDensityFunction implements DensityFunction.SimpleFunction {

    public static final long OVERWORLD_SALT = 42069L;
    /** Separate Voronoi map so Ignis islands are not a copy of the Overworld. */
    public static final long NETHER_SALT = 8675309L;

    public static final MapCodec<CellularIslandDensityFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("cell_size", SkyloreConfig.DEFAULT_CELL_SIZE).forGetter(d -> d.cellSize),
                    Codec.INT.optionalFieldOf("min_altitude", SkyloreConfig.DEFAULT_MIN_ALTITUDE).forGetter(d -> d.minAltitude),
                    Codec.INT.optionalFieldOf("max_altitude", SkyloreConfig.DEFAULT_MAX_ALTITUDE).forGetter(d -> d.maxAltitude),
                    Codec.INT.optionalFieldOf("min_radius", SkyloreConfig.DEFAULT_MIN_RADIUS).forGetter(d -> d.minRadius),
                    Codec.INT.optionalFieldOf("max_radius", SkyloreConfig.DEFAULT_MAX_RADIUS).forGetter(d -> d.maxRadius),
                    Codec.LONG.optionalFieldOf("layout_salt", OVERWORLD_SALT).forGetter(d -> d.layoutSalt)
            ).apply(instance, CellularIslandDensityFunction::new)
    );

    public static final KeyDispatchDataCodec<CellularIslandDensityFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    public static final CellularIslandDensityFunction INSTANCE = new CellularIslandDensityFunction();

    private static volatile long WORLD_SEED = 0L;
    private static final ThreadLocal<long[]> LAYOUT_SALT = ThreadLocal.withInitial(() -> new long[]{OVERWORLD_SALT});

    public static void setWorldSeed(long seed) {
        WORLD_SEED = seed;
    }

    public static long worldSeed() {
        return WORLD_SEED;
    }

    public static void setLayoutSalt(long salt) {
        LAYOUT_SALT.get()[0] = salt;
    }

    public static long layoutSalt() {
        return LAYOUT_SALT.get()[0];
    }

    public static boolean usingNetherLayout() {
        return layoutSalt() == NETHER_SALT;
    }

    public static void bindFromLevel(Level level) {
        IslandLayoutBinder.bindFromLevel(level);
    }

    public static void bindFromHeightAccessor(LevelHeightAccessor height) {
        IslandLayoutBinder.bindFromHeightAccessor(height);
    }

    public static void bindFromGenerator(ChunkGenerator generator) {
        IslandLayoutBinder.bind(generator);
    }

    public static BlockState basinFluid() {
        return usingNetherLayout() ? Blocks.LAVA.defaultBlockState() : Blocks.WATER.defaultBlockState();
    }

    private final int cellSize;
    private final int minAltitude;
    private final int maxAltitude;
    private final int minRadius;
    private final int maxRadius;
    private final long layoutSalt;

    public CellularIslandDensityFunction() {
        this(SkyloreConfig.DEFAULT_CELL_SIZE, SkyloreConfig.DEFAULT_MIN_ALTITUDE, SkyloreConfig.DEFAULT_MAX_ALTITUDE,
                SkyloreConfig.DEFAULT_MIN_RADIUS, SkyloreConfig.DEFAULT_MAX_RADIUS, OVERWORLD_SALT);
    }

    public CellularIslandDensityFunction(int cellSize, int minAltitude, int maxAltitude, int minRadius, int maxRadius) {
        this(cellSize, minAltitude, maxAltitude, minRadius, maxRadius, OVERWORLD_SALT);
    }

    public CellularIslandDensityFunction(int cellSize, int minAltitude, int maxAltitude, int minRadius, int maxRadius, long layoutSalt) {
        this.cellSize = cellSize;
        this.minAltitude = minAltitude;
        this.maxAltitude = maxAltitude;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.layoutSalt = layoutSalt;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        setLayoutSalt(this.layoutSalt);
        return IslandVolume.compute(
                x, y, z,
                SkyloreConfig.cellSize(this.cellSize),
                SkyloreConfig.minIslandAltitude(this.minAltitude),
                SkyloreConfig.maxIslandAltitude(this.maxAltitude),
                SkyloreConfig.minIslandRadius(this.minRadius),
                SkyloreConfig.maxIslandRadius(this.maxRadius));
    }

    public static boolean isInsideWaterBasin(int x, int y, int z) {
        return IslandWater.isInsideWaterBasin(x, y, z);
    }

    public static double calculateIslandDensity(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandVolume.calculateIslandDensity(x, y, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static double getShapeMod(int archetype, double angle, double phase1, double phase2) {
        return IslandVolume.getShapeMod(archetype, angle, phase1, phase2);
    }

    public static boolean couldHaveWaterAtY(int y) {
        return IslandWater.couldHaveWaterAtY(y);
    }

    public static boolean isInsideWaterBasin(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandWater.isInsideWaterBasin(x, y, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static boolean isInsideInteriorWaterBasin(int x, int y, int z) {
        return IslandWater.isInsideInteriorWaterBasin(x, y, z);
    }

    public static boolean isInsideInteriorWaterBasin(int x, int y, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandWater.isInsideInteriorWaterBasin(x, y, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static WaterColumn waterColumnAt(int x, int z) {
        return IslandWater.waterColumnAt(x, z);
    }

    public static IslandLayout layoutContaining(int x, int z) {
        return com.skylore.islands.worldgen.IslandLayout.layoutContaining(x, z);
    }

    public static boolean isOceanIslandColumn(int x, int z) {
        return com.skylore.islands.worldgen.IslandLayout.isOceanIslandColumn(x, z);
    }

    public static boolean hasIslandColumn(int x, int z) {
        return com.skylore.islands.worldgen.IslandLayout.hasIslandColumn(x, z);
    }

    public static int islandYPadBelow() {
        return com.skylore.islands.worldgen.IslandLayout.Y_PAD_BELOW;
    }

    public static int islandYPadAbove() {
        return com.skylore.islands.worldgen.IslandLayout.Y_PAD_ABOVE;
    }

    public static int[] sampleIslandYBand(int chunkX, int chunkZ) {
        return com.skylore.islands.worldgen.IslandLayout.sampleIslandYBand(chunkX, chunkZ);
    }

    public static boolean isInsideIslandStone(int x, int y, int z) {
        return IslandVolume.isInsideIslandStone(x, y, z);
    }

    public static boolean allowsStructureBlock(int x, int y, int z) {
        return IslandVolume.allowsStructureBlock(x, y, z);
    }

    public static boolean chunkAabbMightContainIsland(int chunkX, int chunkZ) {
        return com.skylore.islands.worldgen.IslandLayout.chunkAabbMightContainIsland(chunkX, chunkZ);
    }

    public static boolean chunkMightContainIsland(int chunkX, int chunkZ) {
        return com.skylore.islands.worldgen.IslandLayout.chunkMightContainIsland(chunkX, chunkZ);
    }

    public static IslandLayout findNearestMegaIsland(int x, int z, int cellRadius) {
        return com.skylore.islands.worldgen.IslandLayout.findNearestMegaIsland(x, z, cellRadius);
    }

    public static boolean isInsideOceanBiomeFootprint(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandWater.isInsideOceanBiomeFootprint(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static boolean isOceanBiomeFootprintOnLayout(int x, int z, IslandLayout layout, int cellSize) {
        return IslandWater.isOceanBiomeFootprintOnLayout(x, z, layout, cellSize);
    }

    public static IslandLayout findNearestIsland(int x, int z, int cellRadius, int minBaseRadius) {
        return com.skylore.islands.worldgen.IslandLayout.findNearestIsland(x, z, cellRadius, minBaseRadius);
    }

    public static boolean shouldSkipCarver(int x, int y, int z) {
        return IslandWater.shouldSkipCarver(x, y, z);
    }

    public static IslandLayout findLayoutContainingUncached(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return com.skylore.islands.worldgen.IslandLayout.findLayoutContainingUncached(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static WaterColumn waterColumnAt(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandWater.waterColumnAt(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static WaterColumn computeWaterColumnUncached(int x, int z, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return IslandWater.computeWaterColumnUncached(x, z, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static int computeAltitude(int cx, int cz, long cellHash, int minAlt, int maxAlt) {
        return com.skylore.islands.worldgen.IslandLayout.computeAltitude(cx, cz, cellHash, minAlt, maxAlt);
    }

    public static boolean isChunkInsideIsland(int chunkX, int chunkZ, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return com.skylore.islands.worldgen.IslandLayout.isChunkInsideIsland(chunkX, chunkZ, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static boolean doesCellHaveIsland(int cx, int cz, long cellHash) {
        return com.skylore.islands.worldgen.IslandLayout.doesCellHaveIsland(cx, cz, cellHash);
    }

    public static boolean isMegaIsland(long cellHash) {
        return com.skylore.islands.worldgen.IslandLayout.isMegaIsland(cellHash);
    }

    public static int getBaseRadius(long cellHash, int minRad, int maxRad, int archetype, boolean isMega) {
        return com.skylore.islands.worldgen.IslandLayout.getBaseRadius(cellHash, minRad, maxRad, archetype, isMega);
    }

    public static int getArchetype(long cellHash) {
        return com.skylore.islands.worldgen.IslandLayout.getArchetype(cellHash);
    }

    public static long hashCoordinates(int x, int z, long seed) {
        return com.skylore.islands.worldgen.IslandLayout.hashCoordinates(x, z, seed);
    }

    @Override
    public double minValue() {
        return -1.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    public static IslandLayout layoutForCell(int cx, int cz, int cellSize, int minAlt, int maxAlt, int minRad, int maxRad) {
        return com.skylore.islands.worldgen.IslandLayout.layoutForCell(cx, cz, cellSize, minAlt, maxAlt, minRad, maxRad);
    }

    public static int[] cellOfWarped(int x, int z, int cellSize) {
        return com.skylore.islands.worldgen.IslandLayout.cellOfWarped(x, z, cellSize);
    }

    public static final class ColumnSnapshot {
        public final IslandLayout layout;
        public final WaterColumn waterColumn;
        public final boolean waterInterior;

        public ColumnSnapshot(IslandLayout layout, WaterColumn waterColumn, boolean waterInterior) {
            this.layout = layout;
            this.waterColumn = waterColumn;
            this.waterInterior = waterInterior;
        }
    }

    public static final class WaterColumn {
        public final int seabedY;
        public final int waterLevel;
        public final boolean oceanBowl;

        public WaterColumn(int seabedY, int waterLevel, boolean oceanBowl) {
            this.seabedY = seabedY;
            this.waterLevel = waterLevel;
            this.oceanBowl = oceanBowl;
        }
    }

    public static final class IslandLayout {
        public final int cellX;
        public final int cellZ;
        public final int centerX;
        public final int centerZ;
        public final int altitude;
        public final int baseRadius;
        public final int archetype;
        public final boolean mega;
        public final long cellHash;
        /** Tiny orbiting islets, packed per {@link com.skylore.islands.worldgen.IslandLayout#ISLET_STRIDE}. */
        public final double[] islets;
        /** Large-land satellites, packed per {@link com.skylore.islands.worldgen.IslandLayout#SATELLITE_STRIDE}. */
        public final double[] satellites;

        public IslandLayout(int cellX, int cellZ, int centerX, int centerZ, int altitude, int baseRadius, int archetype, boolean mega, long cellHash) {
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.altitude = altitude;
            this.baseRadius = baseRadius;
            this.archetype = archetype;
            this.mega = mega;
            this.cellHash = cellHash;
            this.islets = archetype < 0
                    ? com.skylore.islands.worldgen.IslandLayout.NO_ISLETS
                    : com.skylore.islands.worldgen.IslandLayout.computeIslets(centerX, centerZ, baseRadius, archetype, mega, cellHash);
            this.satellites = archetype < 0
                    ? com.skylore.islands.worldgen.IslandLayout.NO_ISLETS
                    : com.skylore.islands.worldgen.IslandLayout.computeSatellites(centerX, centerZ, baseRadius, archetype, mega, cellHash);
        }
    }

    public static final class FastNoise {
        private static final int[] PERM = new int[512];

        static {
            int[] p = {
                    151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,
                    8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,
                    35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,
                    134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,
                    55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,
                    18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,
                    250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,
                    189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,
                    172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,
                    228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,
                    107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,
                    138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
            };
            for (int i = 0; i < 256; i++) {
                PERM[i] = p[i];
                PERM[256 + i] = p[i];
            }
        }

        public static double noise2D(double x, double y, int seed) {
            seed = mixWorldSalt(seed);
            x += seed & 255;
            y += (seed >>> 8) & 255;
            seed >>>= 16;
            int X = (int) Math.floor(x) & 255;
            int Y = (int) Math.floor(y) & 255;
            x -= Math.floor(x);
            y -= Math.floor(y);
            double u = fade(x);
            double v = fade(y);
            int A = PERM[(X + seed) & 255] + Y;
            int B = PERM[(X + 1 + seed) & 255] + Y;
            return lerp(v, lerp(u, grad2D(PERM[A & 255], x, y),
                            grad2D(PERM[B & 255], x - 1, y)),
                    lerp(u, grad2D(PERM[(A + 1) & 255], x, y - 1),
                            grad2D(PERM[(B + 1) & 255], x - 1, y - 1)));
        }

        public static double noise3D(double x, double y, double z, int seed) {
            seed = mixWorldSalt(seed);
            x += seed & 255;
            y += (seed >>> 8) & 255;
            z += (seed >>> 16) & 255;
            seed >>>= 24;
            int X = (int) Math.floor(x) & 255;
            int Y = (int) Math.floor(y) & 255;
            int Z = (int) Math.floor(z) & 255;
            x -= Math.floor(x);
            y -= Math.floor(y);
            z -= Math.floor(z);
            double u = fade(x);
            double v = fade(y);
            double w = fade(z);
            int A = PERM[(X + seed) & 255] + Y;
            int AA = PERM[A & 255] + Z;
            int AB = PERM[(A + 1) & 255] + Z;
            int B = PERM[(X + 1 + seed) & 255] + Y;
            int BA = PERM[B & 255] + Z;
            int BB = PERM[(B + 1) & 255] + Z;

            return lerp(w, lerp(v, lerp(u, grad3D(PERM[AA & 255], x, y, z),
                                    grad3D(PERM[BA & 255], x - 1, y, z)),
                            lerp(u, grad3D(PERM[AB & 255], x, y - 1, z),
                                    grad3D(PERM[BB & 255], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad3D(PERM[(AA + 1) & 255], x, y, z - 1),
                                    grad3D(PERM[(BA + 1) & 255], x - 1, y, z - 1)),
                            lerp(u, grad3D(PERM[(AB + 1) & 255], x, y - 1, z - 1),
                                    grad3D(PERM[(BB + 1) & 255], x - 1, y - 1, z - 1))));
        }

        private static int mixWorldSalt(int salt) {
            long mixed = WORLD_SEED ^ ((long) salt * 0x9E3779B97F4A7C15L);
            mixed ^= mixed >>> 32;
            return (int) mixed;
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6 - 15) + 10);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private static double grad2D(int hash, double x, double y) {
            int h = hash & 7;
            double u = h < 4 ? x : y;
            double v = h < 4 ? y : x;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        private static double grad3D(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
