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

---

## Cross-patch validation (#363)

### abilLink offset discovery

SC2's ability catalog shifts when new abilities are inserted between patches.
Between baseBuild 75689 (patch 4.9.3) and baseBuild 82457+ (patch ~5.0.x), two
abilities were added, shifting all subsequent abilLink values by **+2**.

`AbilityProfile.abilLinkOffset()` normalizes incoming abilLink values before the
dispatch switch: `normalized = abilLink - profile.abilLinkOffset()`. This lets the
same base dispatch table (with 4.9.3 constants) handle any patch version.

| Profile | baseBuild range | abilLinkOffset | Covers |
|---|---|---|---|
| `V4_9_3` | ≤75689 | 0 | 2016 IEM through 4.9.3 ladder |
| `HSC_2025` | >75689 | 2 | 2020 ASUS ROG through 2025 HSC XXVII |

### Per-dataset accuracy

| Dataset | baseBuild | Year | Replays | Gameplay accuracy |
|---|---|---|---|---|
| IEM PyeongChang 2018 | 60321 | 2018 | 62 | 98.2% |
| Blizzard ladder 4.9.3 | 75689 | 2019 | 118 oracle | 100.0% |
| ASUS ROG 2020 | 82457 | 2020 | 107 | 95.3% |
| DreamHack Dallas 2025 | 93333 | 2025 | 64 | 95.8% |
| HSC XXVII 2025 | 94137 | 2025 | 61 | 96.8% |

### Fixes applied (#363)

| Issue | Fix | Impact |
|---|---|---|
| abilLink values wrong for builds >75689 | Add `abilLinkOffset` to `AbilityProfile`; normalize in `dispatchHuman()` and `dispatch()` | 8.1% → 79.3% on HSC XXVII |
| Observer replays: P2 CmdEvents at userId=2 not userId=1 | Detect actual userIds from CmdEvent frequency in `StrippedReplayFeatureExtractor` | 65.1% → 79.3% on HSC XXVII |
| GameHeartActive inflated miss count (tournament overlay, not gameplay) | Add to cosmetic exclusion list | 79.3% → 96.8% on HSC XXVII |
| RoboticsBay missing GraviticDrive, ObserverGraviticBooster mappings | Add idx=1 (GraviticDrive), idx=7 (ObserverGraviticBooster) | 4.9.3 unaffected; coverage improved |
| FleetBeacon missing TempestGroundAttackUpgrade | Add idx=3 | 4.9.3 unaffected; coverage improved |
| HSC_2025 override map caused massive over-detection | Replace with empty overrides + offset | Stimpack 716→11, DarkTemplarBlink 141→1 |

### Remaining gaps (unmapped upgrades)

These upgrades are not in the base dispatch table and require new abilLink discovery:

| Upgrade | Oracle count | Building | Notes |
|---|---|---|---|
| PersonalCloaking | 3 | GhostAcademy | Uses ABIL_PROBE_BUILD path in 4.9.3 — offset breaks the collision |
| LiberatorAGRangeUpgrade | 3 | StarportTechLab | Not mapped in 4.9.3 |
| Frenzy | 1 | UltraliskCavern | Not mapped in 4.9.3 (idx unknown) |
| MedivacCaduceusReactor | 1 | StarportTechLab | Not mapped in 4.9.3 |
| BansheeSpeed | 1 | StarportTechLab | Not mapped in 4.9.3 |
| HighCapacityBarrels | 1 | FactoryTechLab | Not mapped in 4.9.3 |
| LurkerRange | 1 | LurkerDen | Not mapped in 4.9.3 |
| InterferenceMatrix | 3 | — | Raven ability usage, not building research |

### Discovery methodology (#363)

**Co-occurrence analysis** (`discoverAbilLinksByCooccurrence`): for each upgrade type
in the oracle, finds abilLink+idx combinations that co-occur with that upgrade across
replays. Precision = fraction of replays with the abilLink that also have the upgrade.
Recall = fraction of upgrade replays where the abilLink fires. High precision + high
recall = correct mapping.

**baseBuild survey** (`scanBaseBuildAcrossDatasets`): reads replay headers across all
downloaded datasets to map baseBuild values to tournament years and determine which
`AbilityProfile` to use.
