# Standalone EmulatedGame Accuracy Baseline — 2026-11-08

**Dataset:** Oracle (118 replays, v4.9.3)
**Processed:** 118 replays, 236 player runs
**Method:** Tracker-event-driven inputs, ALL economy GT syncs disabled
**Buildings:** Tracker-driven (BuildIntent, MorphIntent, BuildingBornIntent)

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
| mineralsCurrent | 20630 | 26675 | 10579 | 51.3% |
| vespeneCurrent | 232 | 684 | 452 | 194.8% |
| mineralsCollectionRate | 150658 | 162067 | 15357 | 10.2% |
| vespeneCollectionRate | 1241 | 2185 | 1416 | 114.1% |
| foodMade | 4579 | 4627 | 48 | 1.0% |
| foodUsed | 3338 | 3473 | 137 | 4.1% |
| workersActiveCount | 3162 | 3283 | 121 | 3.8% |
| mineralsUsedCurrentArmy | 0 | 0 | 0 | 0.0% |
| mineralsUsedCurrentEconomy | 266325 | 269450 | 4325 | 1.6% |
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
| COMMAND_CENTER | 41 | 41 | 100.0% |
| ORBITAL_COMMAND | 53 | 53 | 100.0% |
| SUPPLY_DEPOT | 153 | 153 | 100.0% |
| BARRACKS | 135 | 135 | 100.0% |
| ENGINEERING_BAY | 9 | 9 | 100.0% |
| MISSILE_TURRET | 1 | 1 | 100.0% |
| BUNKER | 6 | 6 | 100.0% |
| FACTORY | 37 | 37 | 100.0% |
| STARPORT | 2 | 2 | 100.0% |
| REFINERY | 121 | 121 | 100.0% |
| HATCHERY | 119 | 119 | 100.0% |
| SPAWNING_POOL | 62 | 62 | 100.0% |
| EVOLUTION_CHAMBER | 1 | 1 | 100.0% |
| ROACH_WARREN | 5 | 5 | 100.0% |
| BANELING_NEST | 1 | 1 | 100.0% |
| SPINE_CRAWLER | 2 | 2 | 100.0% |
| EXTRACTOR | 68 | 68 | 100.0% |
| CREEP_TUMOR | 4 | 4 | 100.0% |
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
| mineralsCurrent | 46751 | 67520 | 29591 | 63.3% |
| vespeneCurrent | 24254 | 29106 | 9506 | 39.2% |
| mineralsCollectionRate | 171704 | 168176 | 24266 | 14.1% |
| vespeneCollectionRate | 39512 | 38844 | 11894 | 30.1% |
| foodMade | 7024 | 7073 | 117 | 1.7% |
| foodUsed | 5153 | 5259 | 128 | 2.5% |
| workersActiveCount | 4000 | 4065 | 67 | 1.7% |
| mineralsUsedCurrentArmy | 36875 | 38550 | 2225 | 6.0% |
| mineralsUsedCurrentEconomy | 385100 | 371725 | 17125 | 4.4% |
| mineralsUsedCurrentTechnology | 63850 | 60900 | 5750 | 9.0% |
| vespeneUsedCurrentArmy | 4450 | 4950 | 550 | 12.4% |
| vespeneUsedCurrentEconomy | 0 | 0 | 0 | 0.0% |
| vespeneUsedCurrentTechnology | 5100 | 5725 | 625 | 12.3% |

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
| WARP_PRISM | 3 | 3 | 100.0% |
| WARP_PRISM_PHASING | 1 | 1 | 100.0% |
| ZERGLING | 531 | 529 | 99.6% |
| ROACH | 53 | 53 | 100.0% |
| QUEEN | 145 | 145 | 100.0% |
| RAVAGER | 12 | 12 | 100.0% |
| DRONE | 1724 | 1724 | 100.0% |
| OVERLORD | 305 | 305 | 100.0% |
| BANELING | 17 | 17 | 100.0% |
| MARINE | 372 | 372 | 100.0% |
| MARAUDER | 25 | 25 | 100.0% |
| MEDIVAC | 9 | 9 | 100.0% |
| SIEGE_TANK | 12 | 12 | 100.0% |
| SIEGE_TANK_SIEGED | 21 | 21 | 100.0% |
| VIKING | 4 | 4 | 100.0% |
| RAVEN | 1 | 1 | 100.0% |
| BANSHEE | 2 | 2 | 100.0% |
| CYCLONE | 8 | 8 | 100.0% |
| LIBERATOR | 2 | 2 | 100.0% |
| WIDOW_MINE | 28 | 28 | 100.0% |
| SCV | 1850 | 1850 | 100.0% |
| REAPER | 36 | 36 | 100.0% |
| HELLION | 52 | 52 | 100.0% |
| LIBERATOR_AG | 1 | 1 | 100.0% |
| **TOTAL** | 6807 | 6805 | **100.0%** |

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
| COMMAND_CENTER | 22 | 22 | 100.0% |
| ORBITAL_COMMAND | 90 | 90 | 100.0% |
| PLANETARY_FORTRESS | 1 | 1 | 100.0% |
| SUPPLY_DEPOT | 236 | 236 | 100.0% |
| BARRACKS | 195 | 195 | 100.0% |
| ENGINEERING_BAY | 31 | 31 | 100.0% |
| ARMORY | 2 | 2 | 100.0% |
| MISSILE_TURRET | 27 | 27 | 100.0% |
| BUNKER | 17 | 17 | 100.0% |
| FACTORY | 118 | 118 | 100.0% |
| STARPORT | 62 | 62 | 100.0% |
| FUSION_CORE | 3 | 3 | 100.0% |
| REFINERY | 158 | 158 | 100.0% |
| HATCHERY | 104 | 104 | 100.0% |
| LAIR | 18 | 18 | 100.0% |
| SPAWNING_POOL | 54 | 54 | 100.0% |
| EVOLUTION_CHAMBER | 15 | 15 | 100.0% |
| ROACH_WARREN | 28 | 28 | 100.0% |
| BANELING_NEST | 14 | 14 | 100.0% |
| SPINE_CRAWLER | 20 | 20 | 100.0% |
| SPORE_CRAWLER | 10 | 10 | 100.0% |
| HYDRALISK_DEN | 1 | 1 | 100.0% |
| INFESTATION_PIT | 1 | 1 | 100.0% |
| EXTRACTOR | 114 | 114 | 100.0% |
| CREEP_TUMOR | 110 | 110 | 100.0% |
| ORACLE_STASIS_TRAP | 1 | 1 | 100.0% |
| **TOTAL** | 2148 | 2148 | **100.0%** |

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
| mineralsCurrent | 66699 | 99885 | 51828 | 77.7% |
| vespeneCurrent | 43390 | 57660 | 24922 | 57.4% |
| mineralsCollectionRate | 208770 | 199561 | 29537 | 14.1% |
| vespeneCollectionRate | 50869 | 47860 | 14481 | 28.5% |
| foodMade | 9520 | 9644 | 280 | 2.9% |
| foodUsed | 7488 | 7798 | 454 | 6.1% |
| workersActiveCount | 4907 | 5002 | 159 | 3.2% |
| mineralsUsedCurrentArmy | 108250 | 120225 | 16375 | 15.1% |
| mineralsUsedCurrentEconomy | 478750 | 461725 | 25175 | 5.3% |
| mineralsUsedCurrentTechnology | 119150 | 113925 | 14025 | 11.8% |
| vespeneUsedCurrentArmy | 24475 | 34875 | 11300 | 46.2% |
| vespeneUsedCurrentEconomy | 150 | 0 | 150 | 100.0% |
| vespeneUsedCurrentTechnology | 25075 | 24850 | 2675 | 10.7% |

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
| WARP_PRISM | 7 | 7 | 100.0% |
| WARP_PRISM_PHASING | 3 | 3 | 100.0% |
| ZERGLING | 487 | 485 | 99.6% |
| ROACH | 268 | 268 | 100.0% |
| HYDRALISK | 9 | 9 | 100.0% |
| MUTALISK | 11 | 11 | 100.0% |
| SWARM_HOST | 6 | 6 | 100.0% |
| QUEEN | 153 | 153 | 100.0% |
| RAVAGER | 19 | 19 | 100.0% |
| DRONE | 1848 | 1848 | 100.0% |
| OVERLORD | 387 | 387 | 100.0% |
| OVERSEER | 16 | 16 | 100.0% |
| BANELING | 31 | 31 | 100.0% |
| CHANGELING | 1 | 1 | 100.0% |
| MARINE | 605 | 605 | 100.0% |
| MARAUDER | 52 | 52 | 100.0% |
| MEDIVAC | 45 | 45 | 100.0% |
| SIEGE_TANK | 35 | 35 | 100.0% |
| SIEGE_TANK_SIEGED | 50 | 50 | 100.0% |
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
| **TOTAL** | 7558 | 7556 | **100.0%** |

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
| COMMAND_CENTER | 18 | 18 | 100.0% |
| ORBITAL_COMMAND | 97 | 97 | 100.0% |
| SUPPLY_DEPOT | 326 | 326 | 100.0% |
| BARRACKS | 232 | 232 | 100.0% |
| ENGINEERING_BAY | 51 | 51 | 100.0% |
| ARMORY | 10 | 10 | 100.0% |
| MISSILE_TURRET | 52 | 52 | 100.0% |
| BUNKER | 21 | 21 | 100.0% |
| SENSOR_TOWER | 3 | 3 | 100.0% |
| GHOST_ACADEMY | 2 | 2 | 100.0% |
| FACTORY | 140 | 140 | 100.0% |
| STARPORT | 92 | 92 | 100.0% |
| FUSION_CORE | 7 | 7 | 100.0% |
| REFINERY | 181 | 181 | 100.0% |
| HATCHERY | 73 | 73 | 100.0% |
| LAIR | 41 | 41 | 100.0% |
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
| CREEP_TUMOR | 267 | 267 | 100.0% |
| **TOTAL** | 2699 | 2699 | **100.0%** |

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
| mineralsCurrent | 89788 | 125655 | 68509 | 76.3% |
| vespeneCurrent | 52228 | 72547 | 37669 | 72.1% |
| mineralsCollectionRate | 201496 | 187705 | 28035 | 13.9% |
| vespeneCollectionRate | 61091 | 52475 | 15882 | 26.0% |
| foodMade | 11184 | 11089 | 345 | 3.1% |
| foodUsed | 9017 | 9195 | 335 | 3.7% |
| workersActiveCount | 5004 | 5099 | 127 | 2.5% |
| mineralsUsedCurrentArmy | 175225 | 192600 | 24575 | 14.0% |
| mineralsUsedCurrentEconomy | 506875 | 483200 | 29475 | 5.8% |
| mineralsUsedCurrentTechnology | 148600 | 134575 | 23375 | 15.7% |
| vespeneUsedCurrentArmy | 57400 | 75675 | 20325 | 35.4% |
| vespeneUsedCurrentEconomy | 50 | 0 | 50 | 100.0% |
| vespeneUsedCurrentTechnology | 37950 | 34700 | 5850 | 15.4% |

