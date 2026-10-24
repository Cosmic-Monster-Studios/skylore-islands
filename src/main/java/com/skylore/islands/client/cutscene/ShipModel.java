package com.skylore.islands.client.cutscene;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sphere ship, in ship-local block coordinates around block (0,0,0) with the nose along +Z.
 * Built from Ad Astra and Refurbished Furniture parts when those mods are installed, and
 * from vanilla stand-ins when they are not. Seats are armchairs, one block each, so crew sit square in them.
 */
public final class ShipModel {
    public static final int RADIUS = 8;
    private static final int FLOOR_Y = -4;
    private static final int SEAT_Y = FLOOR_Y + 1;

    /** A chair: its block, and which way its occupant faces (degrees, 0 = the nose, 90 = +X). */
    public record Seat(BlockPos pos, float yaw) {
        /** Where a sitter's hips rest: the middle of the seat cushion. */
        public Vec3 hips() {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
    }

    /** Seat 0 is the player's, the rest are the crew's in order. */
    public static final List<Seat> SEATS = List.of(
            new Seat(new BlockPos(0, SEAT_Y, 3), 0),
            new Seat(new BlockPos(-2, SEAT_Y, 3), 0),
            new Seat(new BlockPos(2, SEAT_Y, 3), 0),
            new Seat(new BlockPos(-4, SEAT_Y, 0), -90),
            new Seat(new BlockPos(4, SEAT_Y, 0), 90),
            new Seat(new BlockPos(-2, SEAT_Y, -3), 180),
            new Seat(new BlockPos(2, SEAT_Y, -3), 180),
            new Seat(new BlockPos(0, SEAT_Y, -3), 180));
    /** Where the player's eyes are when seated. */
    public static final Vec3 PLAYER_EYE = SEATS.get(0).hips().add(0.0, 0.95, 0.0);

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    public ShipModel() {
        BlockState hull = block("ad_astra:iron_plateblock", Blocks.WHITE_CONCRETE);
        BlockState hullPanel = block("ad_astra:iron_panel", Blocks.LIGHT_GRAY_CONCRETE);
        BlockState belly = block("ad_astra:steel_plateblock", Blocks.GRAY_CONCRETE);
        BlockState trim = block("ad_astra:desh_plating", Blocks.ORANGE_TERRACOTTA);
        BlockState window = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        BlockState ceilingLight = with(block("ad_astra:glowing_iron_pillar", Blocks.SEA_LANTERN), "axis", "y");
        BlockState engineCase = block("ad_astra:encased_desh_block", Blocks.POLISHED_BLACKSTONE);
        BlockState engineGlow = with(block("ad_astra:glowing_desh_pillar", Blocks.SHROOMLIGHT), "axis", "z");
        BlockState floor = block("ad_astra:steel_plating", Blocks.POLISHED_ANDESITE);
        BlockState floorRing = block("ad_astra:steel_panel", Blocks.SMOOTH_STONE);
        BlockState vent = block("ad_astra:vent", Blocks.IRON_TRAPDOOR);
        BlockState core = with(block("ad_astra:glowing_desh_pillar", Blocks.SEA_LANTERN), "axis", "y");
        BlockState coreCap = block("ad_astra:encased_steel_block", Blocks.IRON_BLOCK);
        BlockState console = with(block("ad_astra:steel_plating_slab", Blocks.POLISHED_DEEPSLATE_SLAB), "type", "bottom");
        BlockState antenna = with(block("ad_astra:steel_pillar", Blocks.IRON_BARS), "axis", "y");
        BlockState beacon = with(block("ad_astra:glowing_ostrum_pillar", Blocks.REDSTONE_LAMP), "axis", "y");

        for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
            for (int y = -RADIUS - 1; y <= RADIUS + 1; y++) {
                for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d > RADIUS - 0.5 && d <= RADIUS + 0.5) {
                        BlockState state;
                        // A viewing band round the front half and a bigger canopy over the nose.
                        boolean band = y >= -1 && y <= 0 && z >= 0;
                        boolean canopy = z >= 5 && y >= -2 && y <= 3 && Math.abs(x) <= 4;
                        if (band || canopy) {
                            state = window;
                        } else if (z <= -RADIUS + 1 && Math.abs(x) <= 2 && Math.abs(y) <= 2) {
                            state = Math.abs(x) <= 1 && Math.abs(y) <= 1 ? engineGlow : engineCase;
                        } else if (y >= RADIUS - 1 && Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                            state = ceilingLight;
                        } else if (y == 5 && Math.floorMod(x + z, 4) == 0) {
                            state = ceilingLight;
                        } else if (y <= -6) {
                            state = belly;
                        } else if (y == 3 || y == -5 || Math.abs(x) == 1 && y > -5) {
                            state = hullPanel;
                        } else {
                            state = hull;
                        }
                        put(x, y, z, state);
                    } else if (d > RADIUS + 0.5 && d <= RADIUS + 1.3 && y == -3) {
                        put(x, y, z, trim); // equator ring
                    } else if (d <= RADIUS - 0.5 && y == FLOOR_Y) {
                        boolean ring = Math.abs(d - 5.5) < 0.6;
                        put(x, y, z, ring && Math.floorMod(x + z, 3) == 0 ? vent : ring ? floorRing : floor);
                    }
                }
            }
        }
        // The reactor core runs floor to ceiling in the middle.
        put(0, SEAT_Y, 0, coreCap);
        for (int y = SEAT_Y + 1; y <= RADIUS - 3; y++) {
            put(0, y, 0, core);
        }
        put(0, RADIUS - 2, 0, coreCap);
        for (int y = RADIUS + 1; y <= RADIUS + 2; y++) {
            put(0, y, 0, antenna);
        }
        put(0, RADIUS + 3, 0, beacon);

        for (Seat seat : SEATS) {
            put(seat.pos, chair(seat.yaw));
        }
        // Dashboard ahead of the front row, side stations and the engineering bench behind.
        for (int x = -3; x <= 3; x++) {
            put(x, SEAT_Y, 5, console);
        }
        BlockState computer = block("refurbished_furniture:computer", Blocks.LECTERN);
        put(-3, SEAT_Y + 1, 5, facing(computer, 0));
        put(3, SEAT_Y + 1, 5, facing(computer, 0));
        for (int z = -1; z <= 1; z++) {
            put(-6, SEAT_Y, z, console);
            put(6, SEAT_Y, z, console);
        }
        put(-6, SEAT_Y + 1, 0, facing(computer, -90));
        put(6, SEAT_Y + 1, 0, facing(computer, 90));
        for (int x = -3; x <= 3; x++) {
            put(x, SEAT_Y, -5, console);
        }
        put(0, SEAT_Y + 1, -5, facing(computer, 180));
    }

    /** A padded armchair (Refurbished Furniture's single sofa, or a steel stair) with its back behind someone facing {@code yaw}. */
    private static BlockState chair(float yaw) {
        BlockState chair = block("refurbished_furniture:gray_sofa",
                BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("ad_astra:steel_plating_stairs")).orElse(Blocks.POLISHED_ANDESITE_STAIRS));
        // Both name their back side "facing".
        return with(chair, "facing", direction(yaw + 180.0F));
    }

    /** Something with a front (a screen) turned to face whoever sits looking at {@code yaw}. */
    private static BlockState facing(BlockState state, float sitterYaw) {
        return with(state, "facing", direction(sitterYaw + 180.0F));
    }

    private static String direction(float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90.0F), 4);
        return switch (quarter) {
            case 0 -> "south";
            case 1 -> "east";
            case 2 -> "north";
            default -> "west";
        };
    }

    private static BlockState block(String id, Block fallback) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
        return block.orElse(fallback).defaultBlockState();
    }

    /** Sets a property by name if the block has it, so modded and vanilla stand-ins share one call. */
    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? state : set(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    /** A point on the outer hull, for sparks and venting. */
    public static Vec3 hullPoint(double x, double y, double z) {
        return new Vec3(x, y, z).normalize().scale(RADIUS + 0.6);
    }

    private void put(int x, int y, int z, BlockState state) {
        put(new BlockPos(x, y, z), state);
    }

    private void put(BlockPos pos, BlockState state) {
        blocks.put(pos, state);
    }

    public Map<BlockPos, BlockState> blocks() {
        return blocks;
    }

    public BlockState get(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
}
