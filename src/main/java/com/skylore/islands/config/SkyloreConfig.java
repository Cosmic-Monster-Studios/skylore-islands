package com.skylore.islands.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class SkyloreConfig {
    public static class Server {
        public final ModConfigSpec.IntValue cellSize;
        public final ModConfigSpec.IntValue islandDensity;
        public final ModConfigSpec.IntValue minIslandAltitude;
        public final ModConfigSpec.IntValue maxIslandAltitude;
        public final ModConfigSpec.IntValue minIslandRadius;
        public final ModConfigSpec.IntValue maxIslandRadius;

        public final ModConfigSpec.BooleanValue crashIslandsEnabled;
        public final ModConfigSpec.BooleanValue crashIslandSkipOriginCell;
        public final ModConfigSpec.IntValue crashCraterRadius;
        public final ModConfigSpec.IntValue crashMinTreeLogs;
        public final ModConfigSpec.IntValue crashMinIslandRadius;

        public final ModConfigSpec.IntValue skyCacheChance;
        public final ModConfigSpec.BooleanValue skyCacheInNether;

        public final ModConfigSpec.BooleanValue relocateStructures;
        public final ModConfigSpec.IntValue mushroomIslandChance;

        public final ModConfigSpec.BooleanValue voidFallProtection;
        public final ModConfigSpec.IntValue voidFallRescueY;

        public Server(ModConfigSpec.Builder builder) {
            builder.push("general");

            cellSize = builder
                    .comment("Island spacing in blocks (cell grid). Larger = more void between islands. Default 384 (~150-700 blocks between islands).")
                    .defineInRange("cellSize", 384, 256, 16384);

            islandDensity = builder
                    .comment("How often cells get islands, 0-100. 0 = spawn island only. 50 = isolated islands. 70 = denser with some clumps (default).")
                    .defineInRange("islandDensity", 70, 0, 100);

            minIslandAltitude = builder
                    .comment("Lowest island surface Y. Default is 20 so undersides can reach vanilla deep ores.")
                    .defineInRange("minIslandAltitude", 20, -16, 200);

            maxIslandAltitude = builder
                    .comment("Highest island elevation Y level. Default is 260.")
                    .defineInRange("maxIslandAltitude", 260, 100, 320);

            minIslandRadius = builder
                    .comment("Minimum island base radius in blocks. Default is 45.")
                    .defineInRange("minIslandRadius", 45, 20, 512);

            maxIslandRadius = builder
                    .comment("Maximum standard island base radius in blocks. Default is 170.")
                    .defineInRange("maxIslandRadius", 170, 50, 1024);

            builder.pop();

            builder.push("crash_islands");
            crashIslandsEnabled = builder
                    .comment("Crash-land each new player on their own forested island on first Overworld join. Off by default; packs turn it on.")
                    .define("enabled", false);
            crashIslandSkipOriginCell = builder
                    .comment("Never assign cell (0,0); that stays the world hub.")
                    .define("skipOriginCell", true);
            crashCraterRadius = builder
                    .comment("Impact crater radius in blocks.")
                    .defineInRange("craterRadius", 12, 6, 32);
            crashMinTreeLogs = builder
                    .comment("Log blocks that must be within 24 blocks of a crash site, so players start near trees.")
                    .defineInRange("minTreeLogs", 12, 0, 1000);
            crashMinIslandRadius = builder
                    .comment("Smallest island base radius that can hold a crash site.")
                    .defineInRange("minIslandRadius", 40, 20, 512);
            builder.pop();

            builder.push("treasure");
            skyCacheChance = builder
                    .comment("One in this many islets and satellites hides a buried Sky Cache. 0 disables them. Applies to newly generated chunks.")
                    .defineInRange("skyCacheChance", 40, 0, 100000);
            skyCacheInNether = builder
                    .comment("Whether Sky Caches also generate on islets in the Nether.")
                    .define("skyCacheInNether", false);
            builder.pop();

            builder.push("worldgen");
            relocateStructures = builder
                    .comment("Move structure spawn attempts that land in open void onto the nearest island in the same placement region. Without it most structures are about 10x rarer than vanilla. Applies to newly generated chunks.")
                    .define("relocateStructures", true);
            mushroomIslandChance = builder
                    .comment("One in this many regular Overworld islands gets mushroom fields climate. 0 disables mushroom islands. Applies to newly generated chunks.")
                    .defineInRange("mushroomIslandChance", DEFAULT_MUSHROOM_ISLAND_CHANCE, 0, 100000);
            builder.pop();

            builder.push("gameplay");
            voidFallProtection = builder
                    .comment("Whether falling into the void below islands automatically rescues the player. Off by default; packs turn it on.")
                    .define("voidFallProtection", false);
            voidFallRescueY = builder
                    .comment("The Y level threshold in the Overworld where void fall rescue triggers.")
                    .defineInRange("voidFallRescueY", -40, -64, 0);
            builder.pop();
        }
    }

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    public static final int DEFAULT_CELL_SIZE = 384;
    public static final int DEFAULT_ISLAND_DENSITY = 70;
    public static final int DEFAULT_MIN_ALTITUDE = 20;
    public static final int DEFAULT_MAX_ALTITUDE = 260;
    public static final int DEFAULT_MIN_RADIUS = 45;
    public static final int DEFAULT_MAX_RADIUS = 170;
    public static final boolean DEFAULT_CRASH_ISLANDS_ENABLED = false;
    public static final boolean DEFAULT_CRASH_ISLAND_SKIP_ORIGIN = true;
    public static final int DEFAULT_CRASH_CRATER_RADIUS = 12;
    public static final int DEFAULT_CRASH_MIN_TREE_LOGS = 12;
    public static final int DEFAULT_CRASH_MIN_ISLAND_RADIUS = 40;
    public static final int DEFAULT_SKY_CACHE_CHANCE = 40;
    public static final int DEFAULT_MUSHROOM_ISLAND_CHANCE = 60;

    static {
        final Pair<Server, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER_SPEC = specPair.getRight();
        SERVER = specPair.getLeft();
    }

    private static boolean loaded() {
        return SERVER_SPEC != null && SERVER_SPEC.isLoaded();
    }

    public static int cellSize() {
        return loaded() ? SERVER.cellSize.get() : DEFAULT_CELL_SIZE;
    }

    public static int islandDensity() {
        return loaded() ? SERVER.islandDensity.get() : DEFAULT_ISLAND_DENSITY;
    }

    public static int minIslandAltitude() {
        return loaded() ? SERVER.minIslandAltitude.get() : DEFAULT_MIN_ALTITUDE;
    }

    public static int maxIslandAltitude() {
        return loaded() ? SERVER.maxIslandAltitude.get() : DEFAULT_MAX_ALTITUDE;
    }

    public static int minIslandRadius() {
        return loaded() ? SERVER.minIslandRadius.get() : DEFAULT_MIN_RADIUS;
    }

    public static int maxIslandRadius() {
        return loaded() ? SERVER.maxIslandRadius.get() : DEFAULT_MAX_RADIUS;
    }

    public static int cellSize(int fallback) {
        return loaded() ? SERVER.cellSize.get() : fallback;
    }

    public static int minIslandAltitude(int fallback) {
        return loaded() ? SERVER.minIslandAltitude.get() : fallback;
    }

    public static int maxIslandAltitude(int fallback) {
        return loaded() ? SERVER.maxIslandAltitude.get() : fallback;
    }

    public static int minIslandRadius(int fallback) {
        return loaded() ? SERVER.minIslandRadius.get() : fallback;
    }

    public static int maxIslandRadius(int fallback) {
        return loaded() ? SERVER.maxIslandRadius.get() : fallback;
    }

    public static boolean crashIslandsEnabled() {
        return loaded() ? SERVER.crashIslandsEnabled.get() : DEFAULT_CRASH_ISLANDS_ENABLED;
    }

    public static boolean crashIslandSkipOriginCell() {
        return loaded() ? SERVER.crashIslandSkipOriginCell.get() : DEFAULT_CRASH_ISLAND_SKIP_ORIGIN;
    }

    public static int crashCraterRadius() {
        return loaded() ? SERVER.crashCraterRadius.get() : DEFAULT_CRASH_CRATER_RADIUS;
    }

    public static int crashMinTreeLogs() {
        return loaded() ? SERVER.crashMinTreeLogs.get() : DEFAULT_CRASH_MIN_TREE_LOGS;
    }

    public static int crashMinIslandRadius() {
        return loaded() ? SERVER.crashMinIslandRadius.get() : DEFAULT_CRASH_MIN_ISLAND_RADIUS;
    }

    public static int skyCacheChance() {
        return loaded() ? SERVER.skyCacheChance.get() : DEFAULT_SKY_CACHE_CHANCE;
    }

    public static boolean skyCacheInNether() {
        return loaded() && SERVER.skyCacheInNether.get();
    }

    public static boolean relocateStructures() {
        return !loaded() || SERVER.relocateStructures.get();
    }

    public static int mushroomIslandChance() {
        return loaded() ? SERVER.mushroomIslandChance.get() : DEFAULT_MUSHROOM_ISLAND_CHANCE;
    }

    public static boolean voidFallProtection() {
        return loaded() && SERVER.voidFallProtection.get();
    }

    public static int voidFallRescueY() {
        return loaded() ? SERVER.voidFallRescueY.get() : -40;
    }
}
