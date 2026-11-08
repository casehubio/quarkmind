# Oracle Accuracy Baseline — 2026-11-08

**Context:** Phase 2.5 baseline (#367/#373, child of #366)
**Dataset:** Oracle (118 replays, v4.9.3)
**Extractor:** `StrippedReplayFeatureExtractor`
**Processed:** 118 / 118 replays

---

## Aggregate Summary

| Category | Accuracy | Notes |
|----------|----------|-------|
| Units | 89.9% | count match across 118 replays |
| Buildings | 79.1% | count match, building types only |
| Upgrades | 99.7% | gameplay only (excl. cosmetics) |
| Economy | 335.8% MAPE | mean across 13 fields at 5min |

## Tier Breakdown

| Tier | Category | Accuracy | Oracle | Java |
|------|----------|----------|--------|------|
| T1 Commanded | Units | 100.0% | 23571 | 23805 |
| T1 Commanded | Buildings | 79.1% | 6084 | 4811 |
| T2 Auto-spawn | Units | 80.2% | 10012 | 8032 |
| T3 Combat | Units | excluded | 1838 | 0 |
| T4 Structural | Buildings | excluded | 0 | 0 |

## T1 Commanded Units — Top Divergences

| Type | Oracle | Java | Diff |
|------|--------|------|------|
| P2:Zergling | 2778 | 2080 | -698 |
| P1:SCV | 1293 | 1756 | +463 |
| P2:SCV | 1343 | 1742 | +399 |
| P2:Overlord | 487 | 837 | +350 |
| P1:Drone | 1377 | 1133 | -244 |
| P2:Baneling | 454 | 225 | -229 |
| P1:Hydralisk | 323 | 509 | +186 |
| P1:Zergling | 1510 | 1672 | +162 |
| P2:MULE | 350 | 202 | -148 |
| P2:Marine | 1510 | 1657 | +147 |

## T1 Commanded Buildings — Top Divergences

| Type | Oracle | Java | Diff |
|------|--------|------|------|
| P2:CreepTumor | 396 | 175 | -221 |
| P1:CreepTumor | 250 | 141 | -109 |
| P2:SupplyDepot | 400 | 299 | -101 |
| P1:SupplyDepot | 379 | 286 | -93 |
| P2:CreepTumorQueen | 218 | 148 | -70 |
| P2:MissileTurret | 154 | 87 | -67 |
| P1:Pylon | 372 | 312 | -60 |
| P2:Pylon | 258 | 205 | -53 |
| P1:SporeCrawler | 84 | 38 | -46 |
| P2:Extractor | 178 | 132 | -46 |

## T2 Auto-spawn — Divergences

| Type | Oracle | Java | Diff |
|------|--------|------|------|
| P2:Larva | 5575 | 4542 | -1033 |
| P1:Larva | 4115 | 3233 | -882 |
| P1:Interceptor | 297 | 219 | -78 |
| P2:Interceptor | 25 | 38 | +13 |

## Upgrades — Per-Type Accuracy

| UpgradeType | Oracle | Java | Missed | Accuracy |
|-------------|--------|------|--------|----------|
| AdeptPiercingAttack | 7 | 7 | 0 | 100.0% |
| AnabolicSynthesis | 1 | 1 | 0 | 100.0% |
| BansheeCloak | 8 | 8 | 0 | 100.0% |
| BansheeSpeed | 3 | 3 | 0 | 100.0% |
| BattlecruiserEnableSpecializations | 10 | 10 | 0 | 100.0% |
| BlinkTech | 14 | 14 | 0 | 100.0% |
| Burrow | 9 | 10 | 0 | 100.0% |
| CentrificalHooks | 16 | 16 | 0 | 100.0% |
| Charge | 20 | 20 | 0 | 100.0% |
| ChitinousPlating | 2 | 2 | 0 | 100.0% |
| CycloneLockOnDamageUpgrade | 4 | 6 | 0 | 100.0% |
| DarkTemplarBlinkUpgrade | 1 | 1 | 0 | 100.0% |
| DrillClaws | 7 | 8 | 0 | 100.0% |
| EvolveGroovedSpines | 12 | 16 | 0 | 100.0% |
| EvolveMuscularAugments | 11 | 15 | 0 | 100.0% |
| ExtendedThermalLance | 8 | 8 | 0 | 100.0% |
| GhostAlternate [cosmetic] | 2 | 0 | 2 | 0.0% |
| GlialReconstitution | 24 | 24 | 0 | 100.0% |
| HiSecAutoTracking | 6 | 6 | 0 | 100.0% |
| HighCapacityBarrels | 6 | 6 | 0 | 100.0% |
| InfestorEnergyUpgrade | 5 | 6 | 0 | 100.0% |
| LiberatorAGRangeUpgrade | 5 | 5 | 0 | 100.0% |
| MedivacIncreaseSpeedBoost | 1 | 1 | 0 | 100.0% |
| NeuralParasite | 4 | 5 | 0 | 100.0% |
| PhoenixRangeUpgrade | 1 | 1 | 0 | 100.0% |
| ProtossAirArmorsLevel1 | 2 | 2 | 0 | 100.0% |
| ProtossAirArmorsLevel2 | 1 | 1 | 0 | 100.0% |
| ProtossAirWeaponsLevel1 | 6 | 6 | 0 | 100.0% |
| ProtossAirWeaponsLevel2 | 3 | 3 | 0 | 100.0% |
| ProtossGroundArmorsLevel1 | 12 | 12 | 0 | 100.0% |
| ProtossGroundArmorsLevel2 | 8 | 8 | 0 | 100.0% |
| ProtossGroundArmorsLevel3 | 2 | 2 | 0 | 100.0% |
| ProtossGroundWeaponsLevel1 | 18 | 18 | 0 | 100.0% |
| ProtossGroundWeaponsLevel2 | 12 | 12 | 0 | 100.0% |
| ProtossGroundWeaponsLevel3 | 4 | 4 | 0 | 100.0% |
| ProtossShieldsLevel1 | 4 | 4 | 0 | 100.0% |
| ProtossShieldsLevel2 | 1 | 1 | 0 | 100.0% |
| ProtossShieldsLevel3 | 1 | 1 | 0 | 100.0% |
| PsiStormTech | 5 | 5 | 0 | 100.0% |
| PunisherGrenades | 18 | 18 | 0 | 100.0% |
| RavenCorvidReactor | 3 | 3 | 0 | 100.0% |
| RewardDanceColossus [cosmetic] | 130 | 0 | 130 | 0.0% |
| RewardDanceGhost [cosmetic] | 137 | 0 | 137 | 0.0% |
| RewardDanceInfestor [cosmetic] | 126 | 0 | 126 | 0.0% |
| RewardDanceMule [cosmetic] | 168 | 0 | 168 | 0.0% |
| RewardDanceOracle [cosmetic] | 149 | 0 | 149 | 0.0% |
| RewardDanceOverlord [cosmetic] | 156 | 0 | 156 | 0.0% |
| RewardDanceRoach [cosmetic] | 144 | 0 | 144 | 0.0% |
| RewardDanceStalker [cosmetic] | 162 | 0 | 162 | 0.0% |
| RewardDanceViking [cosmetic] | 157 | 0 | 157 | 0.0% |
| ShieldWall | 30 | 31 | 0 | 100.0% |
| SmartServos | 3 | 3 | 0 | 100.0% |
| SprayProtoss [cosmetic] | 315 | 0 | 315 | 0.0% |
| SprayTerran [cosmetic] | 509 | 0 | 509 | 0.0% |
| SprayZerg [cosmetic] | 543 | 0 | 543 | 0.0% |
| Stimpack | 32 | 32 | 0 | 100.0% |
| TerranBuildingArmor | 5 | 5 | 0 | 100.0% |
| TerranInfantryArmorsLevel1 | 25 | 26 | 0 | 100.0% |
| TerranInfantryArmorsLevel2 | 14 | 14 | 0 | 100.0% |
| TerranInfantryArmorsLevel3 | 4 | 4 | 0 | 100.0% |
| TerranInfantryWeaponsLevel1 | 31 | 31 | 0 | 100.0% |
| TerranInfantryWeaponsLevel2 | 15 | 15 | 0 | 100.0% |
| TerranInfantryWeaponsLevel3 | 4 | 4 | 0 | 100.0% |
| TerranShipWeaponsLevel1 | 13 | 14 | 0 | 100.0% |
| TerranShipWeaponsLevel2 | 7 | 7 | 0 | 100.0% |
| TerranShipWeaponsLevel3 | 5 | 5 | 0 | 100.0% |
| TerranVehicleAndShipArmorsLevel1 | 17 | 17 | 0 | 100.0% |
| TerranVehicleAndShipArmorsLevel2 | 9 | 9 | 0 | 100.0% |
| TerranVehicleAndShipArmorsLevel3 | 6 | 6 | 0 | 100.0% |
| TerranVehicleWeaponsLevel1 | 13 | 13 | 0 | 100.0% |
| TerranVehicleWeaponsLevel2 | 7 | 7 | 0 | 100.0% |
| TerranVehicleWeaponsLevel3 | 2 | 2 | 0 | 100.0% |
| TunnelingClaws | 7 | 7 | 0 | 100.0% |
| WarpGateResearch | 48 | 48 | 0 | 100.0% |
| ZergFlyerArmorsLevel1 | 3 | 3 | 0 | 100.0% |
| ZergFlyerArmorsLevel2 | 2 | 2 | 0 | 100.0% |
| ZergFlyerWeaponsLevel1 | 4 | 4 | 0 | 100.0% |
| ZergFlyerWeaponsLevel2 | 2 | 2 | 0 | 100.0% |
| ZergGroundArmorsLevel1 | 19 | 19 | 0 | 100.0% |
| ZergGroundArmorsLevel2 | 11 | 12 | 0 | 100.0% |
| ZergGroundArmorsLevel3 | 3 | 3 | 0 | 100.0% |
| ZergMeleeWeaponsLevel1 | 15 | 15 | 0 | 100.0% |
| ZergMeleeWeaponsLevel2 | 9 | 9 | 0 | 100.0% |
| ZergMeleeWeaponsLevel3 | 2 | 2 | 0 | 100.0% |
| ZergMissileWeaponsLevel1 | 23 | 23 | 0 | 100.0% |
| ZergMissileWeaponsLevel2 | 12 | 12 | 0 | 100.0% |
| ZergMissileWeaponsLevel3 | 2 | 2 | 0 | 100.0% |
| overlordspeed | 18 | 18 | 0 | 100.0% |
| zerglingattackspeed | 4 | 4 | 0 | 100.0% |
| zerglingmovementspeed | 48 | 48 | 0 | 100.0% |

## Economy — MAPE by Field and Checkpoint

| Field | 1min | 3min | 5min | 7min |
|-------|------|------|------|------|
| scoreValueMineralsCurrent | 100.9% | 107.4% | 176.4% | 132.6% |
| scoreValueVespeneCurrent | 11.7% | 112.3% | 121.5% | 109.5% |
| scoreValueMineralsCollectionRate | 17.9% | 13.3% | 50.1% | 39.6% |
| scoreValueVespeneCollectionRate | 33.2% | 36.4% | 39.4% | 40.7% |
| scoreValueFoodMade | 284.6% | 162.0% | 106.6% | 83.0% |
| scoreValueFoodUsed | 870.5% | 522.8% | 304.0% | 234.6% |
| scoreValueWorkersActiveCount | 6.0% | 11.6% | 20.6% | 47.0% |
| scoreValueMineralsUsedCurrentArmy | 94.7% | 4034.6% | 1252.8% | 642.2% |
| scoreValueMineralsUsedCurrentEconomy | 200.3% | 109.7% | 67.5% | 50.1% |
| scoreValueMineralsUsedCurrentTechnology | 99.0% | 553.1% | 219.3% | 132.2% |
| scoreValueVespeneUsedCurrentArmy | 85.9% | 2076.9% | 1572.0% | 871.7% |
| scoreValueVespeneUsedCurrentEconomy | 0.0% | 0.0% | 0.6% | 0.7% |
| scoreValueVespeneUsedCurrentTechnology | 86.9% | 353.0% | 434.9% | 425.0% |

## Per-Replay — Worst 10

| Replay | Units | Buildings | Upgrades | Economy 5min |
|--------|-------|-----------|----------|--------------|
| 045c34064d057b115e5a5caddefd8a6f8090c4e47d0368e8fdc546a4fc437bc2.SC2Replay | 84.6% | 85.7% | 16.7% | 6440.0% |
| 1fcbf5f42bb3603f7d0cb3b539996839f2ee33eed12a3f368c5bbd0e03eead36.SC2Replay | 97.4% | 96.4% | 14.3% | 1100.0% |
| 1d01e601e5af36dcdbd5c1a8fc32d61f557bc41499f001dc3f724be2bfed1d62.SC2Replay | 58.7% | 94.4% | 6.7% | 458.7% |
| 19e3a526400fc98757f61b3826136da6b91dcffd21448d4a307cb9f958399ed9.SC2Replay | 80.8% | 100.0% | 12.5% | 159.4% |
| 4822d7d400303ce45156b359bcb36d855a7b20a270005a0bd538acc397c57e42.SC2Replay | 63.5% | 42.6% | 36.4% | 100.0% |
| 1136f0e99a53c45134c4431ac21a69ca12aed360d8b9b5bb6255fbb0774a0e21.SC2Replay | 61.0% | 71.4% | 23.5% | 100.0% |
| 0ac98ac604576f838a01446b3772d85f4ed62486241a056ea5debc71e6dcd846.SC2Replay | 66.9% | 71.4% | 18.8% | 100.0% |
| 0e3e1920fe525aa06f568f347c3ae19b02323c4f07affc5bf81e8bf17043aae1.SC2Replay | 53.6% | 65.8% | 37.8% | 100.0% |
| 374df5fbb030c14733dcc9fc342e2acaba0d0bdd9f1341036b405b34eff4fa50.SC2Replay | 75.5% | 59.7% | 22.2% | 100.0% |
| 0f0377fe8e41e5794843108af53d0a1f816d6fbda4a4e04ddacd3276e52e63b4.SC2Replay | 71.9% | 60.5% | 27.3% | 100.0% |
