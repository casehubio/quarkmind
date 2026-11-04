# Standalone EmulatedGame Accuracy Baseline — 2026-11-04

**Dataset:** Oracle (118 replays, v4.9.3)
**Processed:** 118 replays, 236 player runs
**Method:** Tracker-event-driven inputs, ALL economy GT syncs disabled
**Buildings:** Injected from GT (structural — no build intents exist)

**Disabled GT syncs:** syncMineralWorkersFromGt, setMineralsForHarness, setVespeneForHarness, syncGasRateFromGt, syncMineralRateFromGt, syncVespeneRateFromGt, setSupplyCapForHarness, setSupplyUsedForHarness, setWorkerCountForHarness, syncSpendingFromGt

---

## 1-minute checkpoint

### Units — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| PROBE | 969 | 969 | 100.0% |
| DRONE | 1142 | 1142 | 100.0% |
| OVERLORD | 134 | 134 | 100.0% |
| SCV | 1156 | 1156 | 100.0% |
| **TOTAL** | 3401 | 3401 | **100.0%** |

### Buildings — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| NEXUS | 61 | 61 | 100.0% |
| PYLON | 63 | 63 | 100.0% |
| ASSIMILATOR | 8 | 8 | 100.0% |
| COMMAND_CENTER | 74 | 74 | 100.0% |
| SUPPLY_DEPOT | 74 | 74 | 100.0% |
| REFINERY | 28 | 28 | 100.0% |
| HATCHERY | 71 | 71 | 100.0% |
| EXTRACTOR | 6 | 6 | 100.0% |
| **TOTAL** | 385 | 385 | **100.0%** |

### Upgrades

Accuracy: 100.0% (0/0)

### Economy — Per-Field Error

| Field | GT (sum) | EM (sum) | Abs Error (sum) | MAPE |
|---|---|---|---|---|
| mineralsCurrent | 20630 | -75780 | 96410 | 467.3% |
| vespeneCurrent | 232 | 368 | 168 | 72.4% |
| mineralsCollectionRate | 150658 | 232310 | 81652 | 54.2% |
| vespeneCollectionRate | 1241 | 2205 | 1072 | 86.4% |
| foodMade | 4579 | 4619 | 40 | 0.9% |
| foodUsed | 3338 | 3476 | 140 | 4.2% |
| workersActiveCount | 3162 | 5758 | 2596 | 82.1% |
| mineralsUsedCurrentArmy | 0 | 14200 | 14200 | 0.0% |
| mineralsUsedCurrentEconomy | 266325 | 173950 | 92375 | 34.7% |
| mineralsUsedCurrentTechnology | 0 | 0 | 0 | 0.0% |
| vespeneUsedCurrentArmy | 0 | 0 | 0 | 0.0% |
| vespeneUsedCurrentEconomy | 0 | 0 | 0 | 0.0% |
| vespeneUsedCurrentTechnology | 0 | 0 | 0 | 0.0% |

## 3-minute checkpoint

### Units — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| PROBE | 1259 | 1259 | 100.0% |
| ZEALOT | 29 | 29 | 100.0% |
| STALKER | 22 | 22 | 100.0% |
| ADEPT | 26 | 26 | 100.0% |
| SENTRY | 2 | 2 | 100.0% |
| ADEPT_PHASE_SHIFT | 5 | 5 | 100.0% |
| ZERGLING | 296 | 296 | 100.0% |
| ROACH | 4 | 4 | 100.0% |
| QUEEN | 89 | 89 | 100.0% |
| DRONE | 1303 | 1303 | 100.0% |
| OVERLORD | 180 | 180 | 100.0% |
| BANELING | 1 | 1 | 100.0% |
| MARINE | 98 | 98 | 100.0% |
| MARAUDER | 2 | 2 | 100.0% |
| WIDOW_MINE | 2 | 2 | 100.0% |
| SCV | 1493 | 1493 | 100.0% |
| REAPER | 47 | 47 | 100.0% |
| HELLION | 5 | 5 | 100.0% |
| **TOTAL** | 4863 | 4863 | **100.0%** |

### Buildings — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| NEXUS | 72 | 72 | 100.0% |
| PYLON | 149 | 149 | 100.0% |
| GATEWAY | 94 | 94 | 100.0% |
| CYBERNETICS_CORE | 48 | 48 | 100.0% |
| ASSIMILATOR | 106 | 106 | 100.0% |
| STARGATE | 2 | 2 | 100.0% |
| FORGE | 9 | 9 | 100.0% |
| TWILIGHT_COUNCIL | 6 | 6 | 100.0% |
| PHOTON_CANNON | 6 | 6 | 100.0% |
| COMMAND_CENTER | 94 | 94 | 100.0% |
| SUPPLY_DEPOT | 153 | 153 | 100.0% |
| BARRACKS | 139 | 139 | 100.0% |
| ENGINEERING_BAY | 9 | 9 | 100.0% |
| MISSILE_TURRET | 1 | 1 | 100.0% |
| BUNKER | 6 | 6 | 100.0% |
| FACTORY | 33 | 33 | 100.0% |
| STARPORT | 2 | 2 | 100.0% |
| REFINERY | 121 | 121 | 100.0% |
| HATCHERY | 119 | 119 | 100.0% |
| SPAWNING_POOL | 62 | 62 | 100.0% |
| EVOLUTION_CHAMBER | 1 | 1 | 100.0% |
| ROACH_WARREN | 5 | 5 | 100.0% |
| BANELING_NEST | 1 | 1 | 100.0% |
| SPINE_CRAWLER | 2 | 2 | 100.0% |
| EXTRACTOR | 68 | 68 | 100.0% |
| UNKNOWN | 4 | 4 | 100.0% |
| **TOTAL** | 1312 | 1312 | **100.0%** |

### Upgrades

Accuracy: 100.0% (4/4)

| Upgrade | GT | Matched | Accuracy |
|---|---|---|---|
| PunisherGrenades | 1 | 1 | 100.0% |
| zerglingmovementspeed | 3 | 3 | 100.0% |

### Economy — Per-Field Error

| Field | GT (sum) | EM (sum) | Abs Error (sum) | MAPE |
|---|---|---|---|---|
| mineralsCurrent | 46751 | 29050 | 66365 | 142.0% |
| vespeneCurrent | 24254 | 13471 | 15747 | 64.9% |
| mineralsCollectionRate | 171704 | 201532 | 35998 | 21.0% |
| vespeneCollectionRate | 39512 | 24027 | 18425 | 46.6% |
| foodMade | 7024 | 7116 | 108 | 1.5% |
| foodUsed | 5153 | 5297 | 166 | 3.2% |
| workersActiveCount | 4000 | 6359 | 2359 | 59.0% |
| mineralsUsedCurrentArmy | 36875 | 82625 | 45750 | 124.1% |
| mineralsUsedCurrentEconomy | 385100 | 223950 | 161150 | 41.8% |
| mineralsUsedCurrentTechnology | 63850 | 6550 | 57300 | 89.7% |
| vespeneUsedCurrentArmy | 4450 | 8175 | 3725 | 83.7% |
| vespeneUsedCurrentEconomy | 0 | 0 | 0 | 0.0% |
| vespeneUsedCurrentTechnology | 5100 | 6550 | 10000 | 196.1% |

## 5-minute checkpoint

### Units — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| PROBE | 1421 | 1421 | 100.0% |
| ZEALOT | 53 | 53 | 100.0% |
| STALKER | 51 | 51 | 100.0% |
| IMMORTAL | 10 | 10 | 100.0% |
| OBSERVER | 12 | 12 | 100.0% |
| VOID_RAY | 2 | 2 | 100.0% |
| ADEPT | 20 | 20 | 100.0% |
| SENTRY | 7 | 7 | 100.0% |
| PHOENIX | 8 | 8 | 100.0% |
| ORACLE | 9 | 9 | 100.0% |
| WARP_PRISM | 4 | 4 | 100.0% |
| ZERGLING | 531 | 531 | 100.0% |
| ROACH | 65 | 65 | 100.0% |
| QUEEN | 145 | 145 | 100.0% |
| DRONE | 1724 | 1724 | 100.0% |
| OVERLORD | 305 | 305 | 100.0% |
| BANELING | 17 | 17 | 100.0% |
| MARINE | 372 | 372 | 100.0% |
| MARAUDER | 25 | 25 | 100.0% |
| MEDIVAC | 9 | 9 | 100.0% |
| SIEGE_TANK | 33 | 33 | 100.0% |
| VIKING | 4 | 4 | 100.0% |
| RAVEN | 1 | 1 | 100.0% |
| BANSHEE | 2 | 2 | 100.0% |
| CYCLONE | 8 | 8 | 100.0% |
| LIBERATOR | 3 | 3 | 100.0% |
| WIDOW_MINE | 28 | 28 | 100.0% |
| SCV | 1850 | 1850 | 100.0% |
| REAPER | 36 | 36 | 100.0% |
| HELLION | 52 | 52 | 100.0% |
| **TOTAL** | 6807 | 6807 | **100.0%** |

### Buildings — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| NEXUS | 80 | 80 | 100.0% |
| PYLON | 209 | 209 | 100.0% |
| GATEWAY | 132 | 132 | 100.0% |
| CYBERNETICS_CORE | 46 | 46 | 100.0% |
| ASSIMILATOR | 120 | 120 | 100.0% |
| ROBOTICS_FACILITY | 27 | 27 | 100.0% |
| STARGATE | 12 | 12 | 100.0% |
| FORGE | 10 | 10 | 100.0% |
| TWILIGHT_COUNCIL | 23 | 23 | 100.0% |
| PHOTON_CANNON | 16 | 16 | 100.0% |
| SHIELD_BATTERY | 16 | 16 | 100.0% |
| DARK_SHRINE | 3 | 3 | 100.0% |
| ROBOTICS_BAY | 2 | 2 | 100.0% |
| COMMAND_CENTER | 113 | 113 | 100.0% |
| SUPPLY_DEPOT | 236 | 236 | 100.0% |
| BARRACKS | 212 | 212 | 100.0% |
| ENGINEERING_BAY | 31 | 31 | 100.0% |
| ARMORY | 2 | 2 | 100.0% |
| MISSILE_TURRET | 27 | 27 | 100.0% |
| BUNKER | 17 | 17 | 100.0% |
| FACTORY | 105 | 105 | 100.0% |
| STARPORT | 58 | 58 | 100.0% |
| FUSION_CORE | 3 | 3 | 100.0% |
| REFINERY | 158 | 158 | 100.0% |
| HATCHERY | 122 | 122 | 100.0% |
| SPAWNING_POOL | 54 | 54 | 100.0% |
| EVOLUTION_CHAMBER | 15 | 15 | 100.0% |
| ROACH_WARREN | 28 | 28 | 100.0% |
| BANELING_NEST | 14 | 14 | 100.0% |
| SPINE_CRAWLER | 20 | 20 | 100.0% |
| SPORE_CRAWLER | 10 | 10 | 100.0% |
| HYDRALISK_DEN | 1 | 1 | 100.0% |
| INFESTATION_PIT | 1 | 1 | 100.0% |
| EXTRACTOR | 114 | 114 | 100.0% |
| UNKNOWN | 218 | 218 | 100.0% |
| **TOTAL** | 2255 | 2255 | **100.0%** |

### Upgrades

Accuracy: 100.0% (103/103)

| Upgrade | GT | Matched | Accuracy |
|---|---|---|---|
| AdeptPiercingAttack | 2 | 2 | 100.0% |
| BlinkTech | 1 | 1 | 100.0% |
| Charge | 2 | 2 | 100.0% |
| HiSecAutoTracking | 1 | 1 | 100.0% |
| HighCapacityBarrels | 1 | 1 | 100.0% |
| PunisherGrenades | 3 | 3 | 100.0% |
| ShieldWall | 3 | 3 | 100.0% |
| Stimpack | 5 | 5 | 100.0% |
| TerranInfantryWeaponsLevel1 | 2 | 2 | 100.0% |
| WarpGateResearch | 39 | 39 | 100.0% |
| ZergMeleeWeaponsLevel1 | 1 | 1 | 100.0% |
| overlordspeed | 3 | 3 | 100.0% |
| zerglingmovementspeed | 40 | 40 | 100.0% |

### Economy — Per-Field Error

| Field | GT (sum) | EM (sum) | Abs Error (sum) | MAPE |
|---|---|---|---|---|
| mineralsCurrent | 66699 | 33035 | 113840 | 170.7% |
| vespeneCurrent | 43390 | 7328 | 42022 | 96.8% |
| mineralsCollectionRate | 208770 | 201006 | 46302 | 22.2% |
| vespeneCollectionRate | 50869 | 26945 | 25414 | 50.0% |
| foodMade | 9520 | 9826 | 402 | 4.2% |
| foodUsed | 7488 | 7864 | 522 | 7.0% |
| workersActiveCount | 4907 | 7100 | 2193 | 44.7% |
| mineralsUsedCurrentArmy | 108250 | 202450 | 94200 | 87.0% |
| mineralsUsedCurrentEconomy | 478750 | 287050 | 191750 | 40.1% |
| mineralsUsedCurrentTechnology | 119150 | 16825 | 102325 | 85.9% |
| vespeneUsedCurrentArmy | 24475 | 39675 | 17000 | 69.5% |
| vespeneUsedCurrentEconomy | 150 | 0 | 150 | 100.0% |
| vespeneUsedCurrentTechnology | 25075 | 16825 | 20650 | 82.4% |

## 7-minute checkpoint

### Units — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| PROBE | 1319 | 1319 | 100.0% |
| ZEALOT | 17 | 17 | 100.0% |
| STALKER | 27 | 27 | 100.0% |
| IMMORTAL | 24 | 24 | 100.0% |
| COLOSSUS | 5 | 5 | 100.0% |
| DARK_TEMPLAR | 2 | 2 | 100.0% |
| OBSERVER | 16 | 16 | 100.0% |
| VOID_RAY | 11 | 11 | 100.0% |
| ADEPT | 11 | 11 | 100.0% |
| SENTRY | 6 | 6 | 100.0% |
| PHOENIX | 5 | 5 | 100.0% |
| ORACLE | 10 | 10 | 100.0% |
| WARP_PRISM | 10 | 10 | 100.0% |
| ZERGLING | 487 | 487 | 100.0% |
| ROACH | 287 | 287 | 100.0% |
| HYDRALISK | 9 | 9 | 100.0% |
| MUTALISK | 11 | 11 | 100.0% |
| SWARM_HOST | 6 | 6 | 100.0% |
| QUEEN | 153 | 153 | 100.0% |
| DRONE | 1848 | 1848 | 100.0% |
| OVERLORD | 403 | 403 | 100.0% |
| BANELING | 31 | 31 | 100.0% |
| CHANGELING | 1 | 1 | 100.0% |
| MARINE | 605 | 605 | 100.0% |
| MARAUDER | 52 | 52 | 100.0% |
| MEDIVAC | 45 | 45 | 100.0% |
| SIEGE_TANK | 85 | 85 | 100.0% |
| VIKING | 19 | 19 | 100.0% |
| RAVEN | 3 | 3 | 100.0% |
| BANSHEE | 6 | 6 | 100.0% |
| BATTLECRUISER | 6 | 6 | 100.0% |
| CYCLONE | 11 | 11 | 100.0% |
| LIBERATOR | 9 | 9 | 100.0% |
| WIDOW_MINE | 33 | 33 | 100.0% |
| SCV | 1929 | 1929 | 100.0% |
| REAPER | 17 | 17 | 100.0% |
| HELLION | 39 | 39 | 100.0% |
| **TOTAL** | 7558 | 7558 | **100.0%** |

### Buildings — Per-Type

| Type | GT | Emulated | Accuracy |
|---|---|---|---|
| NEXUS | 68 | 68 | 100.0% |
| PYLON | 239 | 239 | 100.0% |
| GATEWAY | 141 | 141 | 100.0% |
| CYBERNETICS_CORE | 32 | 32 | 100.0% |
| ASSIMILATOR | 114 | 114 | 100.0% |
| ROBOTICS_FACILITY | 24 | 24 | 100.0% |
| STARGATE | 16 | 16 | 100.0% |
| FORGE | 23 | 23 | 100.0% |
| TWILIGHT_COUNCIL | 21 | 21 | 100.0% |
| PHOTON_CANNON | 8 | 8 | 100.0% |
| SHIELD_BATTERY | 16 | 16 | 100.0% |
| DARK_SHRINE | 3 | 3 | 100.0% |
| FLEET_BEACON | 1 | 1 | 100.0% |
| ROBOTICS_BAY | 5 | 5 | 100.0% |
| COMMAND_CENTER | 115 | 115 | 100.0% |
| SUPPLY_DEPOT | 326 | 326 | 100.0% |
| BARRACKS | 245 | 245 | 100.0% |
| ENGINEERING_BAY | 51 | 51 | 100.0% |
| ARMORY | 10 | 10 | 100.0% |
| MISSILE_TURRET | 52 | 52 | 100.0% |
| BUNKER | 21 | 21 | 100.0% |
| SENSOR_TOWER | 3 | 3 | 100.0% |
| GHOST_ACADEMY | 2 | 2 | 100.0% |
| FACTORY | 135 | 135 | 100.0% |
| STARPORT | 84 | 84 | 100.0% |
| FUSION_CORE | 7 | 7 | 100.0% |
| REFINERY | 181 | 181 | 100.0% |
| HATCHERY | 114 | 114 | 100.0% |
| SPAWNING_POOL | 45 | 45 | 100.0% |
| EVOLUTION_CHAMBER | 39 | 39 | 100.0% |
| ROACH_WARREN | 32 | 32 | 100.0% |
| BANELING_NEST | 23 | 23 | 100.0% |
| SPINE_CRAWLER | 19 | 19 | 100.0% |
| SPORE_CRAWLER | 34 | 34 | 100.0% |
| HYDRALISK_DEN | 10 | 10 | 100.0% |
| INFESTATION_PIT | 5 | 5 | 100.0% |
| SPIRE | 1 | 1 | 100.0% |
| NYDUS_NETWORK | 4 | 4 | 100.0% |
| NYDUS_CANAL | 1 | 1 | 100.0% |
| EXTRACTOR | 162 | 162 | 100.0% |
| UNKNOWN | 506 | 506 | 100.0% |
| **TOTAL** | 2938 | 2938 | **100.0%** |

### Upgrades

Accuracy: 100.0% (185/185)

| Upgrade | GT | Matched | Accuracy |
|---|---|---|---|
| AdeptPiercingAttack | 3 | 3 | 100.0% |
| BansheeCloak | 4 | 4 | 100.0% |
| BattlecruiserEnableSpecializations | 2 | 2 | 100.0% |
| BlinkTech | 8 | 8 | 100.0% |
| Burrow | 1 | 1 | 100.0% |
| CentrificalHooks | 1 | 1 | 100.0% |
| Charge | 3 | 3 | 100.0% |
| CycloneLockOnDamageUpgrade | 2 | 2 | 100.0% |
| DarkTemplarBlinkUpgrade | 1 | 1 | 100.0% |
| DrillClaws | 1 | 1 | 100.0% |
| EvolveGroovedSpines | 1 | 1 | 100.0% |
| ExtendedThermalLance | 2 | 2 | 100.0% |
| GlialReconstitution | 8 | 8 | 100.0% |
| HiSecAutoTracking | 1 | 1 | 100.0% |
| HighCapacityBarrels | 2 | 2 | 100.0% |
| ProtossGroundWeaponsLevel1 | 4 | 4 | 100.0% |
| PunisherGrenades | 6 | 6 | 100.0% |
| ShieldWall | 12 | 12 | 100.0% |
| Stimpack | 23 | 23 | 100.0% |
| TerranInfantryArmorsLevel1 | 5 | 5 | 100.0% |
| TerranInfantryWeaponsLevel1 | 12 | 12 | 100.0% |
| TunnelingClaws | 1 | 1 | 100.0% |
| WarpGateResearch | 28 | 28 | 100.0% |
| ZergGroundArmorsLevel1 | 3 | 3 | 100.0% |
| ZergMeleeWeaponsLevel1 | 2 | 2 | 100.0% |
| ZergMissileWeaponsLevel1 | 6 | 6 | 100.0% |
| overlordspeed | 8 | 8 | 100.0% |
| zerglingmovementspeed | 35 | 35 | 100.0% |

### Economy — Per-Field Error

| Field | GT (sum) | EM (sum) | Abs Error (sum) | MAPE |
|---|---|---|---|---|
| mineralsCurrent | 89788 | -13310 | 160212 | 178.4% |
| vespeneCurrent | 52228 | -21188 | 76942 | 147.3% |
| mineralsCollectionRate | 201496 | 170714 | 59376 | 29.5% |
| vespeneCollectionRate | 61091 | 27682 | 33959 | 55.6% |
| foodMade | 11184 | 11489 | 497 | 4.4% |
| foodUsed | 9017 | 9281 | 462 | 5.1% |
| workersActiveCount | 5004 | 6849 | 1845 | 36.9% |
| mineralsUsedCurrentArmy | 175225 | 315135 | 139910 | 79.8% |
| mineralsUsedCurrentEconomy | 506875 | 304600 | 203875 | 40.2% |
| mineralsUsedCurrentTechnology | 148600 | 28575 | 120025 | 80.8% |
| vespeneUsedCurrentArmy | 57400 | 83625 | 28425 | 49.5% |
| vespeneUsedCurrentEconomy | 50 | 0 | 50 | 100.0% |
| vespeneUsedCurrentTechnology | 37950 | 28575 | 26075 | 68.7% |

