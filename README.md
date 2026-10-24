# Skylore Islands

Skylore Islands is a worldgen mod for **Minecraft 1.21.1** (NeoForge 21.1.x).
It turns the Overworld (and the Nether) into floating sky islands with empty void inbetween, mixed biomes, and most vanilla / modded structures still working.

Needs a **new world**. Existing terrain wont regenerate.

---

## Features

- Floating overworld with real void between islands. No ocean floor, no bedrock slab.
- Default spacing is 384 blocks (cell grid). Gaps are usually a few hundred blocks, sometimes more.
- Island height goes from around Y 20 up to about 260 so the underside can still hit deep ores.
- Size varies alot. Small islands, big plateaus, rare mega continents, the occasional ocean bowl or caldera lake.
- Small rock islets drift just off most shorelines, and big islands get larger satellites. About 1 in 40 of them hides a buried Sky Cache under a lantern post (loot table `skylore_islands:chests/sky_cache`).
- Nether uses it's own island map (not a copy of the overworld) with lava basins instead of water.
- Biomes still use vanilla climate. Modded overworld biomes should show up to. Island ground always reads as inland, so you dont get ocean or beach biomes on dry land.
- Rare mushroom islands (about 1 in 60). Caves inside thick island undersides can be lush caves or dripstone caves, and the deepest bellies can hold the deep dark.
- Structures try to sit on land or in bowls. Huge ones (mansions, Cataclysm stuff, etc) only on mega islands. Airships can spawn in open void.
- When a structure's spawn spot lands in the void it moves to the nearest island in the same placement region, so structures show up about as often as vanilla. `/locate` and compasses point at the moved spot.
- Trial chambers are buried inside mega islands with a roof of rock over them, about one per mega island.
- Water stays in bowls / lakes / rivers. It shouldnt sheet off the rim into the sky.
- Each player crash lands on there own forested island on first join: a scorched impact crater, a wrecked ship and a Black Box with a bare starter kit (loot table `skylore_islands:chests/black_box`). Cell (0,0) stays the world hub.
- Falling into the void gets you an updraft back toward land (slow falling).

---

## Config

Server config is `config/skylore_islands-server.toml` (world `serverconfig` after first load):

```toml
[general]
    cellSize = 384
    islandDensity = 70
    minIslandAltitude = 20
    maxIslandAltitude = 260
    minIslandRadius = 45
    maxIslandRadius = 170

[crash_islands]
    enabled = true
    skipOriginCell = true
    craterRadius = 12
    minTreeLogs = 12
    minIslandRadius = 40

[treasure]
    skyCacheChance = 40
    skyCacheInNether = false

[worldgen]
    relocateStructures = true
    mushroomIslandChance = 60
```

`islandDensity` 0 = hub island only. 50 is more isolated. 70 (default) is a bit denser and can clump. Changing cell size or density on an old save wont move islands you already explored.

Crash sites only go on islands whose biome is in `#skylore_islands:crash_site_biomes` (forests, taiga, jungle, savanna, cherry grove, meadow) and that actually have trees near the crater.

`skyCacheChance` is 1 in N islets / satellites, 0 turns caches off. Only new chunks get them.

`relocateStructures = false` goes back to only spawning structures whose vanilla spot already lands on an island (much rarer). `mushroomIslandChance` is 1 in N regular overworld islands, 0 turns them off. Both only affect new chunks.

---

## Notes

- Target is NeoForge 1.21.1, Java 21.
- Sea level is set to -64 and default fluid is air so aquifers dont flood the void.
- I recomend leaving aquifers off (the noise settings already do this).
- Distant Horizons is compatable, it just reads the same density as the server.

---

## Building

Java 21:

```bash
./gradlew jar
```

Jar lands in `libs/skylore_islands-<version>.jar`.
On Windows you can use `gradlew.bat` instead.
