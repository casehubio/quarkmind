# EmulatedGame Accuracy Baseline — 2026-10-08

**Context:** Phase 2.5 (#379, child of #366)
**Dataset:** Oracle (118 replays, v4.9.3)
**Processed:** 118 replays, 236 player runs

---

## 1-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |      969 |      905 |    93.4% |
| DRONE                     |     1142 |      996 |    87.2% |
| OVERLORD                  |      134 |      131 |    97.8% |
| EGG                       |        0 |       58 |     0.0% |
| SCV                       |     1156 |     1083 |    93.7% |
| **TOTAL**                 |     3401 |     3173 |    93.3% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       61 |      122 |   100.0% |
| PYLON                     |       63 |      123 |   100.0% |
| ASSIMILATOR               |        8 |       14 |   100.0% |
| COMMAND_CENTER            |       74 |      148 |   100.0% |
| SUPPLY_DEPOT              |       74 |      139 |   100.0% |
| REFINERY                  |       28 |       50 |   100.0% |
| HATCHERY                  |       71 |      142 |   100.0% |
| EXTRACTOR                 |        6 |       19 |   100.0% |
| **TOTAL**                 |      385 |      757 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1342)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        20630 |        32773 |    58.9% |
| vespeneCurrent                      |          232 |          232 |     0.0% |
| mineralsCollectionRate              |       150658 |         5660 |    96.2% |
| vespeneCollectionRate               |         1241 |           24 |    98.1% |
| foodMade                            |         4579 |         4579 |     0.0% |
| foodUsed                            |         3338 |         3148 |     5.7% |
| workersActiveCount                  |         3162 |         2984 |     5.6% |
| mineralsUsedCurrentArmy             |            0 |         6500 |     0.0% |
| mineralsUsedCurrentEconomy          |       266325 |        81750 |    69.3% |
| mineralsUsedCurrentTechnology       |            0 |          450 |     0.0% |
| vespeneUsedCurrentArmy              |            0 |            0 |     0.0% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |            0 |            0 |     0.0% |

## 3-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1259 |     1121 |    89.0% |
| ZEALOT                    |       29 |       16 |    55.2% |
| STALKER                   |       22 |       11 |    50.0% |
| ADEPT                     |       26 |        0 |     0.0% |
| SENTRY                    |        2 |       15 |   100.0% |
| ADEPT_PHASE_SHIFT         |        5 |        0 |     0.0% |
| ZERGLING                  |      296 |      228 |    77.0% |
| ROACH                     |        4 |        2 |    50.0% |
| QUEEN                     |       89 |        3 |     3.4% |
| DRONE                     |     1303 |     1194 |    91.6% |
| OVERLORD                  |      180 |      182 |   100.0% |
| BANELING                  |        1 |        0 |     0.0% |
| EGG                       |        0 |       83 |     0.0% |
| MARINE                    |       98 |       67 |    68.4% |
| MARAUDER                  |        2 |        1 |    50.0% |
| WIDOW_MINE                |        2 |        0 |     0.0% |
| SCV                       |     1493 |     1349 |    90.4% |
| REAPER                    |       47 |       31 |    66.0% |
| HELLION                   |        5 |        3 |    60.0% |
| MULE                      |       47 |        0 |     0.0% |
| **TOTAL**                 |     4910 |     4306 |    87.7% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       72 |      130 |   100.0% |
| PYLON                     |      149 |      297 |   100.0% |
| GATEWAY                   |       94 |      162 |   100.0% |
| CYBERNETICS_CORE          |       48 |       94 |   100.0% |
| ASSIMILATOR               |      106 |      196 |   100.0% |
| STARGATE                  |        2 |        4 |   100.0% |
| FORGE                     |        9 |       15 |   100.0% |
| TWILIGHT_COUNCIL          |        6 |       10 |   100.0% |
| PHOTON_CANNON             |        6 |       14 |   100.0% |
| COMMAND_CENTER            |       94 |      177 |   100.0% |
| SUPPLY_DEPOT              |      153 |      290 |   100.0% |
| BARRACKS                  |      139 |      247 |   100.0% |
| ENGINEERING_BAY           |        9 |       17 |   100.0% |
| MISSILE_TURRET            |        1 |        2 |   100.0% |
| BUNKER                    |        6 |       12 |   100.0% |
| FACTORY                   |       33 |       61 |   100.0% |
| STARPORT                  |        2 |        4 |   100.0% |
| REFINERY                  |      121 |      223 |   100.0% |
| HATCHERY                  |      119 |      217 |   100.0% |
| SPAWNING_POOL             |       62 |       98 |   100.0% |
| EVOLUTION_CHAMBER         |        1 |        2 |   100.0% |
| ROACH_WARREN              |        5 |       10 |   100.0% |
| BANELING_NEST             |        1 |        2 |   100.0% |
| SPINE_CRAWLER             |        2 |        7 |   100.0% |
| EXTRACTOR                 |       68 |      147 |   100.0% |
| CREEP_TUMOR               |        0 |        8 |     0.0% |
| UNKNOWN                   |        4 |        4 |   100.0% |
| **TOTAL**                 |     1312 |     2450 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1225)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        46751 |        76945 |    64.6% |
| vespeneCurrent                      |        24254 |        24029 |     0.9% |
| mineralsCollectionRate              |       171704 |         6590 |    96.2% |
| vespeneCollectionRate               |        39512 |         3645 |    90.8% |
| foodMade                            |         7024 |         7024 |     0.0% |
| foodUsed                            |         5150 |         4263 |    17.2% |
| workersActiveCount                  |         4000 |         3664 |     8.4% |
| mineralsUsedCurrentArmy             |        36875 |        37925 |     2.8% |
| mineralsUsedCurrentEconomy          |       385100 |       213100 |    44.7% |
| mineralsUsedCurrentTechnology       |        63850 |        14975 |    76.5% |
| vespeneUsedCurrentArmy              |         4450 |         6075 |    36.5% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |         5100 |         3500 |    31.4% |

## 5-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1421 |     1163 |    81.8% |
| ZEALOT                    |       53 |       34 |    64.2% |
| STALKER                   |       51 |       66 |   100.0% |
| IMMORTAL                  |       10 |        5 |    50.0% |
| OBSERVER                  |       12 |       10 |    83.3% |
| VOID_RAY                  |        2 |        2 |   100.0% |
| ADEPT                     |       20 |       10 |    50.0% |
| SENTRY                    |        7 |       25 |   100.0% |
| PHOENIX                   |        8 |        2 |    25.0% |
| ORACLE                    |        9 |        7 |    77.8% |
| WARP_PRISM                |        4 |        3 |    75.0% |
| ZERGLING                  |      531 |      406 |    76.5% |
| ROACH                     |       65 |       23 |    35.4% |
| QUEEN                     |      145 |       26 |    17.9% |
| DRONE                     |     1724 |     1319 |    76.5% |
| OVERLORD                  |      305 |      283 |    92.8% |
| BANELING                  |       17 |        0 |     0.0% |
| EGG                       |        0 |       73 |     0.0% |
| MARINE                    |      372 |      229 |    61.6% |
| MARAUDER                  |       25 |       15 |    60.0% |
| MEDIVAC                   |        9 |        6 |    66.7% |
| SIEGE_TANK                |       33 |       21 |    63.6% |
| VIKING                    |        4 |        3 |    75.0% |
| RAVEN                     |        1 |        1 |   100.0% |
| BANSHEE                   |        2 |        1 |    50.0% |
| BATTLECRUISER             |        0 |        2 |     0.0% |
| CYCLONE                   |        8 |        6 |    75.0% |
| LIBERATOR                 |        3 |        3 |   100.0% |
| WIDOW_MINE                |       28 |       21 |    75.0% |
| SCV                       |     1850 |     1571 |    84.9% |
| REAPER                    |       36 |       41 |   100.0% |
| HELLION                   |       52 |       35 |    67.3% |
| MULE                      |       69 |        0 |     0.0% |
| **TOTAL**                 |     6876 |     5412 |    78.7% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       80 |      138 |   100.0% |
| PYLON                     |      209 |      419 |   100.0% |
| GATEWAY                   |      132 |      227 |   100.0% |
| CYBERNETICS_CORE          |       46 |       92 |   100.0% |
| ASSIMILATOR               |      120 |      223 |   100.0% |
| ROBOTICS_FACILITY         |       27 |       50 |   100.0% |
| STARGATE                  |       12 |       23 |   100.0% |
| FORGE                     |       10 |       16 |   100.0% |
| TWILIGHT_COUNCIL          |       23 |       43 |   100.0% |
| PHOTON_CANNON             |       16 |       26 |   100.0% |
| SHIELD_BATTERY            |       16 |       38 |   100.0% |
| DARK_SHRINE               |        3 |        5 |   100.0% |
| ROBOTICS_BAY              |        2 |        4 |   100.0% |
| COMMAND_CENTER            |      113 |      212 |   100.0% |
| SUPPLY_DEPOT              |      236 |      446 |   100.0% |
| BARRACKS                  |      212 |      373 |   100.0% |
| ENGINEERING_BAY           |       31 |       58 |   100.0% |
| ARMORY                    |        2 |        3 |   100.0% |
| MISSILE_TURRET            |       27 |       47 |   100.0% |
| BUNKER                    |       17 |       43 |   100.0% |
| FACTORY                   |      105 |      187 |   100.0% |
| STARPORT                  |       58 |       99 |   100.0% |
| FUSION_CORE               |        3 |        6 |   100.0% |
| REFINERY                  |      158 |      288 |   100.0% |
| HATCHERY                  |      122 |      235 |   100.0% |
| SPAWNING_POOL             |       54 |       86 |   100.0% |
| EVOLUTION_CHAMBER         |       15 |       27 |   100.0% |
| ROACH_WARREN              |       28 |       57 |   100.0% |
| BANELING_NEST             |       14 |       29 |   100.0% |
| SPINE_CRAWLER             |       20 |       42 |   100.0% |
| SPORE_CRAWLER             |       10 |       17 |   100.0% |
| HYDRALISK_DEN             |        1 |        2 |   100.0% |
| INFESTATION_PIT           |        1 |        2 |   100.0% |
| EXTRACTOR                 |      114 |      223 |   100.0% |
| CREEP_TUMOR               |        0 |      109 |     0.0% |
| UNKNOWN                   |      218 |      229 |   100.0% |
| **TOTAL**                 |     2255 |     4124 |   100.0% |

### Upgrades

Accuracy: 3.4% (41/1195)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        66699 |       114548 |    71.7% |
| vespeneCurrent                      |        43390 |        43365 |     0.1% |
| mineralsCollectionRate              |       208770 |         8648 |    95.9% |
| vespeneCollectionRate               |        50869 |         4413 |    91.3% |
| foodMade                            |         9520 |         9520 |     0.0% |
| foodUsed                            |         7483 |         5661 |    24.3% |
| workersActiveCount                  |         4907 |         4053 |    17.4% |
| mineralsUsedCurrentArmy             |       108250 |       105550 |     2.5% |
| mineralsUsedCurrentEconomy          |       478750 |       307225 |    35.8% |
| mineralsUsedCurrentTechnology       |       119150 |        33125 |    72.2% |
| vespeneUsedCurrentArmy              |        24475 |        24650 |     0.7% |
| vespeneUsedCurrentEconomy           |          150 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        25075 |        11775 |    53.0% |

## 7-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1319 |      943 |    71.5% |
| ZEALOT                    |       17 |       26 |   100.0% |
| STALKER                   |       27 |       91 |   100.0% |
| IMMORTAL                  |       24 |       18 |    75.0% |
| COLOSSUS                  |        5 |        1 |    20.0% |
| CARRIER                   |        0 |        2 |     0.0% |
| DARK_TEMPLAR              |        2 |        3 |   100.0% |
| OBSERVER                  |       16 |       18 |   100.0% |
| VOID_RAY                  |       11 |        9 |    81.8% |
| ADEPT                     |       11 |       19 |   100.0% |
| SENTRY                    |        6 |       35 |   100.0% |
| PHOENIX                   |        5 |        4 |    80.0% |
| ORACLE                    |       10 |        8 |    80.0% |
| WARP_PRISM                |       10 |       11 |   100.0% |
| ZERGLING                  |      487 |      444 |    91.2% |
| ROACH                     |      287 |      106 |    36.9% |
| HYDRALISK                 |        9 |        3 |    33.3% |
| MUTALISK                  |       11 |        2 |    18.2% |
| SWARM_HOST                |        6 |        2 |    33.3% |
| QUEEN                     |      153 |       51 |    33.3% |
| DRONE                     |     1848 |     1305 |    70.6% |
| OVERLORD                  |      403 |      337 |    83.6% |
| BANELING                  |       31 |        0 |     0.0% |
| CHANGELING                |        1 |        0 |     0.0% |
| EGG                       |        0 |       68 |     0.0% |
| MARINE                    |      605 |      354 |    58.5% |
| MARAUDER                  |       52 |       37 |    71.2% |
| MEDIVAC                   |       45 |       28 |    62.2% |
| SIEGE_TANK                |       85 |       69 |    81.2% |
| THOR                      |        0 |        1 |     0.0% |
| VIKING                    |       19 |        9 |    47.4% |
| RAVEN                     |        3 |        2 |    66.7% |
| BANSHEE                   |        6 |        7 |   100.0% |
| BATTLECRUISER             |        6 |        5 |    83.3% |
| CYCLONE                   |       11 |       10 |    90.9% |
| LIBERATOR                 |        9 |        8 |    88.9% |
| WIDOW_MINE                |       33 |       32 |    97.0% |
| SCV                       |     1929 |     1558 |    80.8% |
| REAPER                    |       17 |       33 |   100.0% |
| HELLION                   |       39 |       35 |    89.7% |
| MULE                      |       38 |        0 |     0.0% |
| **TOTAL**                 |     7596 |     5694 |    75.0% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       68 |      114 |   100.0% |
| PYLON                     |      239 |      479 |   100.0% |
| GATEWAY                   |      141 |      244 |   100.0% |
| CYBERNETICS_CORE          |       32 |       65 |   100.0% |
| ASSIMILATOR               |      114 |      208 |   100.0% |
| ROBOTICS_FACILITY         |       24 |       46 |   100.0% |
| STARGATE                  |       16 |       31 |   100.0% |
| FORGE                     |       23 |       39 |   100.0% |
| TWILIGHT_COUNCIL          |       21 |       39 |   100.0% |
| PHOTON_CANNON             |        8 |       19 |   100.0% |
| SHIELD_BATTERY            |       16 |       41 |   100.0% |
| DARK_SHRINE               |        3 |        5 |   100.0% |
| FLEET_BEACON              |        1 |        2 |   100.0% |
| ROBOTICS_BAY              |        5 |       10 |   100.0% |
| COMMAND_CENTER            |      115 |      211 |   100.0% |
| SUPPLY_DEPOT              |      326 |      613 |   100.0% |
| BARRACKS                  |      245 |      423 |   100.0% |
| ENGINEERING_BAY           |       51 |       88 |   100.0% |
| ARMORY                    |       10 |       19 |   100.0% |
| MISSILE_TURRET            |       52 |       97 |   100.0% |
| BUNKER                    |       21 |       60 |   100.0% |
| SENSOR_TOWER              |        3 |        6 |   100.0% |
| GHOST_ACADEMY             |        2 |        4 |   100.0% |
| FACTORY                   |      135 |      241 |   100.0% |
| STARPORT                  |       84 |      150 |   100.0% |
| FUSION_CORE               |        7 |       14 |   100.0% |
| REFINERY                  |      181 |      331 |   100.0% |
| HATCHERY                  |      114 |      226 |   100.0% |
| SPAWNING_POOL             |       45 |       72 |   100.0% |
| EVOLUTION_CHAMBER         |       39 |       70 |   100.0% |
| ROACH_WARREN              |       32 |       66 |   100.0% |
| BANELING_NEST             |       23 |       47 |   100.0% |
| SPINE_CRAWLER             |       19 |       45 |   100.0% |
| SPORE_CRAWLER             |       34 |       59 |   100.0% |
| HYDRALISK_DEN             |       10 |       20 |   100.0% |
| INFESTATION_PIT           |        5 |       10 |   100.0% |
| SPIRE                     |        1 |        2 |   100.0% |
| NYDUS_NETWORK             |        4 |        8 |   100.0% |
| NYDUS_CANAL               |        1 |        3 |   100.0% |
| EXTRACTOR                 |      162 |      302 |   100.0% |
| CREEP_TUMOR               |        0 |      233 |     0.0% |
| UNKNOWN                   |      506 |      567 |   100.0% |
| **TOTAL**                 |     2938 |     5329 |   100.0% |

### Upgrades

Accuracy: 9.0% (96/1063)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        89788 |       163060 |    81.6% |
| vespeneCurrent                      |        52228 |        51728 |     1.0% |
| mineralsCollectionRate              |       201496 |        10003 |    95.0% |
| vespeneCollectionRate               |        61091 |         4900 |    92.0% |
| foodMade                            |        11184 |        11184 |     0.0% |
| foodUsed                            |         9012 |         6272 |    30.4% |
| workersActiveCount                  |         5004 |         3806 |    23.9% |
| mineralsUsedCurrentArmy             |       175225 |       154900 |    11.6% |
| mineralsUsedCurrentEconomy          |       506875 |       343450 |    32.2% |
| mineralsUsedCurrentTechnology       |       148600 |        48575 |    67.3% |
| vespeneUsedCurrentArmy              |        57400 |        47550 |    17.2% |
| vespeneUsedCurrentEconomy           |           50 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        37950 |        21875 |    42.4% |


[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.828 s -- in io.quarkmind.sc2.replay.EmulatedGameAccuracyBaselineTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  15.068 s
[INFO] Finished at: 2026-10-08T19:43:21+01:00
[INFO] ------------------------------------------------------------------------
