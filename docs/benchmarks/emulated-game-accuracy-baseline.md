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

Accuracy: 100.0% (0/0)

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
| ZERGLING                  |      296 |      225 |    76.0% |
| ROACH                     |        4 |        2 |    50.0% |
| QUEEN                     |       89 |        3 |     3.4% |
| DRONE                     |     1303 |     1190 |    91.3% |
| OVERLORD                  |      180 |      181 |   100.0% |
| BANELING                  |        1 |        1 |   100.0% |
| MARINE                    |       98 |       67 |    68.4% |
| MARAUDER                  |        2 |        1 |    50.0% |
| WIDOW_MINE                |        2 |        0 |     0.0% |
| SCV                       |     1493 |     1349 |    90.4% |
| REAPER                    |       47 |       31 |    66.0% |
| HELLION                   |        5 |        2 |    40.0% |
| MULE                      |       47 |        0 |     0.0% |
| **TOTAL**                 |     4910 |     4211 |    85.8% |

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
| SUPPLY_DEPOT              |      153 |      286 |   100.0% |
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
| **TOTAL**                 |     1312 |     2414 |   100.0% |

### Upgrades

Accuracy: 25.0% (1/4)

| Upgrade                                  |     GT | Matched |
|------------------------------------------|--------|---------|
| PunisherGrenades                         |      1 |       1 |
| zerglingmovementspeed                    |      3 |       0 |

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        46751 |        53693 |    14.8% |
| vespeneCurrent                      |        24254 |        24154 |     0.4% |
| mineralsCollectionRate              |       171704 |        11017 |    93.6% |
| vespeneCollectionRate               |        39512 |         6019 |    84.8% |
| foodMade                            |         7024 |         7024 |     0.0% |
| foodUsed                            |         5150 |         4233 |    17.8% |
| workersActiveCount                  |         4000 |         3654 |     8.7% |
| mineralsUsedCurrentArmy             |        36875 |        37225 |     0.9% |
| mineralsUsedCurrentEconomy          |       385100 |       198825 |    48.4% |
| mineralsUsedCurrentTechnology       |        63850 |        14650 |    77.1% |
| vespeneUsedCurrentArmy              |         4450 |         6225 |    39.9% |
| vespeneUsedCurrentEconomy           |            0 |            0 |     0.0% |
| vespeneUsedCurrentTechnology        |         5100 |         4650 |     8.8% |

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
| ZERGLING                  |      531 |      379 |    71.4% |
| ROACH                     |       65 |       20 |    30.8% |
| QUEEN                     |      145 |       25 |    17.2% |
| RAVAGER                   |        0 |        3 |     0.0% |
| DRONE                     |     1724 |     1311 |    76.0% |
| OVERLORD                  |      305 |      279 |    91.5% |
| OVERSEER                  |        0 |        1 |     0.0% |
| BANELING                  |       17 |       17 |   100.0% |
| MARINE                    |      372 |      225 |    60.5% |
| MARAUDER                  |       25 |       13 |    52.0% |
| MEDIVAC                   |        9 |        5 |    55.6% |
| SIEGE_TANK                |       33 |       11 |    33.3% |
| VIKING                    |        4 |        1 |    25.0% |
| RAVEN                     |        1 |        1 |   100.0% |
| BANSHEE                   |        2 |        2 |   100.0% |
| BATTLECRUISER             |        0 |        1 |     0.0% |
| CYCLONE                   |        8 |        5 |    62.5% |
| LIBERATOR                 |        3 |        2 |    66.7% |
| WIDOW_MINE                |       28 |       25 |    89.3% |
| SCV                       |     1850 |     1574 |    85.1% |
| REAPER                    |       36 |       42 |   100.0% |
| HELLION                   |       52 |       30 |    57.7% |
| MULE                      |       69 |        0 |     0.0% |
| **TOTAL**                 |     6876 |     5277 |    76.7% |

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
| BARRACKS                  |      212 |      349 |   100.0% |
| ENGINEERING_BAY           |       31 |       56 |   100.0% |
| ARMORY                    |        2 |        3 |   100.0% |
| MISSILE_TURRET            |       27 |       47 |   100.0% |
| BUNKER                    |       17 |       42 |   100.0% |
| FACTORY                   |      105 |      172 |   100.0% |
| STARPORT                  |       58 |       85 |   100.0% |
| FUSION_CORE               |        3 |        5 |   100.0% |
| REFINERY                  |      158 |      285 |   100.0% |
| HATCHERY                  |      122 |      233 |   100.0% |
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
| **TOTAL**                 |     2255 |     3979 |   100.0% |

### Upgrades

Accuracy: 58.3% (60/103)

| Upgrade                                  |     GT | Matched |
|------------------------------------------|--------|---------|
| AdeptPiercingAttack                      |      2 |       1 |
| BlinkTech                                |      1 |       0 |
| Charge                                   |      2 |       0 |
| CycloneLockOnDamageUpgrade               |      0 |       0 |
| DarkTemplarBlinkUpgrade                  |      0 |       0 |
| GlialReconstitution                      |      0 |       0 |
| HiSecAutoTracking                        |      1 |       1 |
| HighCapacityBarrels                      |      1 |       0 |
| PunisherGrenades                         |      3 |       2 |
| ShieldWall                               |      3 |       1 |
| Stimpack                                 |      5 |       1 |
| TerranInfantryWeaponsLevel1              |      2 |       0 |
| WarpGateResearch                         |     39 |      31 |
| ZergMeleeWeaponsLevel1                   |      1 |       0 |
| overlordspeed                            |      3 |       3 |
| zerglingmovementspeed                    |     40 |      20 |

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        66699 |        75212 |    12.8% |
| vespeneCurrent                      |        43390 |        43365 |     0.1% |
| mineralsCollectionRate              |       208770 |        13300 |    93.6% |
| vespeneCollectionRate               |        50869 |         7439 |    85.4% |
| foodMade                            |         9520 |         9520 |     0.0% |
| foodUsed                            |         7483 |         5469 |    26.9% |
| workersActiveCount                  |         4907 |         4041 |    17.6% |
| mineralsUsedCurrentArmy             |       108250 |        96150 |    11.2% |
| mineralsUsedCurrentEconomy          |       478750 |       277700 |    42.0% |
| mineralsUsedCurrentTechnology       |       119150 |        32725 |    72.5% |
| vespeneUsedCurrentArmy              |        24475 |        20575 |    15.9% |
| vespeneUsedCurrentEconomy           |          150 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        25075 |        13275 |    47.1% |

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
| ZERGLING                  |      487 |      410 |    84.2% |
| ROACH                     |      287 |       93 |    32.4% |
| HYDRALISK                 |        9 |        3 |    33.3% |
| MUTALISK                  |       11 |        2 |    18.2% |
| SWARM_HOST                |        6 |        2 |    33.3% |
| QUEEN                     |      153 |       49 |    32.0% |
| RAVAGER                   |        0 |       14 |     0.0% |
| DRONE                     |     1848 |     1298 |    70.2% |
| OVERLORD                  |      403 |      317 |    78.7% |
| OVERSEER                  |        0 |       16 |     0.0% |
| BANELING                  |       31 |       24 |    77.4% |
| CHANGELING                |        1 |        0 |     0.0% |
| MARINE                    |      605 |      340 |    56.2% |
| MARAUDER                  |       52 |       30 |    57.7% |
| MEDIVAC                   |       45 |       25 |    55.6% |
| SIEGE_TANK                |       85 |       47 |    55.3% |
| VIKING                    |       19 |        6 |    31.6% |
| RAVEN                     |        3 |        2 |    66.7% |
| BANSHEE                   |        6 |        9 |   100.0% |
| BATTLECRUISER             |        6 |        5 |    83.3% |
| CYCLONE                   |       11 |       10 |    90.9% |
| LIBERATOR                 |        9 |        6 |    66.7% |
| WIDOW_MINE                |       33 |       36 |   100.0% |
| SCV                       |     1929 |     1549 |    80.3% |
| REAPER                    |       17 |       34 |   100.0% |
| HELLION                   |       39 |       33 |    84.6% |
| MULE                      |       38 |        0 |     0.0% |
| **TOTAL**                 |     7596 |     5514 |    72.6% |

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
| BARRACKS                  |      245 |      398 |   100.0% |
| ENGINEERING_BAY           |       51 |       84 |   100.0% |
| ARMORY                    |       10 |       17 |   100.0% |
| MISSILE_TURRET            |       52 |       91 |   100.0% |
| BUNKER                    |       21 |       58 |   100.0% |
| SENSOR_TOWER              |        3 |        6 |   100.0% |
| GHOST_ACADEMY             |        2 |        4 |   100.0% |
| FACTORY                   |      135 |      223 |   100.0% |
| STARPORT                  |       84 |      130 |   100.0% |
| FUSION_CORE               |        7 |       13 |   100.0% |
| REFINERY                  |      181 |      326 |   100.0% |
| HATCHERY                  |      114 |      222 |   100.0% |
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
| **TOTAL**                 |     2938 |     5153 |   100.0% |

### Upgrades

Accuracy: 64.3% (119/185)

| Upgrade                                  |     GT | Matched |
|------------------------------------------|--------|---------|
| AdeptPiercingAttack                      |      3 |       1 |
| BansheeCloak                             |      4 |       3 |
| BattlecruiserEnableSpecializations       |      2 |       0 |
| BlinkTech                                |      8 |       5 |
| Burrow                                   |      1 |       1 |
| CentrificalHooks                         |      1 |       1 |
| Charge                                   |      3 |       2 |
| CycloneLockOnDamageUpgrade               |      2 |       1 |
| DarkTemplarBlinkUpgrade                  |      1 |       0 |
| DrillClaws                               |      1 |       1 |
| EvolveGroovedSpines                      |      1 |       1 |
| ExtendedThermalLance                     |      2 |       2 |
| GlialReconstitution                      |      8 |       8 |
| HiSecAutoTracking                        |      1 |       1 |
| HighCapacityBarrels                      |      2 |       0 |
| ProtossGroundWeaponsLevel1               |      4 |       2 |
| PunisherGrenades                         |      6 |       3 |
| ShieldWall                               |     12 |       7 |
| Stimpack                                 |     23 |      12 |
| TerranInfantryArmorsLevel1               |      5 |       1 |
| TerranInfantryWeaponsLevel1              |     12 |       7 |
| TunnelingClaws                           |      1 |       0 |
| WarpGateResearch                         |     28 |      23 |
| ZergGroundArmorsLevel1                   |      3 |       2 |
| ZergMeleeWeaponsLevel1                   |      2 |       1 |
| ZergMissileWeaponsLevel1                 |      6 |       5 |
| overlordspeed                            |      8 |       8 |
| zerglingmovementspeed                    |     35 |      21 |

### Economy — Aggregate Error

| Field                               |     GT (sum) |     Em (sum) |   Error% |
|-------------------------------------|--------------|--------------|----------|
| mineralsCurrent                     |        89788 |        94186 |     4.9% |
| vespeneCurrent                      |        52228 |        51728 |     1.0% |
| mineralsCollectionRate              |       201496 |        12356 |    93.9% |
| vespeneCollectionRate               |        61091 |         8922 |    85.4% |
| foodMade                            |        11184 |        11184 |     0.0% |
| foodUsed                            |         9012 |         5994 |    33.5% |
| workersActiveCount                  |         5004 |         3784 |    24.4% |
| mineralsUsedCurrentArmy             |       175225 |       142025 |    18.9% |
| mineralsUsedCurrentEconomy          |       506875 |       311425 |    38.6% |
| mineralsUsedCurrentTechnology       |       148600 |        48025 |    67.7% |
| vespeneUsedCurrentArmy              |        57400 |        41275 |    28.1% |
| vespeneUsedCurrentEconomy           |           50 |            0 |   100.0% |
| vespeneUsedCurrentTechnology        |        37950 |        24325 |    35.9% |

