# Upgrade Detection Gaps

Upgrade types that cannot be reliably detected from stripped replay data.
Validated across 118 oracle replays (patch 4.9.3).

## Gameplay accuracy

**97.8%** (742/759 gameplay upgrade events correctly detected).

## Under-detection (no dedicated CmdEvent in some replays)

| UpgradeType | Oracle | Detected | Root cause |
|---|---|---|---|
| EvolveGroovedSpines | 12 | 4 | abilLink 262 fires in ~33% of replays; remainder use generic commands (abilLink 193 = ABIL_LARVA) with no upgrade-specific signal |
| EvolveMuscularAugments | 11 | 4 | abilLink 310 fires in ~36% of replays; same generic command pattern |
| DarkTemplarBlinkUpgrade | 1 | 0 | No dedicated CmdEvent; correlates only with generic warpgate commands |
| PhoenixRangeUpgrade | 1 | 0 | No dedicated CmdEvent; abilLink 170 (ABIL_WARPGATE) is multi-purpose |

## Over-detection (minor, +1 each)

| UpgradeType | Oracle | Detected | Root cause |
|---|---|---|---|
| ShieldWall | 30 | 31 | Residual duplicate CmdEvent in 1 replay |
| Burrow | 9 | 10 | Same pattern |
| DrillClaws | 7 | 8 | Same pattern |
| InfestorEnergyUpgrade | 5 | 6 | Same pattern |
| NeuralParasite | 4 | 5 | Same pattern |
| TerranInfantryArmorsLevel1 | 25 | 26 | Same pattern |
| TerranShipWeaponsLevel1 | 13 | 14 | Same pattern |
| ZergGroundArmorsLevel2 | 11 | 12 | Same pattern |
| CycloneLockOnDamageUpgrade | 4 | 6 | +2, likely dual CmdEvent firing |

## Cosmetic upgrades (not gameplay)

These are visual/reward upgrades with no CmdEvent signal — excluded from gameplay accuracy.

| Category | Types | Oracle events |
|---|---|---|
| RewardDance* | 9 (Colossus, Ghost, Infestor, Mule, Oracle, Overlord, Roach, Stalker, Viking) | 1329 |
| Spray* | 3 (Protoss, Terran, Zerg) | 1367 |
| GhostAlternate | 1 | 2 |
| **Total cosmetic** | **13** | **2698** |

## Fixes applied (#351)

| Issue | Fix | Impact |
|---|---|---|
| WarpGateResearch +43 over-detection | Set `warpGateResearchEmitted` in ABIL_CYBERNETICS_CORE path | 91→48 (exact match) |
| Charge +7 over-detection | Remove duplicate ABIL_TWILIGHT_COUNCIL (547) | 27→20 (exact match) |
| PunisherGrenades +12 over-detection | Remove duplicate ABIL_CONCUSSIVE_SHELLS (152) | 30→18 (exact match) |
| ShieldWall +15 over-detection | Remove duplicate ABIL_COMBAT_SHIELD (124) | 45→31 (residual +1) |
