# EmulatedGame Accuracy Baseline — 2026-10-07

**Context:** Phase 2.5 (#379, child of #366)
**Dataset:** Oracle (118 replays, v4.9.3)
**Processed:** 118 replays, 236 player runs

---

## 1-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |      969 |     2606 |   100.0% |
| DRONE                     |     1142 |        0 |     0.0% |
| OVERLORD                  |      134 |        0 |     0.0% |
| SCV                       |     1156 |       72 |     6.2% |
| **TOTAL**                 |     3401 |     2678 |    78.7% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       61 |      267 |   100.0% |
| PYLON                     |       63 |       63 |   100.0% |
| ASSIMILATOR               |        8 |        8 |   100.0% |
| COMMAND_CENTER            |       74 |       74 |   100.0% |
| SUPPLY_DEPOT              |       74 |       74 |   100.0% |
| REFINERY                  |       28 |       28 |   100.0% |
| HATCHERY                  |       71 |       71 |   100.0% |
| EXTRACTOR                 |        6 |        6 |   100.0% |
| **TOTAL**                 |      385 |      591 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1342)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        20630 |        31329 |    51.9% |
| vespeneCurrent                      |          232 |          232 |     0.0% |
| mineralsCollectionRate              |       150658 |         2830 |    98.1% |
| vespeneCollectionRate               |         1241 |           24 |    98.1% |
| foodMade                            |         4579 |         4579 |     0.0% |
| foodUsed                            |         3338 |         2710 |    18.8% |
| workersActiveCount                  |         3162 |         2678 |    15.3% |
| mineralsUsedCurrentArmy             |            0 |            0 |     0.0% |
| mineralsUsedCurrentEconomy          |       266325 |        11900 |    95.5% |
| mineralsUsedCurrentTechnology       |            0 |            0 |     0.0% |
| vespeneUsedCurrentArmy              |            0 |            0 |     0.0% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |            0 |            0 |     0.0% |

## 3-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1259 |     2549 |   100.0% |
| ZEALOT                    |       29 |       18 |    62.1% |
| STALKER                   |       22 |       11 |    50.0% |
| ADEPT                     |       26 |        0 |     0.0% |
| SENTRY                    |        2 |       13 |   100.0% |
| ADEPT_PHASE_SHIFT         |        5 |        0 |     0.0% |
| ZERGLING                  |      296 |        0 |     0.0% |
| ROACH                     |        4 |        0 |     0.0% |
| QUEEN                     |       89 |        0 |     0.0% |
| DRONE                     |     1303 |        0 |     0.0% |
| OVERLORD                  |      180 |        0 |     0.0% |
| BANELING                  |        1 |        0 |     0.0% |
| MARINE                    |       98 |        0 |     0.0% |
| MARAUDER                  |        2 |        0 |     0.0% |
| WIDOW_MINE                |        2 |        0 |     0.0% |
| SCV                       |     1493 |       70 |     4.7% |
| REAPER                    |       47 |        0 |     0.0% |
| HELLION                   |        5 |        0 |     0.0% |
| MULE                      |       47 |        0 |     0.0% |
| **TOTAL**                 |     4910 |     2661 |    54.2% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       72 |      260 |   100.0% |
| PYLON                     |      149 |      154 |   100.0% |
| GATEWAY                   |       94 |       94 |   100.0% |
| CYBERNETICS_CORE          |       48 |       48 |   100.0% |
| ASSIMILATOR               |      106 |      106 |   100.0% |
| STARGATE                  |        2 |        2 |   100.0% |
| FORGE                     |        9 |        9 |   100.0% |
| TWILIGHT_COUNCIL          |        6 |        6 |   100.0% |
| PHOTON_CANNON             |        6 |        7 |   100.0% |
| COMMAND_CENTER            |       94 |       94 |   100.0% |
| SUPPLY_DEPOT              |      153 |      153 |   100.0% |
| BARRACKS                  |      139 |      139 |   100.0% |
| ENGINEERING_BAY           |        9 |        9 |   100.0% |
| MISSILE_TURRET            |        1 |        1 |   100.0% |
| BUNKER                    |        6 |        6 |   100.0% |
| FACTORY                   |       33 |       33 |   100.0% |
| STARPORT                  |        2 |        2 |   100.0% |
| REFINERY                  |      121 |      121 |   100.0% |
| HATCHERY                  |      119 |      119 |   100.0% |
| SPAWNING_POOL             |       62 |       62 |   100.0% |
| EVOLUTION_CHAMBER         |        1 |        1 |   100.0% |
| ROACH_WARREN              |        5 |        5 |   100.0% |
| BANELING_NEST             |        1 |        1 |   100.0% |
| SPINE_CRAWLER             |        2 |        2 |   100.0% |
| EXTRACTOR                 |       68 |       68 |   100.0% |
| UNKNOWN                   |        4 |        4 |   100.0% |
| **TOTAL**                 |     1312 |     1506 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1225)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        46751 |        83584 |    78.8% |
| vespeneCurrent                      |        24254 |        24254 |     0.0% |
| mineralsCollectionRate              |       171704 |         3670 |    97.9% |
| vespeneCollectionRate               |        39512 |         3613 |    90.9% |
| foodMade                            |         7024 |         7024 |     0.0% |
| foodUsed                            |         5150 |         2761 |    46.4% |
| workersActiveCount                  |         4000 |         2619 |    34.5% |
| mineralsUsedCurrentArmy             |        36875 |         6275 |    83.0% |
| mineralsUsedCurrentEconomy          |       385100 |        18650 |    95.2% |
| mineralsUsedCurrentTechnology       |        63850 |            0 |   100.0% |
| vespeneUsedCurrentArmy              |         4450 |         3050 |    31.5% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |         5100 |            0 |   100.0% |

## 5-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1421 |     2318 |   100.0% |
| ZEALOT                    |       53 |       29 |    54.7% |
| STALKER                   |       51 |       35 |    68.6% |
| IMMORTAL                  |       10 |        7 |    70.0% |
| OBSERVER                  |       12 |        8 |    66.7% |
| VOID_RAY                  |        2 |        2 |   100.0% |
| ADEPT                     |       20 |        7 |    35.0% |
| SENTRY                    |        7 |       20 |   100.0% |
| PHOENIX                   |        8 |        3 |    37.5% |
| ORACLE                    |        9 |        6 |    66.7% |
| WARP_PRISM                |        4 |        3 |    75.0% |
| ZERGLING                  |      531 |        0 |     0.0% |
| ROACH                     |       65 |        0 |     0.0% |
| QUEEN                     |      145 |        0 |     0.0% |
| DRONE                     |     1724 |        0 |     0.0% |
| OVERLORD                  |      305 |        0 |     0.0% |
| BANELING                  |       17 |        0 |     0.0% |
| MARINE                    |      372 |        0 |     0.0% |
| MARAUDER                  |       25 |        0 |     0.0% |
| MEDIVAC                   |        9 |        0 |     0.0% |
| SIEGE_TANK                |       33 |        0 |     0.0% |
| VIKING                    |        4 |        0 |     0.0% |
| RAVEN                     |        1 |        0 |     0.0% |
| BANSHEE                   |        2 |        0 |     0.0% |
| CYCLONE                   |        8 |        0 |     0.0% |
| LIBERATOR                 |        3 |        0 |     0.0% |
| WIDOW_MINE                |       28 |        0 |     0.0% |
| SCV                       |     1850 |       66 |     3.6% |
| REAPER                    |       36 |        0 |     0.0% |
| HELLION                   |       52 |        0 |     0.0% |
| MULE                      |       69 |        0 |     0.0% |
| **TOTAL**                 |     6876 |     2504 |    36.4% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       80 |      249 |   100.0% |
| PYLON                     |      209 |      224 |   100.0% |
| GATEWAY                   |      132 |      133 |   100.0% |
| CYBERNETICS_CORE          |       46 |       47 |   100.0% |
| ASSIMILATOR               |      120 |      120 |   100.0% |
| ROBOTICS_FACILITY         |       27 |       27 |   100.0% |
| STARGATE                  |       12 |       12 |   100.0% |
| FORGE                     |       10 |       10 |   100.0% |
| TWILIGHT_COUNCIL          |       23 |       23 |   100.0% |
| PHOTON_CANNON             |       16 |       16 |   100.0% |
| SHIELD_BATTERY            |       16 |       18 |   100.0% |
| DARK_SHRINE               |        3 |        3 |   100.0% |
| ROBOTICS_BAY              |        2 |        2 |   100.0% |
| COMMAND_CENTER            |      113 |      113 |   100.0% |
| SUPPLY_DEPOT              |      236 |      238 |   100.0% |
| BARRACKS                  |      212 |      212 |   100.0% |
| ENGINEERING_BAY           |       31 |       32 |   100.0% |
| ARMORY                    |        2 |        2 |   100.0% |
| MISSILE_TURRET            |       27 |       27 |   100.0% |
| BUNKER                    |       17 |       20 |   100.0% |
| FACTORY                   |      105 |      105 |   100.0% |
| STARPORT                  |       58 |       58 |   100.0% |
| FUSION_CORE               |        3 |        3 |   100.0% |
| REFINERY                  |      158 |      158 |   100.0% |
| HATCHERY                  |      122 |      126 |   100.0% |
| SPAWNING_POOL             |       54 |       54 |   100.0% |
| EVOLUTION_CHAMBER         |       15 |       15 |   100.0% |
| ROACH_WARREN              |       28 |       28 |   100.0% |
| BANELING_NEST             |       14 |       14 |   100.0% |
| SPINE_CRAWLER             |       20 |       20 |   100.0% |
| SPORE_CRAWLER             |       10 |       10 |   100.0% |
| HYDRALISK_DEN             |        1 |        1 |   100.0% |
| INFESTATION_PIT           |        1 |        1 |   100.0% |
| EXTRACTOR                 |      114 |      114 |   100.0% |
| UNKNOWN                   |      218 |      229 |   100.0% |
| **TOTAL**                 |     2255 |     2464 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1195)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        66699 |       127659 |    91.4% |
| vespeneCurrent                      |        43390 |        43390 |     0.0% |
| mineralsCollectionRate              |       208770 |         3978 |    98.1% |
| vespeneCollectionRate               |        50869 |         4569 |    91.0% |
| foodMade                            |         9520 |         9520 |     0.0% |
| foodUsed                            |         7483 |         2709 |    63.8% |
| workersActiveCount                  |         4907 |         2384 |    51.4% |
| mineralsUsedCurrentArmy             |       108250 |        17525 |    83.8% |
| mineralsUsedCurrentEconomy          |       478750 |        18650 |    96.1% |
| mineralsUsedCurrentTechnology       |       119150 |            0 |   100.0% |
| vespeneUsedCurrentArmy              |        24475 |         8475 |    65.4% |
| vespeneUsedCurrentEconomy           |          150 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        25075 |            0 |   100.0% |

## 7-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1319 |     1863 |   100.0% |
| ZEALOT                    |       17 |       16 |    94.1% |
| STALKER                   |       27 |       28 |   100.0% |
| IMMORTAL                  |       24 |       17 |    70.8% |
| COLOSSUS                  |        5 |        1 |    20.0% |
| CARRIER                   |        0 |        2 |     0.0% |
| DARK_TEMPLAR              |        2 |        0 |     0.0% |
| OBSERVER                  |       16 |       17 |   100.0% |
| VOID_RAY                  |       11 |       10 |    90.9% |
| ADEPT                     |       11 |        5 |    45.5% |
| SENTRY                    |        6 |       19 |   100.0% |
| PHOENIX                   |        5 |        5 |   100.0% |
| ORACLE                    |       10 |        5 |    50.0% |
| WARP_PRISM                |       10 |       11 |   100.0% |
| ZERGLING                  |      487 |        0 |     0.0% |
| ROACH                     |      287 |        0 |     0.0% |
| HYDRALISK                 |        9 |        0 |     0.0% |
| MUTALISK                  |       11 |        0 |     0.0% |
| SWARM_HOST                |        6 |        0 |     0.0% |
| QUEEN                     |      153 |        0 |     0.0% |
| DRONE                     |     1848 |        0 |     0.0% |
| OVERLORD                  |      403 |        0 |     0.0% |
| BANELING                  |       31 |        0 |     0.0% |
| CHANGELING                |        1 |        0 |     0.0% |
| MARINE                    |      605 |        0 |     0.0% |
| MARAUDER                  |       52 |        0 |     0.0% |
| MEDIVAC                   |       45 |        0 |     0.0% |
| SIEGE_TANK                |       85 |        0 |     0.0% |
| VIKING                    |       19 |        0 |     0.0% |
| RAVEN                     |        3 |        0 |     0.0% |
| BANSHEE                   |        6 |        0 |     0.0% |
| BATTLECRUISER             |        6 |        0 |     0.0% |
| CYCLONE                   |       11 |        0 |     0.0% |
| LIBERATOR                 |        9 |        0 |     0.0% |
| WIDOW_MINE                |       33 |        0 |     0.0% |
| SCV                       |     1929 |       57 |     3.0% |
| REAPER                    |       17 |        0 |     0.0% |
| HELLION                   |       39 |        0 |     0.0% |
| MULE                      |       38 |        0 |     0.0% |
| **TOTAL**                 |     7596 |     2056 |    27.1% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       68 |      202 |   100.0% |
| PYLON                     |      239 |      256 |   100.0% |
| GATEWAY                   |      141 |      144 |   100.0% |
| CYBERNETICS_CORE          |       32 |       34 |   100.0% |
| ASSIMILATOR               |      114 |      114 |   100.0% |
| ROBOTICS_FACILITY         |       24 |       25 |   100.0% |
| STARGATE                  |       16 |       16 |   100.0% |
| FORGE                     |       23 |       23 |   100.0% |
| TWILIGHT_COUNCIL          |       21 |       21 |   100.0% |
| PHOTON_CANNON             |        8 |       10 |   100.0% |
| SHIELD_BATTERY            |       16 |       21 |   100.0% |
| DARK_SHRINE               |        3 |        3 |   100.0% |
| FLEET_BEACON              |        1 |        1 |   100.0% |
| ROBOTICS_BAY              |        5 |        5 |   100.0% |
| COMMAND_CENTER            |      115 |      117 |   100.0% |
| SUPPLY_DEPOT              |      326 |      341 |   100.0% |
| BARRACKS                  |      245 |      249 |   100.0% |
| ENGINEERING_BAY           |       51 |       51 |   100.0% |
| ARMORY                    |       10 |       11 |   100.0% |
| MISSILE_TURRET            |       52 |       54 |   100.0% |
| BUNKER                    |       21 |       28 |   100.0% |
| SENSOR_TOWER              |        3 |        3 |   100.0% |
| GHOST_ACADEMY             |        2 |        2 |   100.0% |
| FACTORY                   |      135 |      135 |   100.0% |
| STARPORT                  |       84 |       84 |   100.0% |
| FUSION_CORE               |        7 |        7 |   100.0% |
| REFINERY                  |      181 |      183 |   100.0% |
| HATCHERY                  |      114 |      119 |   100.0% |
| SPAWNING_POOL             |       45 |       45 |   100.0% |
| EVOLUTION_CHAMBER         |       39 |       39 |   100.0% |
| ROACH_WARREN              |       32 |       32 |   100.0% |
| BANELING_NEST             |       23 |       23 |   100.0% |
| SPINE_CRAWLER             |       19 |       22 |   100.0% |
| SPORE_CRAWLER             |       34 |       35 |   100.0% |
| HYDRALISK_DEN             |       10 |       10 |   100.0% |
| INFESTATION_PIT           |        5 |        5 |   100.0% |
| SPIRE                     |        1 |        1 |   100.0% |
| NYDUS_NETWORK             |        4 |        4 |   100.0% |
| NYDUS_CANAL               |        1 |        1 |   100.0% |
| EXTRACTOR                 |      162 |      165 |   100.0% |
| UNKNOWN                   |      506 |      567 |   100.0% |
| **TOTAL**                 |     2938 |     3208 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1063)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        89788 |       139542 |    55.4% |
| vespeneCurrent                      |        52228 |        51978 |     0.5% |
| mineralsCollectionRate              |       201496 |         3586 |    98.2% |
| vespeneCollectionRate               |        61091 |         4968 |    91.9% |
| foodMade                            |        11184 |        11184 |     0.0% |
| foodUsed                            |         9012 |         2287 |    74.6% |
| workersActiveCount                  |         5004 |         1920 |    61.6% |
| mineralsUsedCurrentArmy             |       175225 |        20900 |    88.1% |
| mineralsUsedCurrentEconomy          |       506875 |        15800 |    96.9% |
| mineralsUsedCurrentTechnology       |       148600 |            0 |   100.0% |
| vespeneUsedCurrentArmy              |        57400 |        10875 |    81.1% |
| vespeneUsedCurrentEconomy           |           50 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        37950 |            0 |   100.0% |

---

## Root Cause Triage

### Units — 36.4% at 5-min

| Root cause | Classification | Impact | Examples |
|---|---|---|---|
| Race-specific seeding | Known limitation | ~60% of the gap | EmulatedGame seeds ProtossRaceModel for all players. Zerg (Drone, Zergling, Queen, Overlord) and Terran (Marine, SCV, Reaper) players produce 0 emulated units. |
| Probe over-count | Physics error | Inflates Protoss accuracy | GT=1421 vs Em=2318 at 5-min. EmulatedGame's initial seeded Probes are not removed when harness syncs ground truth. |
| Adept/Phoenix under-count | Missing mechanic | Minor | Adept 35%, Phoenix 37.5%. WarpGate production and Stargate training timing may not match ReplayCommandExtractor output. |

### Upgrades — 0% at all checkpoints

| Root cause | Classification | Impact |
|---|---|---|
| No ResearchIntents applied | Missing mechanic | 100% of the gap. ReplayCommandExtractor may not extract research commands, or EmulatedGame rejects them (building tag mismatch). |

### Economy — high error across most fields

| Root cause | Classification | Impact |
|---|---|---|
| Flat mining rate | Known limitation | mineralsCollectionRate 98% error. EmulatedGame uses SC2Data.mineralIncomePerTick (flat per-worker) vs SC2's saturation curves. |
| No technology spending tracked | Missing mechanic | mineralsUsedCurrentTechnology 100% error. SC2Data lacks upgrade costs, so EconomyTracker records 0. |
| Race model mismatch | Known limitation | foodUsed 64% error, workersActiveCount 51% error. Non-Protoss players have no emulated units, deflating food and worker counts. |
| Economy spending under-count | Missing mechanic | mineralsUsedCurrentEconomy 96% error. Only building costs from handleBuild are tracked; harness-injected buildings (majority) bypass EconomyTracker. |
