package com.skylore.islands.api;

/** One island grid cell. {@link #pack()} matches the keys used by CrashIslandSavedData. */
public record IslandCell(int x, int z) {
    public long pack() {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static IslandCell unpack(long key) {
        return new IslandCell((int) (key >> 32), (int) key);
    }
}
