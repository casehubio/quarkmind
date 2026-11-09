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
    private int gameLoop = 0;
    private int tick = -1;
    private int buildIndex = 0;
    private int gasIndex = 0;


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

            int                   supplyUsed = (int) state.supplyUsed();
            List<ResolvedCommand> commands   = new ArrayList<>();

            for (int i = 0; i < steps.size(); i++) {
                SC2PlaybookRunner.PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed)
                                  && (!fired[i] || step.loop());
                if (canFire) {
                    ResolvedCommand cmd = translateStep(step, state);
                    if (cmd != null) {
                        commands.add(cmd);
                        fired[i] = true;
                        log.debugf("[Recorder] tick %d (loop %d): %s", tick, gameLoop, step.action());
                    }
                }
            }

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
            List.copyOf(snapshots)
        );
    }

    public List<CalibrationSnapshot> snapshots() {
        return List.copyOf(snapshots);
    }

    private ResolvedCommand translateStep(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        return switch (step.action()) {
                case "train" -> translateTrain(step, state);
                case "build" -> translateBuild(step, state);
                case "morph" -> translateMorph(step, state);
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
        BuildingType bt      = BuildingType.valueOf((String) step.params().get("build"));
            Abilities    ability = ActionTranslator.mapBuildAbility(bt);
            if (ability == null) {return null;}

            UnitType workerType = workerForRace(race);
            Tag      workerTag  = findUnitTag(state, workerType);
            if (workerTag == null) {
                log.debugf("[Recorder] No worker found for building %s", bt);
                return null;
            }

            if (bt == BuildingType.ASSIMILATOR || bt == BuildingType.REFINERY || bt == BuildingType.EXTRACTOR) {
                return translateGasBuilding(workerTag, ability, state);
            }

            io.quarkmind.domain.Point2d basePos = findBasePosition(state);
            if (basePos == null) {
                log.warnf("[Recorder] No base found for build placement");
                return null;
            }

            float offsetX = 5f + (buildIndex % 3) * 3f;
            float offsetY = -3f + (buildIndex / 3) * 3f;
            buildIndex++;

            com.github.ocraft.s2client.protocol.spatial.Point2d pos =
                    com.github.ocraft.s2client.protocol.spatial.Point2d.of(
                            basePos.x() + offsetX, basePos.y() + offsetY);

        return new ResolvedCommand(workerTag, ability, Optional.of(pos));
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
        return ResolvedCommand.targetUnit(workerTag, ability, geyserTag);
    }

    private ResolvedCommand translateMorph(SC2PlaybookRunner.PlaybookStep step, GameState state) {
        String sourceStr   = (String) step.params().get("source");
        String morphTarget = (String) step.params().get("morph");
        if (sourceStr == null) {
            log.warnf("[Recorder] morph step missing 'source' param");
            return null;
        }
        BuildingType sourceType = BuildingType.valueOf(sourceStr);
        BuildingType targetType = BuildingType.valueOf(morphTarget != null ? morphTarget : sourceStr);
        Abilities    ability    = ActionTranslator.mapBuildAbility(targetType);
        if (ability == null) {
            log.warnf("[Recorder] No ability mapped for morph target %s", targetType);
            return null;
        }
        Tag buildingTag = findBuildingTag(state, sourceType);
        if (buildingTag == null) {
            log.debugf("[Recorder] No %s found for morph to %s", sourceType, targetType);
            return null;
        }
        return new ResolvedCommand(buildingTag, ability, Optional.empty());
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


    private Tag findBuildingTag(GameState state, BuildingType type) {
        return state.myBuildings().stream()
            .filter(b -> b.type() == type && b.isComplete())
            .findFirst()
            .map(b -> Tag.of(Long.parseLong(b.tag())))
            .orElse(null);
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
            default -> null;
        };
    }
}
