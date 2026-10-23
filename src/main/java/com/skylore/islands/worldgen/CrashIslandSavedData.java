package com.skylore.islands.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CrashIslandSavedData extends SavedData {
    public static final String STORAGE_ID = "skylore_crash_islands";

    private static final int VERSION = 3;

    private int nextSpawnSeq;
    private final Map<UUID, Long> playerCells = new HashMap<>();
    private final Map<Long, UUID> claimedCells = new HashMap<>();
    private final Set<Long> builtCells = new HashSet<>();
    private final Map<Long, BlockPos> sites = new HashMap<>();
    private final Set<UUID> sawCutscene = new HashSet<>();

    public static CrashIslandSavedData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CrashIslandSavedData::new, CrashIslandSavedData::load, null),
                STORAGE_ID);
    }

    public static CrashIslandSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return load(tag);
    }

    public static CrashIslandSavedData load(CompoundTag tag) {
        CrashIslandSavedData data = new CrashIslandSavedData();
        data.nextSpawnSeq = tag.getInt("nextSpawnSeq");
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag row = players.getCompound(i);
            UUID uuid = row.getUUID("uuid");
            long key = pack(row.getInt("cellX"), row.getInt("cellZ"));
            data.playerCells.put(uuid, key);
        }
        for (Map.Entry<UUID, Long> entry : data.playerCells.entrySet()) {
            data.claimedCells.put(entry.getValue(), entry.getKey());
        }
        ListTag built = tag.getList("built", Tag.TAG_COMPOUND);
        for (int i = 0; i < built.size(); i++) {
            CompoundTag row = built.getCompound(i);
            long key = pack(row.getInt("cellX"), row.getInt("cellZ"));
            data.builtCells.add(key);
            if (row.contains("siteY")) {
                data.sites.put(key, new BlockPos(row.getInt("siteX"), row.getInt("siteY"), row.getInt("siteZ")));
            }
        }
        ListTag saw = tag.getList("sawCutscene", Tag.TAG_INT_ARRAY);
        for (Tag uuid : saw) {
            data.sawCutscene.add(NbtUtils.loadUUID(uuid));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return save(tag);
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", VERSION);
        tag.putInt("nextSpawnSeq", nextSpawnSeq);
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Long> entry : playerCells.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putUUID("uuid", entry.getKey());
            row.putInt("cellX", unpackX(entry.getValue()));
            row.putInt("cellZ", unpackZ(entry.getValue()));
            players.add(row);
        }
        tag.put("players", players);
        ListTag built = new ListTag();
        for (long key : builtCells) {
            CompoundTag row = new CompoundTag();
            row.putInt("cellX", unpackX(key));
            row.putInt("cellZ", unpackZ(key));
            BlockPos site = sites.get(key);
            if (site != null) {
                row.putInt("siteX", site.getX());
                row.putInt("siteY", site.getY());
                row.putInt("siteZ", site.getZ());
            }
            built.add(row);
        }
        tag.put("built", built);
        ListTag saw = new ListTag();
        for (UUID uuid : sawCutscene) {
            saw.add(NbtUtils.createUUID(uuid));
        }
        tag.put("sawCutscene", saw);
        return tag;
    }

    public int[] assignedCell(UUID uuid) {
        Long key = playerCells.get(uuid);
        if (key == null) {
            return null;
        }
        return new int[]{unpackX(key), unpackZ(key)};
    }

    public void assign(UUID uuid, int cellX, int cellZ) {
        long key = pack(cellX, cellZ);
        playerCells.put(uuid, key);
        claimedCells.put(key, uuid);
        setDirty();
    }

    /** Drops a player's lease (e.g. the island could not be built) so the cell can be reused. */
    public void release(UUID uuid) {
        Long key = playerCells.remove(uuid);
        if (key != null) {
            claimedCells.remove(key);
            setDirty();
        }
    }

    public boolean isClaimed(int cellX, int cellZ) {
        return claimedCells.containsKey(pack(cellX, cellZ));
    }

    public boolean isBuilt(int cellX, int cellZ) {
        return builtCells.contains(pack(cellX, cellZ));
    }

    public void markBuilt(int cellX, int cellZ) {
        builtCells.add(pack(cellX, cellZ));
        setDirty();
    }

    /** Marks a crash site built on an island cell, remembering where the player stands. */
    public void markBuilt(int cellX, int cellZ, BlockPos stand) {
        long key = pack(cellX, cellZ);
        builtCells.add(key);
        sites.put(key, stand.immutable());
        setDirty();
    }

    /** Stand position of a crash site, or null for legacy void-island leases. */
    public BlockPos site(int cellX, int cellZ) {
        return sites.get(pack(cellX, cellZ));
    }

    public boolean sawCutscene(UUID uuid) {
        return sawCutscene.contains(uuid);
    }

    public void markSawCutscene(UUID uuid) {
        if (sawCutscene.add(uuid)) {
            setDirty();
        }
    }

    /** Stand position of a player's built crash site, or null. */
    public BlockPos standOf(UUID uuid) {
        Long key = playerCells.get(uuid);
        return key != null && builtCells.contains(key) ? sites.get(key) : null;
    }

    public int takeNextSeq() {
        int seq = nextSpawnSeq;
        nextSpawnSeq++;
        setDirty();
        return seq;
    }

    public static long pack(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }
}
