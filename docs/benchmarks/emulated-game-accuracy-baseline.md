# EmulatedGame Accuracy Baseline — 2026-10-12

**Context:** Phase 2.5 (#379, child of #366)
**Dataset:** Oracle (118 replays, v4.9.3)
**Processed:** 118 replays, 236 player runs

---

## 1-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |      969 |      904 |    93.3% |
| DRONE                     |     1142 |      996 |    87.2% |
| OVERLORD                  |      134 |      131 |    97.8% |
| SCV                       |     1156 |     1083 |    93.7% |
| **TOTAL**                 |     3401 |     3114 |    91.6% |

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
| mineralsCurrent                     |        20630 |        31951 |    54.9% |
| vespeneCurrent                      |          232 |          232 |     0.0% |
| mineralsCollectionRate              |       150658 |        13220 |    91.2% |
| vespeneCollectionRate               |         1241 |          463 |    62.7% |
| foodMade                            |         4579 |         4579 |     0.0% |
| foodUsed                            |         3338 |         3139 |     6.0% |
| workersActiveCount                  |         3162 |         2983 |     5.7% |
| mineralsUsedCurrentArmy             |            0 |         6500 |     0.0% |
| mineralsUsedCurrentEconomy          |       266325 |        81600 |    69.4% |
| mineralsUsedCurrentTechnology       |            0 |          450 |     0.0% |
| vespeneUsedCurrentArmy              |            0 |            0 |     0.0% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |            0 |            0 |     0.0% |

## 3-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1259 |     1115 |    88.6% |
| ZEALOT                    |       29 |       15 |    51.7% |
| STALKER                   |       22 |       12 |    54.5% |
| ADEPT                     |       26 |        0 |     0.0% |
| SENTRY                    |        2 |       17 |   100.0% |
| ADEPT_PHASE_SHIFT         |        5 |        0 |     0.0% |
| ZERGLING                  |      296 |      228 |    77.0% |
| ROACH                     |        4 |        2 |    50.0% |
| QUEEN                     |       89 |        3 |     3.4% |
| DRONE                     |     1303 |     1190 |    91.3% |
| OVERLORD                  |      180 |      181 |   100.0% |
| BANELING                  |        1 |        0 |     0.0% |
| MARINE                    |       98 |       67 |    68.4% |
| MARAUDER                  |        2 |        1 |    50.0% |
| WIDOW_MINE                |        2 |        0 |     0.0% |
| SCV                       |     1493 |     1349 |    90.4% |
| REAPER                    |       47 |       31 |    66.0% |
| HELLION                   |        5 |        2 |    40.0% |
| MULE                      |       47 |        0 |     0.0% |
| **TOTAL**                 |     4910 |     4213 |    85.8% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       72 |      129 |   100.0% |
| PYLON                     |      149 |      292 |   100.0% |
| GATEWAY                   |       94 |      161 |   100.0% |
| CYBERNETICS_CORE          |       48 |       88 |   100.0% |
| ASSIMILATOR               |      106 |      194 |   100.0% |
| STARGATE                  |        2 |        3 |   100.0% |
| FORGE                     |        9 |       15 |   100.0% |
| TWILIGHT_COUNCIL          |        6 |        8 |   100.0% |
| PHOTON_CANNON             |        6 |       14 |   100.0% |
| COMMAND_CENTER            |       94 |      174 |   100.0% |
| SUPPLY_DEPOT              |      153 |      287 |   100.0% |
| BARRACKS                  |      139 |      241 |   100.0% |
| ENGINEERING_BAY           |        9 |       17 |   100.0% |
| MISSILE_TURRET            |        1 |        2 |   100.0% |
| BUNKER                    |        6 |       12 |   100.0% |
| FACTORY                   |       33 |       59 |   100.0% |
| STARPORT                  |        2 |        3 |   100.0% |
| REFINERY                  |      121 |      222 |   100.0% |
| HATCHERY                  |      119 |      217 |   100.0% |
| SPAWNING_POOL             |       62 |       97 |   100.0% |
| EVOLUTION_CHAMBER         |        1 |        2 |   100.0% |
| ROACH_WARREN              |        5 |       10 |   100.0% |
| BANELING_NEST             |        1 |        2 |   100.0% |
| SPINE_CRAWLER             |        2 |        7 |   100.0% |
| EXTRACTOR                 |       68 |      147 |   100.0% |
| CREEP_TUMOR               |        0 |        8 |     0.0% |
| UNKNOWN                   |        4 |        4 |   100.0% |
| **TOTAL**                 |     1312 |     2415 |   100.0% |

### Upgrades

Accuracy: 0.0% (0/1225)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        46751 |        54943 |    17.5% |
| vespeneCurrent                      |        24254 |        24154 |     0.4% |
| mineralsCollectionRate              |       171704 |        11017 |    93.6% |
| vespeneCollectionRate               |        39512 |         6019 |    84.8% |
| foodMade                            |         7024 |         7024 |     0.0% |
| foodUsed                            |         5150 |         4233 |    17.8% |
| workersActiveCount                  |         4000 |         3654 |     8.7% |
| mineralsUsedCurrentArmy             |        36875 |        37325 |     1.2% |
| mineralsUsedCurrentEconomy          |       385100 |       199225 |    48.3% |
| mineralsUsedCurrentTechnology       |        63850 |        12900 |    79.8% |
| vespeneUsedCurrentArmy              |         4450 |         6200 |    39.3% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |         5100 |         2900 |    43.1% |

## 5-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1421 |     1156 |    81.4% |
| ZEALOT                    |       53 |       31 |    58.5% |
| STALKER                   |       51 |       57 |   100.0% |
| IMMORTAL                  |       10 |        5 |    50.0% |
| OBSERVER                  |       12 |       10 |    83.3% |
| VOID_RAY                  |        2 |        1 |    50.0% |
| ADEPT                     |       20 |        8 |    40.0% |
| SENTRY                    |        7 |       28 |   100.0% |
| PHOENIX                   |        8 |        1 |    12.5% |
| ORACLE                    |        9 |        7 |    77.8% |
| WARP_PRISM                |        4 |        1 |    25.0% |
| ZERGLING                  |      531 |      398 |    75.0% |
| ROACH                     |       65 |       23 |    35.4% |
| QUEEN                     |      145 |       25 |    17.2% |
| DRONE                     |     1724 |     1311 |    76.0% |
| OVERLORD                  |      305 |      281 |    92.1% |
| BANELING                  |       17 |        0 |     0.0% |
| MARINE                    |      372 |      225 |    60.5% |
| MARAUDER                  |       25 |       12 |    48.0% |
| MEDIVAC                   |        9 |        6 |    66.7% |
| SIEGE_TANK                |       33 |       11 |    33.3% |
| VIKING                    |        4 |        1 |    25.0% |
| RAVEN                     |        1 |        1 |   100.0% |
| BANSHEE                   |        2 |        2 |   100.0% |
| BATTLECRUISER             |        0 |        1 |     0.0% |
| CYCLONE                   |        8 |        5 |    62.5% |
| LIBERATOR                 |        3 |        2 |    66.7% |
| WIDOW_MINE                |       28 |       25 |    89.3% |
| SCV                       |     1850 |     1575 |    85.1% |
| REAPER                    |       36 |       42 |   100.0% |
| HELLION                   |       52 |       30 |    57.7% |
| MULE                      |       69 |        0 |     0.0% |
| **TOTAL**                 |     6876 |     5281 |    76.8% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       80 |      131 |   100.0% |
| PYLON                     |      209 |      415 |   100.0% |
| GATEWAY                   |      132 |      223 |   100.0% |
| CYBERNETICS_CORE          |       46 |       88 |   100.0% |
| ASSIMILATOR               |      120 |      218 |   100.0% |
| ROBOTICS_FACILITY         |       27 |       40 |   100.0% |
| STARGATE                  |       12 |       20 |   100.0% |
| FORGE                     |       10 |       16 |   100.0% |
| TWILIGHT_COUNCIL          |       23 |       37 |   100.0% |
| PHOTON_CANNON             |       16 |       25 |   100.0% |
| SHIELD_BATTERY            |       16 |       37 |   100.0% |
| DARK_SHRINE               |        3 |        5 |   100.0% |
| ROBOTICS_BAY              |        2 |        4 |   100.0% |
| COMMAND_CENTER            |      113 |      196 |   100.0% |
| SUPPLY_DEPOT              |      236 |      427 |   100.0% |
| BARRACKS                  |      212 |      350 |   100.0% |
| ENGINEERING_BAY           |       31 |       56 |   100.0% |
| ARMORY                    |        2 |        3 |   100.0% |
| MISSILE_TURRET            |       27 |       47 |   100.0% |
| BUNKER                    |       17 |       42 |   100.0% |
| FACTORY                   |      105 |      172 |   100.0% |
| STARPORT                  |       58 |       86 |   100.0% |
| FUSION_CORE               |        3 |        5 |   100.0% |
| REFINERY                  |      158 |      285 |   100.0% |
| HATCHERY                  |      122 |      234 |   100.0% |
| SPAWNING_POOL             |       54 |       85 |   100.0% |
| EVOLUTION_CHAMBER         |       15 |       27 |   100.0% |
| ROACH_WARREN              |       28 |       57 |   100.0% |
| BANELING_NEST             |       14 |       29 |   100.0% |
| SPINE_CRAWLER             |       20 |       40 |   100.0% |
| SPORE_CRAWLER             |       10 |       17 |   100.0% |
| HYDRALISK_DEN             |        1 |        2 |   100.0% |
| INFESTATION_PIT           |        1 |        2 |   100.0% |
| EXTRACTOR                 |      114 |      223 |   100.0% |
| CREEP_TUMOR               |        0 |      109 |     0.0% |
| UNKNOWN                   |      218 |      229 |   100.0% |
| **TOTAL**                 |     2255 |     3982 |   100.0% |

### Upgrades

Accuracy: 2.8% (34/1195)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        66699 |        77737 |    16.5% |
| vespeneCurrent                      |        43390 |        43365 |     0.1% |
| mineralsCollectionRate              |       208770 |        13300 |    93.6% |
| vespeneCollectionRate               |        50869 |         7439 |    85.4% |
| foodMade                            |         9520 |         9520 |     0.0% |
| foodUsed                            |         7483 |         5477 |    26.8% |
| workersActiveCount                  |         4907 |         4042 |    17.6% |
| mineralsUsedCurrentArmy             |       108250 |        96500 |    10.9% |
| mineralsUsedCurrentEconomy          |       478750 |       278450 |    41.8% |
| mineralsUsedCurrentTechnology       |       119150 |        29100 |    75.6% |
| vespeneUsedCurrentArmy              |        24475 |        20700 |    15.4% |
| vespeneUsedCurrentEconomy           |          150 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        25075 |         9925 |    60.4% |

## 7-minute checkpoint

### Units — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| PROBE                     |     1319 |      937 |    71.0% |
| ZEALOT                    |       17 |       24 |   100.0% |
| STALKER                   |       27 |       81 |   100.0% |
| IMMORTAL                  |       24 |       16 |    66.7% |
| COLOSSUS                  |        5 |        2 |    40.0% |
| DARK_TEMPLAR              |        2 |        3 |   100.0% |
| OBSERVER                  |       16 |       18 |   100.0% |
| VOID_RAY                  |       11 |        4 |    36.4% |
| ADEPT                     |       11 |       17 |   100.0% |
| SENTRY                    |        6 |       37 |   100.0% |
| PHOENIX                   |        5 |        1 |    20.0% |
| ORACLE                    |       10 |        7 |    70.0% |
| WARP_PRISM                |       10 |        7 |    70.0% |
| ZERGLING                  |      487 |      436 |    89.5% |
| ROACH                     |      287 |      106 |    36.9% |
| HYDRALISK                 |        9 |        3 |    33.3% |
| MUTALISK                  |       11 |        2 |    18.2% |
| SWARM_HOST                |        6 |        2 |    33.3% |
| QUEEN                     |      153 |       49 |    32.0% |
| DRONE                     |     1848 |     1298 |    70.2% |
| OVERLORD                  |      403 |      333 |    82.6% |
| BANELING                  |       31 |        0 |     0.0% |
| CHANGELING                |        1 |        0 |     0.0% |
| MARINE                    |      605 |      338 |    55.9% |
| MARAUDER                  |       52 |       33 |    63.5% |
| MEDIVAC                   |       45 |       26 |    57.8% |
| SIEGE_TANK                |       85 |       47 |    55.3% |
| VIKING                    |       19 |        6 |    31.6% |
| RAVEN                     |        3 |        2 |    66.7% |
| BANSHEE                   |        6 |        9 |   100.0% |
| BATTLECRUISER             |        6 |        5 |    83.3% |
| CYCLONE                   |       11 |       10 |    90.9% |
| LIBERATOR                 |        9 |        6 |    66.7% |
| WIDOW_MINE                |       33 |       36 |   100.0% |
| SCV                       |     1929 |     1552 |    80.5% |
| REAPER                    |       17 |       34 |   100.0% |
| HELLION                   |       39 |       33 |    84.6% |
| MULE                      |       38 |        0 |     0.0% |
| **TOTAL**                 |     7596 |     5520 |    72.7% |

### Buildings — Per-Type

| Type                      |       GT | Emulated | Accuracy |
|---------------------------|----------|----------|----------|
| NEXUS                     |       68 |      106 |   100.0% |
| PYLON                     |      239 |      475 |   100.0% |
| GATEWAY                   |      141 |      241 |   100.0% |
| CYBERNETICS_CORE          |       32 |       62 |   100.0% |
| ASSIMILATOR               |      114 |      204 |   100.0% |
| ROBOTICS_FACILITY         |       24 |       38 |   100.0% |
| STARGATE                  |       16 |       27 |   100.0% |
| FORGE                     |       23 |       35 |   100.0% |
| TWILIGHT_COUNCIL          |       21 |       35 |   100.0% |
| PHOTON_CANNON             |        8 |       18 |   100.0% |
| SHIELD_BATTERY            |       16 |       41 |   100.0% |
| DARK_SHRINE               |        3 |        5 |   100.0% |
| FLEET_BEACON              |        1 |        1 |   100.0% |
| ROBOTICS_BAY              |        5 |       10 |   100.0% |
| COMMAND_CENTER            |      115 |      195 |   100.0% |
| SUPPLY_DEPOT              |      326 |      588 |   100.0% |
| BARRACKS                  |      245 |      400 |   100.0% |
| ENGINEERING_BAY           |       51 |       84 |   100.0% |
| ARMORY                    |       10 |       17 |   100.0% |
| MISSILE_TURRET            |       52 |       92 |   100.0% |
| BUNKER                    |       21 |       58 |   100.0% |
| SENSOR_TOWER              |        3 |        5 |   100.0% |
| GHOST_ACADEMY             |        2 |        4 |   100.0% |
| FACTORY                   |      135 |      223 |   100.0% |
| STARPORT                  |       84 |      132 |   100.0% |
| FUSION_CORE               |        7 |       13 |   100.0% |
| REFINERY                  |      181 |      326 |   100.0% |
| HATCHERY                  |      114 |      224 |   100.0% |
| SPAWNING_POOL             |       45 |       71 |   100.0% |
| EVOLUTION_CHAMBER         |       39 |       70 |   100.0% |
| ROACH_WARREN              |       32 |       66 |   100.0% |
| BANELING_NEST             |       23 |       47 |   100.0% |
| SPINE_CRAWLER             |       19 |       43 |   100.0% |
| SPORE_CRAWLER             |       34 |       59 |   100.0% |
| HYDRALISK_DEN             |       10 |       19 |   100.0% |
| INFESTATION_PIT           |        5 |       10 |   100.0% |
| SPIRE                     |        1 |        2 |   100.0% |
| NYDUS_NETWORK             |        4 |        8 |   100.0% |
| NYDUS_CANAL               |        1 |        3 |   100.0% |
| EXTRACTOR                 |      162 |      302 |   100.0% |
| CREEP_TUMOR               |        0 |      233 |     0.0% |
| UNKNOWN                   |      506 |      567 |   100.0% |
| **TOTAL**                 |     2938 |     5159 |   100.0% |

### Upgrades

Accuracy: 7.5% (80/1063)

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        89788 |        96961 |     8.0% |
| vespeneCurrent                      |        52228 |        51728 |     1.0% |
| mineralsCollectionRate              |       201496 |        12356 |    93.9% |
| vespeneCollectionRate               |        61091 |         8922 |    85.4% |
| foodMade                            |        11184 |        11184 |     0.0% |
| foodUsed                            |         9012 |         6004 |    33.4% |
| workersActiveCount                  |         5004 |         3787 |    24.3% |
| mineralsUsedCurrentArmy             |       175225 |       142375 |    18.7% |
| mineralsUsedCurrentEconomy          |       506875 |       312900 |    38.3% |
| mineralsUsedCurrentTechnology       |       148600 |        43425 |    70.8% |
| vespeneUsedCurrentArmy              |        57400 |        41450 |    27.8% |
| vespeneUsedCurrentEconomy           |           50 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        37950 |        19725 |    48.0% |

