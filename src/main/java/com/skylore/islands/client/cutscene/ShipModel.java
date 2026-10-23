package com.skylore.islands.client.cutscene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * The sphere ship, in ship-local block coordinates centred on (0,0,0) with the nose along +Z.
 * Built procedurally so the cutscene needs no structure file; seats sit in a ring on the deck.
 */
public final class ShipModel {
    public static final int RADIUS = 7;
    /** Top of the seat slabs, where a sitting crew member's hips rest. */
    public static final double SEAT_TOP = -2.5;
    public static final double SEAT_RING = 3.2;
    /** Seat angles in degrees from the nose; 0 is the player's, the rest are the crew's, in order. */
    public static final float[] SEAT_ANGLES = {0, -35, 35, -70, 70, -110, 110, 180};
    /** Where the player's eyes are when seated. */
    public static final Vec3 PLAYER_EYE = new Vec3(0.0, -1.6, SEAT_RING);

    private static final int FLOOR_Y = -4;

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    public ShipModel() {
        BlockState hull = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState panel = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState window = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        BlockState trim = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
        BlockState seat = Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState desk = Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState core = Blocks.BEACON.defaultBlockState();
        BlockState coreCase = Blocks.GLASS.defaultBlockState();

        for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
            for (int y = -RADIUS - 1; y <= RADIUS + 1; y++) {
                for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d > RADIUS - 0.5 && d <= RADIUS + 0.5) {
                        BlockState state;
                        if (y >= -2 && y <= 1 && z >= -2) {
                            state = window;
                        } else if (y >= RADIUS - 1 && Math.abs(x) <= 1 && Math.abs(z) <= 1) {
                            state = light;
                        } else if (z <= -RADIUS + 1 && Math.abs(x) <= 1 && Math.abs(y) <= 1) {
                            state = light; // engine glow
                        } else if (y == -3 || (Math.abs(x) + Math.abs(z)) % 5 == 0 && y > 1) {
                            state = panel;
                        } else {
                            state = hull;
                        }
                        put(x, y, z, state);
                    } else if (d > RADIUS + 0.5 && d <= RADIUS + 1.3 && y == -3) {
                        put(x, y, z, trim); // equator ring
                    } else if (d <= RADIUS - 0.5 && y == FLOOR_Y) {
                        put(x, y, z, floor);
                    }
                }
            }
        }
        for (int y = FLOOR_Y + 1; y <= RADIUS - 2; y++) {
            put(0, y, 0, y % 2 == 0 ? core : coreCase);
        }
        for (float angle : SEAT_ANGLES) {
            Vec3 s = seat(angle);
            put((int) Math.floor(s.x), FLOOR_Y + 1, (int) Math.floor(s.z), seat);
        }
        for (int x = -2; x <= 2; x++) {
            put(x, FLOOR_Y + 1, 5, desk);
        }
    }

    /** Ship-local position of the seat at this angle (the centre of the seat top). */
    public static Vec3 seat(float angleDeg) {
        double a = Math.toRadians(angleDeg);
        return new Vec3(Math.sin(a) * SEAT_RING, SEAT_TOP, Math.cos(a) * SEAT_RING);
    }

    /** A point on the outer hull, for sparks and venting. */
    public static Vec3 hullPoint(double x, double y, double z) {
        return new Vec3(x, y, z).normalize().scale(RADIUS + 0.6);
    }

    private void put(int x, int y, int z, BlockState state) {
        blocks.put(new BlockPos(x, y, z), state);
    }

    public Map<BlockPos, BlockState> blocks() {
        return blocks;
    }

    public BlockState get(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
}
