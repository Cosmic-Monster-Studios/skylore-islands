# Arrival Cutscene - Dialogue Script (draft)

Opening cutscene for Skylore: a sphere-shaped exploratory vessel with one crew
member from each playable species goes looking for Atmos, gets hurt, and crashes.
It ends where `CrashArrival.java` takes over ("SIGNAL LOST", black box UNIT 7-C).

## 1. Ship name

The ship is the *Phlox*: a small sphere vessel built for exploration.

**Mission justification.** Atmos cannot be found. It moves with no clean trajectory,
instruments read vacuum where its islands are, delivery drones lose lock at the
cloud sea, and every ship that got there arrived by accident: the *Wanderlight*
found cloud and stone it was not looking for, and the *Galileo of the Violet Hour*
said Atmos "found the side of our hull". So this mission is not a survey run. It
is a stubborn, mixed-species attempt to settle the rumors with one crew member from
each people. The Fortis go because finding out is a pillar. The Beryl go because
the Church calls it Eden. The Laevis go because their ancestors stumbled onto it
once. The Teknari go for the portfolio, the Automaton for something worth doing,
the Enoki because someone has to feed everyone, and the Tenebris for the empty islands.
The ship's sensors read vacuum all the way in, which is why nobody sees the sky coming.

## 2. Voice notes

Shipboard roles are suggestions. Nobody is addressed by name, so any species can be missing.

| Species | Role | Voice |
|---|---|---|
| Fortis | Mission lead | Warm, blunt, steady. Talks in short commands under pressure. Quotes the Pillars without irony. |
| Enoki | Cook / medic | Kind, plural-minded ("we", "everyone"). Wants to feed people. Wilts at violence and worries about the others first. |
| Teknari | Engineer | Upbeat 1950s-ad cheer with corporate jargon. Sincerely proud of gear that "mostly" passed QA. |
| Beryl | Chaplain | Earnest and devout (the Earth Spirit, Eden). Imitates human idioms faithfully and a little wrong. |
| Automaton | Navigation / systems | Quiet, precise, dry. Jokes about not eating and about endings. Calm even in a crisis. |
| Tenebris | Night watch | Few words, patient disinterest. Prefers to be alone. Practical, never sentimental. |
| Laevis | Pilot | Slow, sunny, folksy. Distrusts batteries. Has family stories about Atmos. |

## 3. Script

Total runtime about 130 s. The cruise and the trouble are paced so every line can
be read; the impact lands at 127.6 s.

**Absent-species rule.** The player is one of the seven, and that species' NPC is
not shown. Every spoken line has an alternate speaker who reads it if the primary
is absent (verbatim unless an alt variant is given). No plot fact depends on dialogue:
each trouble beat is carried by a SHIP line and/or a visual cue. SHIP covers SFX,
staging cues and the ship computer, which is always present. Computer lines are
in CAPS so they match the black box style.

| # | Beat | t (s) | Speaker | Alt speaker | Line |
|---|---|---|---|---|---|
| 1 | 1 Title card | 0 | SHIP | - | [Exterior: a small sphere drifting through starfield. Low hum.] |
| 2 | 1 Title card | 2 | SHIP | - | EXPLORATORY VESSEL "PHLOX" - CREW: 7 - TARGET: ATMOS |
| 3 | 2 Cruise int. | 7.6 | Fortis | Laevis | Day forty. Still no Atmos. Still going to find out. |
| 4 | 2 Cruise int. | 14 | Automaton | Teknari | Long-range scan: vacuum. It has been vacuum for forty days. |
| 5 | 2 Cruise int. | 20.4 | Teknari | Automaton | Sensors are top of the line. Passed QA. Mostly. Eventually. |
| 6 | 2 Cruise int. | 26.8 | Automaton | Fortis | Atmos moves with no trajectory. Plotting it is superstition. |
| 7 | 2 Cruise int. | 33.2 | Laevis | Beryl | You don't find Atmos. My grandmother swore it finds you. |
| 8 | 2 Cruise int. | 39.6 | Beryl | Enoki | Islands held in the sky like beads. The Church calls it Eden. |
| 9 | 2 Cruise int. | 46 | Tenebris | Automaton | Empty islands. Nobody on them. I came for the quiet. |
| 10 | 2 Cruise int. | 50.8 | Enoki | Fortis | The quiet can wait. Supper's ready, and everyone eats together. |
| 11 | 2 Cruise int. | 55.6 | Tenebris | Teknari | I'll eat at my station. |
| 12 | 2 Cruise int. | 58.8 | Automaton | Laevis | I don't eat. Save me a bowl anyway. (alt: Save his bowl too.) |
| 13 | 3 Ext. sparks | 62 | SHIP | - | [Exterior: sparks crawl along a hull seam. Whine rises. Bang.] |
| 14 | 4 Trouble | 66.8 | SHIP | - | WARNING. REACTOR SURGE, DECK 2. |
| 15 | 4 Trouble | 70 | Teknari | Automaton | Reactor's spiking! Venting deck 2, grab something! |
| 16 | 4 Trouble | 74.8 | Fortis | Laevis | Everyone to the core ring. Now. Move! |
| 17 | 4 Trouble | 79.6 | SHIP | - | HULL BREACH, DECK 3. SEALING BULKHEADS. [Air roars, door slams] |
| 18 | 4 Trouble | 84.4 | Enoki | Beryl | Deck 3 is open to space! Is anyone still down there? |
| 19 | 4 Trouble | 89.2 | Tenebris | Fortis | Deck 3 was empty. I checked. Keep moving. |
| 20 | 4 Trouble | 95.6 | Automaton | Teknari | Nav array's failing. Rerouting... no. It's gone. |
| 21 | 4 Trouble | 100.4 | SHIP | - | NAV ARRAY OFFLINE. |
| 22 | 4 Trouble | 103.6 | Laevis | Fortis | We were always flying blind out here. Cut our speed. |
| 23 | 4 Trouble | 108.4 | Teknari | Fortis | Too much mass to brake! Dump the cargo pods! |
| 24 | 4 Trouble | 113.2 | SHIP | - | CARGO PODS JETTISONED: 3. BRAKING. [Three clunks, retros roar] |
| 25 | 4 Trouble | 116.4 | Beryl | Enoki | Earth Spirit, if you are listening, now would be very good. |
| 26 | 5 Snap | 119.6 | SHIP | - | [Hard cut: void becomes blue sky. Wind. Island ahead, ~300 m.] |
| 27 | 5 Snap | 120.6 | Laevis | Beryl | That's... sky. That's ATMOS! |
| 28 | 5 Snap | 121.6 | Teknari | Automaton | Sensors still read vacuum! |
| 29 | 6 Chase | 122.6 | Fortis | Tenebris | Pull up! Pull up! |
| 30 | 6 Chase | 123.6 | SHIP | - | IMPACT IMMINENT. [Alarm. Island fills the window.] |
| 31 | 6 Chase | 125.6 | Automaton | Teknari | Brace! We're going to hit the isl— |
| 32 | 7 Impact | 127.6 | SHIP | - | [Impact. Hard cut to black. Silence. One recorder beep.] |
| 33 | 7 Impact | 129.6 | SHIP | - | [Hand off to CrashArrival: "SIGNAL LOST"] |

33 lines: 20 spoken, 13 SHIP cues. Each species has 2 to 4 spoken lines and at least one alternate slot.

Implementation notes:
- Line 31 is the cut-off line. It ends on the em dash with no closing word.
- Line 26 should have no warning sound or alarm before it. Alarms resume at line 30.
- Line 28 echoes the lore that instruments read vacuum where Atmos's islands are.
- Line 12's alt variant is only for when the Automaton is absent. The alt
  speaker then says it about the Automaton, who is present but silent.
  (If that reads oddly, cut line 12; nothing depends on it.)

## 4. Lore relied on

- Species personalities, roles and humor: Fortis (Four Pillars, Water = "go and find
  out", the open table for exiles, big hands), Enoki (groups, cooking, kindness,
  wilting at violence), Teknari (1950s broadcasts, corporation, QA shipped anyway),
  Beryl (Earth Spirit, Church of Ender, Atmos as Eden, slightly-off human idioms),
  Automaton (quiet, programming and networks, no eating, jokes about lunch and
  endings), Tenebris (solitary, patient disinterest, empty islands), Laevis
  (ancestors found Atmos, slow, warm, wary of batteries). Source:
  `pack/kubejs/data/skylore/origins/{fortis,enoki,teknari,beryl,automaton,tenebris,laevis}.json`.
- "Atmos cannot be found", "Atmos moves. no clean trajectory. plotting is superstition
  with numbers", "instruments find vacuum where the island sits": `loot_table/lore/mine.json`, `all.json`.
- *Wanderlight*: a Fortis hull with a mixed-species crew, out to find Atmos;
  "instruments found nothing. We found cloud and stone by accident". Source:
  `loot_table/lore/all.json`, `shipwreck.json`, `aviary.json`.
- *Galileo of the Violet Hour*: "Atmos found the side of our hull... Instruments
  stayed blank"; islands held "the way a hand holds beads"; Eden. Source:
  `loot_table/lore/end.json`, `shipwreck.json`, `all.json`.
- Drones losing lock after the cloud sea, NOT FOUND / NO TRAJECTORY: `loot_table/lore/mine.json`.
- Teknari QA "REJECTED / SHIPPED ANYWAY": `loot_table/lore/*` (QA Rejection Form 4471).
- Black box beats and wording (UNIT 7-C, T-00:41 reactor surge deck 2, T-00:33 hull
  breach deck 3, T-00:20 NAV ARRAY OFFLINE, T-00:12 cargo pods jettisoned (3),
  T-00:04 impact imminent, "SIGNAL LOST"): `Skylore Islands/src/main/java/com/skylore/islands/gameplay/CrashArrival.java`.
- `server_scripts/10_story_advancements.js` only references `skylore:story/atmos_truth`
  by ID with no text, so the script avoids revealing any "truth" about Atmos.
