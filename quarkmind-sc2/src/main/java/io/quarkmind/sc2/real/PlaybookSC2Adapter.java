package io.quarkmind.sc2.real;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.unit.Tag;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.playbook.CalibrationBaseline;
import io.quarkmind.sc2.playbook.CalibrationSnapshot;
import io.quarkmind.sc2.playbook.SC2PlaybookRunner;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PlaybookSC2Adapter implements SC2FrameCallback {

    private static final Logger log = Logger.getLogger(PlaybookSC2Adapter.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final QuarkusSC2Transport transport;
    private final List<SC2PlaybookRunner.PlaybookStep> steps;
    private final boolean[] fired;
    private final int durationTicks;
    private final Race race;
    private final String playbookName;

    private final List<CalibrationSnapshot> snapshots = new ArrayList<>();
    private final java.util.Set<String> assignedGasBuildings = new java.util.HashSet<>();
    private final List<io.quarkmind.sc2.playbook.WorkerCadenceSnapshot> workerCadenceSnapshots = new ArrayList<>();

    private int gameLoop = 0;
    private int tick = -1;
    private int buildIndex = 0;
    private int gasIndex = 0;
    private int pendingMorphStepIndex = -1;
    private BuildingType pendingMorphTargetType;
    private List<CalibrationBaseline.PatchPosition> capturedMineralPatches;
    private List<CalibrationBaseline.GeyserPosition> capturedGeyserPositions;
    private float[] capturedBasePosition;


    public PlaybookSC2Adapter(QuarkusSC2Transport transport, String playbookResource) {
        this.transport = transport;
        String yaml = SC2PlaybookRunner.loadResource(playbookResource);
        JsonNode root;
        try { root = YAML.readTree(yaml); }
        catch (Exception e) { throw new RuntimeException("Failed to parse playbook", e); }

        this.steps = SC2PlaybookRunner.parseSteps(root);
        this.fired = new boolean[steps.size()];
        this.race = SC2PlaybookRunner.resolveRace(root);
        this.durationTicks = SC2PlaybookRunner.resolveDuration(root, steps);
        this.playbookName = playbookResource
            .replaceAll(".*/", "")
            .replace(".yaml", "")
            .replace(".yml", "");
    }

    @Override
    public void onGameStart(ResponseGameInfo info) {
        log.infof("[Recorder] Game started — recording %d ticks for %s", durationTicks, playbookName);
    }

    @Override
    public void onStep(Observation obs) throws InterruptedException {
            int emTick = gameLoop / io.quarkmind.domain.SC2Data.LOOPS_PER_TICK;
            if (emTick >= durationTicks) {
                transport.quit();
                return;
            }

            boolean newTick = emTick > tick;
            if (newTick) {
                tick = emTick;
                GameState state = ObservationTranslator.translate(obs, null);
                snapshots.add(CalibrationSnapshot.from(tick, state));
                workerCadenceSnapshots.add(ObservationTranslator.extractWorkerCadence(
                        tick, obs.getRaw().orElseThrow().getUnits()));

                if (capturedMineralPatches == null && state.mineralPatches() != null) {
                    capturedMineralPatches = state.mineralPatches().stream()
                        .map(r -> new CalibrationBaseline.PatchPosition(
                            r.position().x(), r.position().y(), r.remaining(), r.rich()))
                        .toList();
                }
                if (capturedGeyserPositions == null && state.geysers() != null) {
                    capturedGeyserPositions = state.geysers().stream()
                        .map(r -> new CalibrationBaseline.GeyserPosition(
                            r.position().x(), r.position().y(), r.remaining()))
                        .toList();
                }
                if (capturedBasePosition == null) {
                    state.myBuildings().stream()
                        .filter(b -> b.isComplete())
                        .findFirst()
                        .ifPresent(b -> capturedBasePosition = new float[]{b.position().x(), b.position().y()});
                }

                int                   supplyUsed = (int) state.supplyUsed();
                List<ResolvedCommand> commands   = new ArrayList<>();

                if (pendingMorphStepIndex >= 0) {
                    if (findBuildingTag(state, pendingMorphTargetType) != null) {
                        fired[pendingMorphStepIndex] = true;
                        log.infof("[Recorder] tick %d: morph to %s confirmed", tick, pendingMorphTargetType);
                        pendingMorphStepIndex = -1;
                        pendingMorphTargetType = null;
                    }
                }

                for (int i = 0; i < steps.size(); i++) {
                    SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                    boolean canFire = step.shouldFire(tick, supplyUsed)
                                      && (!fired[i] || step.loop());
                    if (!canFire) continue;

                    if ("morph".equals(step.action())) {
                        if (pendingMorphStepIndex >= 0) continue;
                        translateMorphDebug(step, state);
                        String morphTarget = (String) step.params().get("morph");
                        String sourceStr = (String) step.params().get("source");
                        pendingMorphStepIndex = i;
                        pendingMorphTargetType = BuildingType.valueOf(morphTarget != null ? morphTarget : sourceStr);
                        log.infof("[Recorder] tick %d (loop %d): morph %s via debug API supply=%d/%d",
                                  tick, gameLoop, pendingMorphTargetType, supplyUsed, (int) state.supply());
                        continue;
                    }

                    ResolvedCommand cmd = translateStep(step, state);
                    if (cmd != null) {
                        commands.add(cmd);
                    }
                    if (cmd != null || "build".equals(step.action())) {
                        fired[i] = true;
                        log.infof("[Recorder] tick %d (loop %d): %s supply=%d/%d",
                                  tick, gameLoop, step.action(), supplyUsed, (int) state.supply());
                    }
                }

                assignWorkersToGas(state, commands);

                if (!commands.isEmpty()) {
                    transport.sendActions(commands);
                }
            }
        gameLoop++;
    }

    @Override
    public void onGameEnd(GameResult result) {
        log.infof("[Recorder] Game ended — %d snapshots captured, result=%s", snapshots.size(), result);
    }

    public CalibrationBaseline buildBaseline(String sc2Build) {
        return new CalibrationBaseline(
            playbookName, race.name(), sc2Build,
            Instant.now().toString(), snapshots.size(),
            List.copyOf(snapshots),
            capturedMineralPatches,
            capturedGeyserPositions,
            capturedBasePosition
        );
    }

    public List<CalibrationSnapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    public List<io.quarkmind.sc2.playbook.WorkerCadenceSnapshot> workerCadenceSnapshots() {
        return List.copyOf(workerCadenceSnapshots);
    }

    public io.quarkmind.sc2.playbook.WorkerCadenceBaseline buildWorkerCadenceBaseline(String sc2Build) {
        return new io.quarkmind.sc2.playbook.WorkerCadenceBaseline(
                playbookName, race.name(), sc2Build,
                java.time.Instant.now().toString(),
                workerCadenceSnapshots.size(),
                List.copyOf(workerCadenceSnapshots)
        );
    }


    private ResolvedCommand translateStep(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        return switch (step.action()) {
                case "train" -> translateTrain(step, state);
                case "build" -> translateBuild(step, state);
                case "morph" -> null;
                case "calldown" -> translateCalldown(step, state);
                case "assert" -> null;
                default -> {
                    log.warnf("[Recorder] Unsupported action for SC2 recording: %s", step.action());
                    yield null;
                }
        };
    }

    private ResolvedCommand translateTrain(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        UnitType unitType = UnitType.valueOf((String) step.params().get("train"));
        Abilities ability = ActionTranslator.mapTrainAbility(unitType);
        if (ability == null) return null;

        BuildingType producerType = findProducerType(unitType);
        if (producerType == null) return null;

        Tag producerTag = findBuildingTag(state, producerType);
        if (producerTag == null) {
            log.debugf("[Recorder] No %s found for training %s", producerType, unitType);
            return null;
        }
        return new ResolvedCommand(producerTag, ability, Optional.empty());
    }

    private ResolvedCommand translateBuild(SC2PlaybookRunner.PlaybookStep step, GameState state) {
            BuildingType bt = BuildingType.valueOf((String) step.params().get("build"));

            if (bt == BuildingType.ASSIMILATOR || bt == BuildingType.REFINERY || bt == BuildingType.EXTRACTOR) {
                return translateGasBuildingDebug(bt, state);
            }

            io.quarkmind.domain.Point2d basePos = findBasePosition(state);
            if (basePos == null) {
                log.infof("[Recorder] No base found for build placement");
                return null;
            }

            float offsetX = -8f - (buildIndex % 3) * 4f;
            float offsetY = 8f + (buildIndex / 3) * 4f;
            buildIndex++;

            int sc2UnitType = mapToSC2UnitTypeId(bt);
            if (sc2UnitType < 0) {
                log.infof("[Recorder] No SC2 unit type ID for %s", bt);
                return null;
            }

            try {
                SC2APIProtocol.Debug.DebugCreateUnit createUnit = SC2APIProtocol.Debug.DebugCreateUnit.newBuilder()
                                                                                                      .setUnitType(sc2UnitType)
                                                                                                      .setOwner(1)
                                                                                                      .setPos(SC2APIProtocol.Common.Point2D.newBuilder()
                                                                                                                                           .setX(basePos.x() + offsetX)
                                                                                                                                           .setY(basePos.y() + offsetY)
                                                                                                                                           .build())
                                                                                                      .setQuantity(1)
                                                                                                      .build();
                SC2APIProtocol.Sc2Api.RequestDebug debugReq = SC2APIProtocol.Sc2Api.RequestDebug.newBuilder()
                                                                                                .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder()
                                                                                                                                           .setCreateUnit(createUnit)
                                                                                                                                           .build())
                                                                                                .build();
                transport.sendDebug(debugReq);
                log.infof("[Recorder] DEBUG CREATE %s at (%.1f, %.1f) — base=(%.1f, %.1f)",
                          bt, basePos.x() + offsetX, basePos.y() + offsetY, basePos.x(), basePos.y());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        return null;
    }

    private ResolvedCommand translateGasBuildingDebug(BuildingType bt, GameState state) {
        var geysers = state.geysers();
        if (geysers == null || gasIndex >= geysers.size()) {
            log.debugf("[Recorder] No geyser available at index %d", gasIndex);
            return null;
        }
        io.quarkmind.domain.Point2d basePos = findBasePosition(state);
        if (basePos == null) return null;
        var sortedGeysers = geysers.stream()
            .sorted(java.util.Comparator.comparingDouble(g ->
                Math.pow(g.position().x() - basePos.x(), 2) + Math.pow(g.position().y() - basePos.y(), 2)))
            .toList();
        if (gasIndex >= sortedGeysers.size()) return null;
        var geyser = sortedGeysers.get(gasIndex);
        gasIndex++;
        int sc2UnitType = mapToSC2UnitTypeId(bt);
        if (sc2UnitType < 0) {
            log.infof("[Recorder] No SC2 unit type ID for gas building %s", bt);
            return null;
        }
        try {
            SC2APIProtocol.Debug.DebugCreateUnit createUnit = SC2APIProtocol.Debug.DebugCreateUnit.newBuilder()
                .setUnitType(sc2UnitType)
                .setOwner(1)
                .setPos(SC2APIProtocol.Common.Point2D.newBuilder()
                    .setX(geyser.position().x())
                    .setY(geyser.position().y())
                    .build())
                .setQuantity(1)
                .build();
            SC2APIProtocol.Sc2Api.RequestDebug debugReq = SC2APIProtocol.Sc2Api.RequestDebug.newBuilder()
                .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder()
                    .setCreateUnit(createUnit)
                    .build())
                .build();
            transport.sendDebug(debugReq);
            log.infof("[Recorder] DEBUG CREATE %s at geyser (%.1f, %.1f) — base=(%.1f, %.1f)",
                      bt, geyser.position().x(), geyser.position().y(), basePos.x(), basePos.y());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private ResolvedCommand translateGasBuilding(Tag workerTag, Abilities ability, GameState state) {
        var geysers = state.geysers();
        if (geysers == null || gasIndex >= geysers.size()) {
            log.debugf("[Recorder] No geyser available at index %d", gasIndex);
            return null;
        }

        io.quarkmind.domain.Point2d basePos = findBasePosition(state);
        if (basePos == null) {return null;}

        var sortedGeysers = geysers.stream()
                                   .sorted(java.util.Comparator.comparingDouble(g ->
                                                                                        Math.pow(g.position().x() - basePos.x(), 2) + Math.pow(g.position().y() - basePos.y(), 2)))
                                   .toList();

        if (gasIndex >= sortedGeysers.size()) {return null;}

        var geyser = sortedGeysers.get(gasIndex);
        gasIndex++;
        long geyserTag = Long.parseLong(geyser.tag());
        log.infof("[Recorder] Gas build: worker=%s ability=%s geyser=%s at (%.1f, %.1f)",
                  workerTag, ability, geyserTag, geyser.position().x(), geyser.position().y());
        return ResolvedCommand.targetUnit(workerTag, ability, geyserTag);
    }

    private void translateMorphDebug(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        String sourceStr   = (String) step.params().get("source");
        String morphTarget = (String) step.params().get("morph");
        if (sourceStr == null) {
            log.warnf("[Recorder] morph step missing 'source' param");
            return;
        }
        BuildingType sourceType = BuildingType.valueOf(sourceStr);
        BuildingType targetType = BuildingType.valueOf(morphTarget != null ? morphTarget : sourceStr);

        Building sourceBuilding = state.myBuildings().stream()
            .filter(b -> b.type() == sourceType && b.isComplete())
            .findFirst().orElse(null);
        if (sourceBuilding == null) {
            log.debugf("[Recorder] No %s found for morph to %s", sourceType, targetType);
            return;
        }

        int sc2TargetType = mapToSC2UnitTypeId(targetType);
        if (sc2TargetType < 0) {
            log.warnf("[Recorder] No SC2 unit type ID for morph target %s", targetType);
            return;
        }

        try {
            long sourceTag = Long.parseLong(sourceBuilding.tag());
            SC2APIProtocol.Debug.DebugKillUnit kill = SC2APIProtocol.Debug.DebugKillUnit.newBuilder()
                .addTag(sourceTag).build();
            SC2APIProtocol.Debug.DebugCreateUnit create = SC2APIProtocol.Debug.DebugCreateUnit.newBuilder()
                .setUnitType(sc2TargetType)
                .setOwner(1)
                .setPos(SC2APIProtocol.Common.Point2D.newBuilder()
                    .setX(sourceBuilding.position().x())
                    .setY(sourceBuilding.position().y())
                    .build())
                .setQuantity(1)
                .build();
            SC2APIProtocol.Sc2Api.RequestDebug debugReq = SC2APIProtocol.Sc2Api.RequestDebug.newBuilder()
                .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder().setKillUnit(kill).build())
                .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder().setCreateUnit(create).build())
                .build();
            transport.sendDebug(debugReq);
            log.infof("[Recorder] DEBUG MORPH %s -> %s at (%.1f, %.1f)",
                      sourceType, targetType, sourceBuilding.position().x(), sourceBuilding.position().y());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ResolvedCommand translateCalldown(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        String calldownType = (String) step.params().get("calldown");
        if (!"MULE".equals(calldownType)) {
            log.warnf("[Recorder] Unsupported calldown type: %s", calldownType);
            return null;
        }
        Tag ocTag = findBuildingTag(state, BuildingType.ORBITAL_COMMAND);
        if (ocTag == null) {
            log.debugf("[Recorder] No Orbital Command found for MULE calldown");
            return null;
        }
        var minerals = state.mineralPatches();
        if (minerals == null || minerals.isEmpty()) {return null;}
        io.quarkmind.domain.Point2d basePos = findBasePosition(state);
        if (basePos == null) {return null;}
        var nearest = minerals.stream()
                              .min(java.util.Comparator.comparingDouble(m ->
                                                                                Math.pow(m.position().x() - basePos.x(), 2) + Math.pow(m.position().y() - basePos.y(), 2)))
                              .orElse(null);
        if (nearest == null) {return null;}
        com.github.ocraft.s2client.protocol.spatial.Point2d pos =
                com.github.ocraft.s2client.protocol.spatial.Point2d.of(
                        nearest.position().x(), nearest.position().y());
        return new ResolvedCommand(ocTag, Abilities.EFFECT_CALL_DOWN_MULE, Optional.of(pos));
    }

    private io.quarkmind.domain.Point2d findBasePosition(GameState state) {
        BuildingType baseType = switch (race) {
            case PROTOSS -> BuildingType.NEXUS;
            case TERRAN -> BuildingType.COMMAND_CENTER;
            case ZERG -> BuildingType.HATCHERY;
        };
        return state.myBuildings().stream()
                    .filter(b -> b.type() == baseType || b.type() == BuildingType.ORBITAL_COMMAND
                                 || b.type() == BuildingType.PLANETARY_FORTRESS
                                 || b.type() == BuildingType.LAIR || b.type() == BuildingType.HIVE)
                    .map(Building::position)
                    .findFirst()
                    .orElse(null);
    }


    private void assignWorkersToGas(GameState state, List<ResolvedCommand> commands) {
        Abilities harvestAbility = switch (race) {
            case PROTOSS -> Abilities.HARVEST_GATHER_PROBE;
            case TERRAN  -> Abilities.HARVEST_GATHER_SCV;
            case ZERG    -> Abilities.HARVEST_GATHER_DRONE;
        };
        UnitType workerType = workerForRace(race);
        for (Building b : state.myBuildings()) {
            if (!b.isComplete()) continue;
            if (!io.quarkmind.domain.SC2Data.isGasBuilding(b.type())) continue;
            if (assignedGasBuildings.contains(b.tag())) continue;
            assignedGasBuildings.add(b.tag());
            long gasTag = Long.parseLong(b.tag());
            int assigned = 0;
            for (var u : state.myUnits()) {
                if (u.type() != workerType) continue;
                if (assigned >= 3) break;
                Tag wTag = Tag.of(Long.parseLong(u.tag()));
                commands.add(ResolvedCommand.targetUnit(wTag, harvestAbility, gasTag));
                assigned++;
            }
            log.infof("[Recorder] tick %d: assigned %d workers to gas %s (tag=%s)",
                      tick, assigned, b.type(), b.tag());
        }
    }

    private Tag findBuildingTag(GameState state, BuildingType type) {
        return state.myBuildings().stream()
            .filter(b -> matchesBuildingType(b.type(), type) && b.isComplete())
            .findFirst()
            .map(b -> Tag.of(Long.parseLong(b.tag())))
            .orElse(null);
    }

    private static boolean matchesBuildingType(BuildingType actual, BuildingType requested) {
        if (actual == requested) return true;
        if (requested == BuildingType.COMMAND_CENTER
            && (actual == BuildingType.ORBITAL_COMMAND || actual == BuildingType.PLANETARY_FORTRESS)) return true;
        if (requested == BuildingType.HATCHERY
            && (actual == BuildingType.LAIR || actual == BuildingType.HIVE)) return true;
        return false;
    }

    private Tag findUnitTag(GameState state, UnitType type) {
        return state.myUnits().stream()
            .filter(u -> u.type() == type)
            .findFirst()
            .map(u -> Tag.of(Long.parseLong(u.tag())))
            .orElse(null);
    }

    private static UnitType workerForRace(Race race) {
        return switch (race) {
            case PROTOSS -> UnitType.PROBE;
            case TERRAN -> UnitType.SCV;
            case ZERG -> UnitType.DRONE;
        };
    }

    private static BuildingType findProducerType(UnitType unitType) {
        return switch (unitType) {
            case PROBE -> BuildingType.NEXUS;
            case SCV -> BuildingType.COMMAND_CENTER;
            case ZEALOT, STALKER, ADEPT, SENTRY, DARK_TEMPLAR, HIGH_TEMPLAR ->
                BuildingType.GATEWAY;
            case MARINE, MARAUDER, REAPER, GHOST -> BuildingType.BARRACKS;
            case HELLION, HELLBAT, SIEGE_TANK, CYCLONE, THOR -> BuildingType.FACTORY;
            case MEDIVAC, VIKING, RAVEN, BANSHEE, BATTLECRUISER, LIBERATOR -> BuildingType.STARPORT;
            case DRONE -> BuildingType.HATCHERY;
            case QUEEN -> BuildingType.HATCHERY;
            case OVERLORD -> BuildingType.HATCHERY;
            case ZERGLING, ROACH, HYDRALISK, MUTALISK, CORRUPTOR, INFESTOR, SWARM_HOST, VIPER, ULTRALISK -> BuildingType.HATCHERY;
            default -> null;
        };
    }

    private static int mapToSC2UnitTypeId(BuildingType bt) {
        return switch (bt) {
            case PYLON -> 60;
            case NEXUS -> 59;
            case GATEWAY -> 62;
            case CYBERNETICS_CORE -> 72;
            case FORGE -> 63;
            case ASSIMILATOR -> 61;
            case SUPPLY_DEPOT -> 19;
            case COMMAND_CENTER -> 18;
            case BARRACKS -> 21;
            case ENGINEERING_BAY -> 22;
            case REFINERY -> 20;
            case ORBITAL_COMMAND -> 132;
            case PLANETARY_FORTRESS -> 130;
            case HATCHERY -> 86;
            case SPAWNING_POOL -> 89;
            case EXTRACTOR -> 88;
            default -> -1;
        };
    }

}
