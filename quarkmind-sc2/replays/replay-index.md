# SC2 Replay Index

Living index of replay datasets available for feeding into `ReplaySimulatedGame`.
Update this file when new replays or datasets are downloaded.

**Format:** Pre-processed JSON from SC2EGSet — no need to run `RepParserEngine`, the JSON already contains all tracker events, player data, and game metadata.

---

## How to Use

The JSON files in each dataset contain the full event stream. Key fields:
- `ToonPlayerDescMap` — player names, races, results, SQ, APM
- `trackerEvents` — `UnitBornEvent`, `PlayerStatsEvent`, `UpgradeEvent` etc. (frame-by-frame)
- `header.elapsedGameLoops` — total game length (divide by 22.4 for seconds)
- `metadata.mapName` — map name
- `details.timeUTC` — game date

---

## Dataset 1: IEM Season 10 Taipei (2016)

**Source:** [SC2EGSet on Zenodo](https://zenodo.org/records/14963484) (CC BY 4.0)  
**Downloaded:** 2026-04-06  
**Local path:** `replays/2016_IEM_10_Taipei.zip` (11 MB)  
**Format:** Nested ZIP → `*_data.zip` → `<hash>.SC2Replay.json`  
**Game version:** 3.1.1.39948 (Legacy of the Void, January 2016)  
**Total replays:** 30 | **Protoss games:** 21  
**Matchups:** 8×PvP, 5×PvT/TvP, 4×PvZ/ZvP, 9×TvZ/ZvT  
**Maps:** Central Protocol, Dusk Towers, Lerilak Crest, Orbital Shipyard, Prion Terraces, Ruins of Seras, Ulrena  

### Players
| Player | Race | Notes |
|---|---|---|
| sOs | Protoss | Tournament winner. Known for creative, unconventional play. |
| herO | Protoss | Semi-finalist. Strong macro player. |
| Lilbow | Protoss | Quarter-finalist. European Protoss. |
| MinChul (MC) | Protoss | Quarter-finalist. |
| ByuN | Terran | Finalist. Very aggressive bio play. |
| Polt | Terran | Quarter-finalist. |
| Soulkey | Zerg | Semi-finalist. |
| Snute | Zerg | Quarter-finalist. European Zerg. |

### Replay List (Protoss games only)

| # | Hash | Matchup | Map | Duration | Stage | Players | Winner | Labels |
|---|---|---|---|---|---|---|---|---|
| 1 | `095724b...` | TvP | Lerilak Crest | 6m22s | QF | ByuN vs Lilbow | ByuN | `early-game` `terran-aggression` `short` |
| 2 | `b09eebe...` | TvP | Dusk Towers | 5m42s | QF | ByuN vs Lilbow | ByuN | `early-game` `terran-aggression` `short` |
| 3 | `0e0b1a5...` | TvP | Orbital Shipyard | 10m51s | QF | ByuN vs Lilbow | ByuN | `mid-game` `pvt` |
| 4 | `15ad08e...` | PvP | Dusk Towers | 5m38s | QF | MinChul vs sOs | sOs | `early-game` `pvp` `short` |
| 5 | `1e8c6...` | PvP | Ruins of Seras | 12m06s | QF | sOs vs MinChul | sOs | `mid-game` `pvp` |
| 6 | `...` | PvP | Orbital Shipyard | 7m55s | QF | sOs vs MinChul | sOs | `early-game` `pvp` |
| 7 | `...` | ZvP | Dusk Towers | 10m28s | QF | Snute vs herO | herO | `mid-game` `pvz` |
| 8 | `...` | ZvP | Lerilak Crest | 14m30s | QF | Snute vs herO | herO | `mid-game` `pvz` `protoss-wins` |
| 9 | `...` | PvZ | Ruins of Seras | 4m14s | QF | herO vs Snute | Snute | `very-short` `pvz` `zerg-wins` |
| 10 | `...` | PvZ | Central Protocol | 13m05s | QF | herO vs Snute | herO | `mid-game` `pvz` `protoss-wins` |
| 11 | `...` | PvP | Orbital Shipyard | 5m34s | SF | herO vs sOs | herO | `early-game` `pvp` `short` |
| 12 | `...` | PvP | Prion Terraces | 16m30s | SF | herO vs sOs | sOs | `late-game` `pvp` `long` |
| 13 | `...` | PvP | Dusk Towers | 7m59s | SF | sOs vs herO | herO | `early-game` `pvp` |
| 14 | `...` | PvP | Ruins of Seras | 5m43s | SF | sOs vs herO | sOs | `early-game` `pvp` `short` |
| 15 | `...` | PvP | Lerilak Crest | 22m13s | SF | sOs vs herO | sOs | `late-game` `pvp` `very-long` |
| 16 | `...` | PvT | Ruins of Seras | 21m13s | Final | sOs vs ByuN | sOs | `late-game` `pvt` `protoss-wins` `very-long` |
| 17 | `...` | PvT | Dusk Towers | 9m31s | Final | sOs vs ByuN | ByuN | `mid-game` `pvt` `terran-wins` |
| 18 | `...` | TvP | Prion Terraces | 9m38s | Final | ByuN vs sOs | sOs | `mid-game` `pvt` `protoss-wins` |
| 19 | `...` | PvT | Orbital Shipyard | 6m53s | Final | sOs vs ByuN | sOs | `early-game` `pvt` `protoss-wins` |
| 20 | `...` | TvP | Lerilak Crest | 5m36s | Final | ByuN vs sOs | ByuN | `early-game` `pvt` `terran-wins` `short` |
| 21 | `...` | TvP | Central Protocol | 5m35s | Final | ByuN vs sOs | sOs | `early-game` `pvt` `protoss-wins` `short` |

### Good Games for Specific Scenarios

| Scenario | Recommended | Why |
|---|---|---|
| **Standard Protoss economy** | Any Final game (sOs) | sOs plays clean macro, representative Protoss economic curve |
| **Early aggression / short game** | QF ByuN vs Lilbow G1 (5m42s) | Fast bio pressure, good for testing crisis response |
| **Long macro game** | SF sOs vs herO G5 (22m13s) | Full tech tree, late-game army compositions |
| **PvP mirror** | SF sOs vs herO series | Pure Protoss vs Protoss, easier to map to our domain model |
| **Timing attack** | QF herO vs Snute G1 (4m14s) | Very short — zerg all-in, good for spawn-enemy-attack scenario |

---

## Dataset 3: IEM PyeongChang 2018 — Cross-Patch Validation

**Source:** [SC2ReSet on Zenodo](https://zenodo.org/records/5575797) (CC BY 4.0)
**Downloaded:** 2026-10-05
**Local path:** `../quarkmind-classifier/data/replay_packs/2018_IEM_PyeongChang/`
**Format:** Raw `.SC2Replay` — full replays with tracker events
**baseBuild:** 60321 (SC2 ~4.1.x, February 2018)
**Total replays:** 62
**AbilityProfile:** V4_9_3 (offset=0)
**Gameplay upgrade accuracy:** 98.2%

---

## Dataset 4: ASUS ROG Online 2020 — Cross-Patch Validation

**Source:** [SC2ReSet on Zenodo](https://zenodo.org/records/5575797) (CC BY 4.0)
**Downloaded:** 2026-10-05
**Local path:** `../quarkmind-classifier/data/replay_packs/2020_ASUS_ROG_Online/`
**Format:** Raw `.SC2Replay` — full replays with tracker events
**baseBuild:** 82457 (SC2 ~5.0.x, 2020)
**Total replays:** 107
**AbilityProfile:** HSC_2025 (offset=2)
**Gameplay upgrade accuracy:** 95.3%

---

## Dataset 5: DreamHack Dallas 2025 — Cross-Patch Validation

**Source:** Tournament replay pack
**Downloaded:** 2025-05-25
**Local path:** `../quarkmind-classifier/data/replay_packs/2025_DreamHack_Dallas/`
**Format:** Raw `.SC2Replay` — full replays with tracker events
**baseBuild:** 93333 (SC2 ~5.0.13, 2025)
**Total replays:** 64
**AbilityProfile:** HSC_2025 (offset=2)
**Gameplay upgrade accuracy:** 95.8%

---

## Dataset 6: Esports World Cup 2025

**Source:** Tournament replay pack
**Downloaded:** 2025-07-25
**Local path:** `../quarkmind-classifier/data/replay_packs/2025_Esports_World_Cup/`
**Format:** Raw `.SC2Replay` — full replays with tracker events
**Total replays:** 113

---

## Dataset 7: FEL Cracow 2025

**Source:** Tournament replay pack
**Downloaded:** 2025-07-28
**Local path:** `../quarkmind-classifier/data/replay_packs/2025_FEL_Cracow/`
**Format:** Raw `.SC2Replay` — full replays with tracker events
**baseBuild:** 94137 (SC2 5.0.14, 2025)
**Total replays:** 77

---

## TODO — Additional Datasets to Download

| Dataset | Source | Priority | Why |
|---|---|---|---|
| SC2EGSet 2022 DH Masters Atlanta | Zenodo (662 MB) | Medium | More recent LotV meta, larger variety |
| SC2EGSet 2019 WCS Summer | Zenodo (265 MB) | Low | More variety but older meta |
| Any local SC2 replays | `~/Documents/StarCraft II/...` | — | None found on this machine yet |

## Notes

- All SC2EGSet files are **pre-processed JSON** — no need to use `RepParserEngine` or `scelight-s2protocol`. The JSON already has all tracker events extracted.
- Game loops ÷ 22.4 = game time in seconds (Faster speed is 22.4 game loops/second)
- `SQ` (Spending Quotient) measures economic efficiency — higher = fewer wasted resources
- Labels: `early-game` (<8 min), `mid-game` (8-15 min), `late-game` (>15 min), `very-long` (>20 min), `short` (<6 min)

---

## Dataset 2: AI Arena Bot Replays — April 2026

**Source:** [AI Arena](https://aiarena.net) ladder — live bot vs bot matches (account required)  
**Downloaded:** 2026-04-06  
**Local path:** `replays/aiarena_protoss/` (29 `.SC2Replay` files)  
**Format:** Raw `.SC2Replay` — use `RepParserEngine.parseReplay(path)` to parse  
**Game version:** Build 75689 (AI Arena's fixed SC2 LotV version)  
**Parseable:** 22/29 (7 appear to be a newer build not yet in s2protocol .dat files)  
**Maps:** Magannatha AIE, Torches AIE, Persephone AIE, Ley Lines AIE, Pylon AIE, Ultralove AIE, Incorporeal AIE  

### Bots

| Bot | Race | Style notes |
|---|---|---|
| **ArgoBot** (ID=966) | Protoss | Active ladder bot |
| **Nothing** (ID=971) | Protoss | Consistent 8-9min games — likely fixed opening |
| **puck** (ID=943) | Protoss | 10-11min games vs Zerg |
| **Starlight** (ID=1052) | Protoss | Variable — short losses, long wins |
| **Tyckles** (ID=112) | Protoss | Long games — macro player, goes 44min |
| **Zozo** (ID=496) | Protoss | Mix of results |

### Parsed Replays (22 usable)

| File | Map | Duration | Matchup | Result | Labels |
|---|---|---|---|---|---|
| `ArgoBot_4721229.SC2Replay` | Magannatha AIE | 9m58s | PvT | ArgoBot wins | `pvt` `protoss-wins` `mid-game` |
| `ArgoBot_4721230.SC2Replay` | Magannatha AIE | 9m57s | PvT | ArgoBot wins | `pvt` `protoss-wins` `mid-game` |
| `Nothing_4720935.SC2Replay` | Persephone AIE | 18m48s | PvT | RustyNikolaj wins | `pvt` `terran-wins` `late-game` |
| `Nothing_4720936.SC2Replay` | Torches AIE | 8m21s | PvZ | Nothing wins | `pvz` `protoss-wins` `mid-game` |
| `Nothing_4720937.SC2Replay` | Magannatha AIE | 8m07s | PvP | Nothing wins | `pvp` `protoss-wins` `mid-game` |
| `Nothing_4720938.SC2Replay` | Ley Lines AIE | 8m32s | PvZ | Nothing wins | `pvz` `protoss-wins` `mid-game` |
| `Nothing_4720939.SC2Replay` | Persephone AIE | 8m18s | PvZ | Nothing wins | `pvz` `protoss-wins` `mid-game` |
| `puck_4720480.SC2Replay` | Persephone AIE | 4m13s | PvZ | puck wins | `pvz` `protoss-wins` `early-game` `short` |
| `puck_4721233.SC2Replay` | Ultralove AIE | 10m39s | PvZ | Eris wins | `pvz` `zerg-wins` `mid-game` |
| `puck_4721235.SC2Replay` | Magannatha AIE | 11m25s | PvZ | Eris wins | `pvz` `zerg-wins` `mid-game` |
| `Starlight_4721163.SC2Replay` | Persephone AIE | 13m12s | PvZ | DoopyBot wins | `pvz` `zerg-wins` `mid-game` |
| `Starlight_4721164.SC2Replay` | Pylon AIE | 13m55s | PvP | Starlight wins | `pvp` `protoss-wins` `mid-game` |
| `Starlight_4721165.SC2Replay` | Magannatha AIE | 6m25s | PvT | Siriusly wins | `pvt` `terran-wins` `early-game` |
| `Starlight_4721166.SC2Replay` | Persephone AIE | 20m37s | PvZ | Starlight wins | `pvz` `protoss-wins` `late-game` `long` |
| `Tyckles_4721034.SC2Replay` | Ley Lines AIE | 15m36s | PvT | RustyNikolaj wins | `pvt` `terran-wins` `late-game` |
| `Tyckles_4721035.SC2Replay` | Ultralove AIE | 11m54s | PvZ | DoopyBot wins | `pvz` `zerg-wins` `mid-game` |
| `Tyckles_4721036.SC2Replay` | Magannatha AIE | 43m37s | PvP | Tyckles wins | `pvp` `protoss-wins` `very-long` `marathon` |
| `Tyckles_4721038.SC2Replay` | Ley Lines AIE | 25m17s | PvZ | Belzebuth wins | `pvz` `zerg-wins` `late-game` `long` |
| `Zozo_4720216.SC2Replay` | Incorporeal AIE | 9m14s | PvP | Zozo wins | `pvp` `protoss-wins` `mid-game` |

### Good Games for Specific Scenarios

| Scenario | File | Why |
|---|---|---|
| **Consistent Protoss opening** | `Nothing_4720936/38/39.SC2Replay` | Nothing wins 4 straight in 8-9min — identical build order, great baseline |
| **Short early pressure** | `puck_4720480.SC2Replay` | 4min PvZ — aggressive early game, fast puck win |
| **Long macro PvP** | `Tyckles_4721036.SC2Replay` | 44min PvP — full tech, late-game colossus/carrier compositions |
| **Protoss vs Terran loss** | `Nothing_4720935.SC2Replay` | 19min loss — good example of how Terran beats macro Protoss |
| **Standard mid-game** | `ArgoBot_4721229.SC2Replay` | Clean 10min PvT win, consistent bot behaviour |

### Notes for ReplaySimulatedGame

- **Nothing bot** has the most consistent build order — 4 games on similar maps, all ~8min wins. Load these as the "standard Protoss opening" baseline.
- All maps are AI Arena season maps ("AIE" suffix) — different from ladder maps. Shouldn't matter for testing.
- Build 75689 is within Scelight's supported range (max: 81009). ✅
- 7 unparseable replays (ArgoBot_4721222, ArgoBot_4721231, puck_4720479, puck_4721234, Starlight_4721162, Tyckles_4721037, Zozo_4720215/17/32/36) — likely newer SC2 build. Add new .dat files to scelight-s2protocol when available.

