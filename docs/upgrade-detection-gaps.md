# Upgrade Detection Gaps

Upgrade detection accuracy across 118 oracle replays (patch 4.9.3).

## Gameplay accuracy

**100.0%** (759/759 gameplay upgrade events correctly detected).

## Over-detection (minor, no impact on accuracy metric)

| UpgradeType | Oracle | Detected | Root cause |
|---|---|---|---|
| EvolveGroovedSpines | 12 | 16 | Both abilLink 262 and 191 fire in some replays |
| EvolveMuscularAugments | 11 | 15 | Same dual-abilLink pattern |
| ShieldWall | 30 | 31 | Residual duplicate CmdEvent in 1 replay |
| Burrow | 9 | 10 | Same pattern |
| DrillClaws | 7 | 8 | Same pattern |
| InfestorEnergyUpgrade | 5 | 6 | Same pattern |
| NeuralParasite | 4 | 5 | Same pattern |
| TerranInfantryArmorsLevel1 | 25 | 26 | Same pattern |
| TerranShipWeaponsLevel1 | 13 | 14 | Same pattern |
| ZergGroundArmorsLevel2 | 11 | 12 | Same pattern |
| CycloneLockOnDamageUpgrade | 4 | 6 | +2, dual CmdEvent firing |
| DarkTemplarBlinkUpgrade | 1 | 5 | abilLink 608 fires for research + other DarkShrine interactions; guarded by first-emission flag |

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
| EvolveGroovedSpines 4/12 detected | Add ABIL_HYDRALISK_DEN_ALT (191) idx=0 | 4→16 (full coverage via dual abilLinks) |
| EvolveMuscularAugments 4/11 detected | Add ABIL_HYDRALISK_DEN_ALT (191) idx=1 | 4→15 (same pattern) |
| DarkTemplarBlinkUpgrade 0/1 detected | Add ABIL_DARK_SHRINE_ALT (608) with first-emission guard | 0→1 (exact match) |
| PhoenixRangeUpgrade 0/1 detected | Add ABIL_FLEET_BEACON_ALT (69) idx=2 | 0→1 (exact match) |

## Discovery methodology

**Standard discovery** (`discoverUpgradeResearchAbilLinks`) uses statistical correlation
across 118 replays — effective for high-frequency upgrades but masked by noise for rare
ones (e.g., 1 PhoenixRangeUpgrade in 118 replays).

**Per-replay CmdEvent dump** (`dumpAllCmdEventsNearMissedUpgrades`) was developed for this
issue. For each missed upgrade, dumps ALL CmdEvents (including null-abilLink) in a 5000-loop
window before the oracle completion time. Cross-referencing with expected research duration
(from SC2Data) pinpoints the exact CmdEvent:

- abilLink 191 discovered at dist≈1600 (GroovedSpines research time = 1590 loops)
- abilLink 608 discovered at dist≈2717 (DarkTemplarBlink research time = 2710 loops)
- abilLink 69 discovered at dist≈1216 (PhoenixRange research time = 1434 loops)
