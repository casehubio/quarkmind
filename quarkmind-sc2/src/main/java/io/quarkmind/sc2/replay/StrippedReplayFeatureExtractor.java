package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.details.Result;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Subgroup;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StrippedReplayFeatureExtractor {


    private static final int                   ID_CMD_UPDATE_TARGET_POINT = 104;
    private static final int                   ABIL_WARPGATE_WARPIN       = 214;
    private static final Map<Integer, Set<Integer>> PRODUCTION_UNIT_LINKS = Map.ofEntries(
            Map.entry(155, Set.of(67)),          // CommandCenter
            Map.entry(159, Set.of(70)),          // Barracks (or OrbitalCommand in same group)
            Map.entry(160, Set.of(75, 48)),      // Factory (+ with addon)
            Map.entry(161, Set.of(56, 49)),      // Starport (+ with addon)
            Map.entry(172, Set.of(84)),          // Gateway
            Map.entry(173, Set.of(89)),          // Stargate
            Map.entry(174, Set.of(93)),          // RoboticsFacility
            Map.entry(175, Set.of(81, 106))      // Nexus (+ Nexus variant)
    );



    private static final Map<UnitType, String> UNIT_PYTHON_NAMES;
    static {
        var map = new EnumMap<UnitType, String>(UnitType.class);
        // Terran
        map.put(UnitType.SCV, "SCV");
        map.put(UnitType.MARINE, "Marine");
        map.put(UnitType.MARAUDER, "Marauder");
        map.put(UnitType.REAPER, "Reaper");
        map.put(UnitType.GHOST, "Ghost");
        map.put(UnitType.HELLION, "Hellion");
        map.put(UnitType.HELLBAT, "HellionTank");
        map.put(UnitType.SIEGE_TANK, "SiegeTank");
        map.put(UnitType.CYCLONE, "Cyclone");
        map.put(UnitType.THOR, "Thor");
        map.put(UnitType.MEDIVAC, "Medivac");
        map.put(UnitType.VIKING, "VikingFighter");
        map.put(UnitType.VIKING_ASSAULT, "VikingAssault");
        map.put(UnitType.LIBERATOR, "Liberator");
        map.put(UnitType.BANSHEE, "Banshee");
        map.put(UnitType.RAVEN, "Raven");
        map.put(UnitType.WIDOW_MINE, "WidowMine");
        map.put(UnitType.BATTLECRUISER, "Battlecruiser");
        // Zerg
        map.put(UnitType.DRONE, "Drone");
        map.put(UnitType.ZERGLING, "Zergling");
        map.put(UnitType.BANELING, "Baneling");
        map.put(UnitType.ROACH, "Roach");
        map.put(UnitType.RAVAGER, "Ravager");
        map.put(UnitType.QUEEN, "Queen");
        map.put(UnitType.MUTALISK, "Mutalisk");
        map.put(UnitType.CORRUPTOR, "Corruptor");
        map.put(UnitType.BROOD_LORD, "BroodLord");
        map.put(UnitType.HYDRALISK, "Hydralisk");
        map.put(UnitType.LURKER, "Lurker");
        map.put(UnitType.INFESTOR, "Infestor");
        map.put(UnitType.SWARM_HOST, "SwarmHost");
        map.put(UnitType.ULTRALISK, "Ultralisk");
        map.put(UnitType.VIPER, "Viper");
        map.put(UnitType.OVERLORD, "Overlord");
        map.put(UnitType.OVERSEER, "Overseer");
        // Protoss
        map.put(UnitType.PROBE, "Probe");
        map.put(UnitType.ZEALOT, "Zealot");
        map.put(UnitType.STALKER, "Stalker");
        map.put(UnitType.SENTRY, "Sentry");
        map.put(UnitType.ADEPT, "Adept");
        map.put(UnitType.HIGH_TEMPLAR, "HighTemplar");
        map.put(UnitType.DARK_TEMPLAR, "DarkTemplar");
        map.put(UnitType.ARCHON, "Archon");
        map.put(UnitType.IMMORTAL, "Immortal");
        map.put(UnitType.COLOSSUS, "Colossus");
        map.put(UnitType.DISRUPTOR, "Disruptor");
        map.put(UnitType.WARP_PRISM, "WarpPrism");
        map.put(UnitType.PHOENIX, "Phoenix");
        map.put(UnitType.ORACLE, "Oracle");
        map.put(UnitType.VOID_RAY, "VoidRay");
        map.put(UnitType.CARRIER, "Carrier");
        map.put(UnitType.TEMPEST, "Tempest");
        map.put(UnitType.MOTHERSHIP, "Mothership");
        map.put(UnitType.OBSERVER, "Observer");
        UNIT_PYTHON_NAMES = Map.copyOf(map);
    }

    private static final Map<String, BuildingType> BUILDING_NAME_TO_TYPE;
    static {
        var map = new HashMap<String, BuildingType>();
        for (BuildingType bt : BuildingType.values()) {
            String pythonName = buildingTypeToPythonName(bt);
            if (pythonName != null) map.put(pythonName, bt);
        }
        // Add-on buildings and WarpGate — no BuildingType enum entry
        // These produce BuildCommands with these exact names but have no buildTime mapping
        BUILDING_NAME_TO_TYPE = Map.copyOf(map);
    }

    private static final Map<String, Integer> ADDON_BUILD_TIMES = Map.of(
        "BarracksReactor", 800, "BarracksTechLab", 800,
        "FactoryReactor", 800, "FactoryTechLab", 800,
        "StarportReactor", 800, "StarportTechLab", 800
    );

    private static final Set<String> ZERG_BUILDINGS = Set.of(
        "Hatchery", "Extractor", "SpawningPool", "EvolutionChamber",
        "HydraliskDen", "Spire", "UltraliskCavern", "InfestationPit",
        "NydusNetwork", "BanelingNest", "LurkerDenMP", "RoachWarren",
        "SpineCrawler", "SporeCrawler"
    );

    private static final Set<String> BUILDING_MORPH_TARGETS = Set.of(
        "Lair", "Hive", "GreaterSpire", "WarpGate",
        "OrbitalCommand", "PlanetaryFortress"
    );

    private static final Set<UnitType> TECHLAB_UNITS = Set.of(
            UnitType.MARAUDER, UnitType.GHOST,
            UnitType.SIEGE_TANK, UnitType.THOR,
            UnitType.BANSHEE, UnitType.RAVEN, UnitType.BATTLECRUISER
    );

    private static final int MAX_MULTIPLICATION = 8;

    private static final Map<String, Integer> BUILDING_TO_ABIL_LINK = Map.ofEntries(
            Map.entry("CommandCenter", 155), Map.entry("OrbitalCommand", 155),
            Map.entry("PlanetaryFortress", 155),
            Map.entry("Barracks", 159),
            Map.entry("Factory", 160),
            Map.entry("Starport", 161),
            Map.entry("Gateway", 172),
            Map.entry("Stargate", 173),
            Map.entry("RoboticsFacility", 174),
            Map.entry("Nexus", 175),
            Map.entry("Hatchery", 193)
    );

    private static final int ARCHON_MORPH_TIME = 269;
    private static final Set<UnitType> GATEWAY_UNITS = Set.of(
            UnitType.ZEALOT, UnitType.STALKER, UnitType.SENTRY,
            UnitType.ADEPT, UnitType.HIGH_TEMPLAR, UnitType.DARK_TEMPLAR
                                                             );


    public Map<String, Object> extract(Path replayPath) {
        Replay replay = RepParserEngine.parseReplay(replayPath,
                                                    java.util.EnumSet.of(RepContent.GAME_EVENTS));
        if (replay == null) {throw new IllegalArgumentException("Cannot parse replay: " + replayPath);}

        Player[] players = replay.details.getPlayerList();
        if (players.length < 2) {throw new IllegalArgumentException("Need at least 2 players: " + replayPath);}

        List<Event> gameEvents = List.of(replay.gameEvents.getEvents());

        List<SyntheticEvent> syntheticEvents = new ArrayList<>();
        int                  tagCounter      = 1;

        for (int playerId = 1; playerId <= 2; playerId++) {
            var            playerRace = players[playerId - 1].getRace();
            AbilityMapping mapping    = new AbilityMapping(playerId, true, playerRace);
            var            state      = new PlayerState();
            initStartingBuildings(playerRace, state);
            int            userId     = playerId - 1;
            TrainIntent    lastWarpIn = null;
            var            selTracker = new SelectionUnitLinkTracker(userId);

            for (Event raw : gameEvents) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    mapping.onSelection(sel);
                    selTracker.onSelection(sel);
                } else if (raw instanceof CmdEvent cmd) {
                    if (cmd.getUserId() == userId) {
                        lastWarpIn = null;
                    }
                    for (ReplayCommand rc : mapping.process(cmd)) {
                        switch (rc) {
                            case ReplayCommand.IntentCommand ic -> {
                                TimedIntent ti = ic.intent();
                                if (ti.intent() instanceof TrainIntent train) {
                                    Integer abilLink = cmd.getAbilLink();
                                    boolean isWarpIn = abilLink != null && abilLink == ABIL_WARPGATE_WARPIN
                                        && GATEWAY_UNITS.contains(train.unitType());
                                    if (isWarpIn) {
                                        lastWarpIn = train;
                                    }
                                    int repeatCount = 1;
                                    if (abilLink != null && !TECHLAB_UNITS.contains(train.unitType())) {
                                        Set<Integer> validLinks = PRODUCTION_UNIT_LINKS.get(abilLink);
                                        if (validLinks != null) {
                                            int selectionCount = Math.max(1, selTracker.countMatching(validLinks));
                                            int buildingCap = state.productionBuildingCounts.getOrDefault(abilLink, 1);
                                            repeatCount = Math.min(selectionCount, Math.max(buildingCap, MAX_MULTIPLICATION));
                                        }
                                    }
                                    for (int r = 0; r < repeatCount; r++) {
                                        tagCounter = handleTrain(train, ti.loop(), playerId,
                                                                 state, syntheticEvents, tagCounter);
                                    }
                                }
                            }
                            case ReplayCommand.BuildCommand bc -> {
                                tagCounter = handleBuild(bc, playerId,
                                                         state, syntheticEvents, tagCounter);
                            }
                            case ReplayCommand.UpgradeCommand uc -> {
                                tagCounter = handleUpgrade(uc, playerId,
                                                           state, syntheticEvents, tagCounter);
                            }
                            case ReplayCommand.MorphCommand mc -> {
                                tagCounter = handleMorph(mc, playerId,
                                                         syntheticEvents, tagCounter);
                            }
                            case ReplayCommand.CancelCommand ignored -> {}
                            case ReplayCommand.Movement ignored -> {}
                        }
                    }
                } else if (raw.getId() == ID_CMD_UPDATE_TARGET_POINT
                           && raw.getUserId() == userId
                           && lastWarpIn != null) {
                    tagCounter = handleTrain(lastWarpIn, raw.getLoop(), playerId,
                                             state, syntheticEvents, tagCounter);
                }
            }

            if (state.warpGateCompletionLoop > 0) {
                tagCounter = emitWarpGateAutoMorph(playerId, state, syntheticEvents, tagCounter);
            }

            Integer elapsedLoops = replay.header.getElapsedGameLoops();
            if (elapsedLoops != null && elapsedLoops > 0) {
                generatePlayerStats(playerId, state, syntheticEvents, elapsedLoops);
            }
        }

        syntheticEvents.sort(Comparator.comparingLong(SyntheticEvent::loop)
                                       .thenComparing(SyntheticEvent::ordinal));

        return buildGameJson(replay, players, syntheticEvents);
    }

    private static void initStartingBuildings(Race race, PlayerState state) {
        if (race == Race.TERRAN) {
            state.productionBuildingCounts.put(155, 1); // CommandCenter
        } else if (race == Race.PROTOSS) {
            state.productionBuildingCounts.put(175, 1); // Nexus
            state.productionBuildingCounts.put(172, 0); // Gateway — none at start
        } else if (race == Race.ZERG) {
            state.productionBuildingCounts.put(193, 1); // Hatchery (larva)
            state.productionBuildingCounts.put(184, 1); // Hatchery (queen)
        }
    }

    private int handleTrain(TrainIntent train, long commandLoop, int playerId,
                            PlayerState state,
                            List<SyntheticEvent> events, int tagCounter) {
        UnitType unitType   = train.unitType();
        String   pythonName = UNIT_PYTHON_NAMES.get(unitType);
        if (pythonName == null) {return tagCounter;}

        int    trainTime   = SC2Data.trainTimeInLoops(unitType);
        String buildingTag = train.buildingTag();
        long   startLoop;

        if (buildingTag != null) {
            long busyUntil = state.buildingBusyUntil.getOrDefault(buildingTag, 0L);
            startLoop = Math.max(commandLoop, busyUntil);
            state.buildingBusyUntil.put(buildingTag, startLoop + trainTime);
        } else {
            startLoop = commandLoop;
        }

        long birthLoop = startLoop + trainTime;
        trackTrainSpending(unitType, state);

        boolean warpIn = state.warpGateCompletionLoop > 0
                         && commandLoop >= state.warpGateCompletionLoop
                         && GATEWAY_UNITS.contains(unitType);

        int count = SC2Data.trainCount(unitType);
        for (int i = 0; i < count; i++) {
            int tag = tagCounter++;
            if (warpIn) {
                events.add(new SyntheticEvent(startLoop, EventOrdinal.UNIT_INIT, playerId,
                                              Map.of("evtTypeName", "UnitInit",
                                                     "loop", startLoop,
                                                     "controlPlayerId", playerId,
                                                     "unitTypeName", pythonName,
                                                     "unitTagIndex", tag,
                                                     "unitTagRecycle", 0)));
                events.add(new SyntheticEvent(birthLoop, EventOrdinal.UNIT_DONE, playerId,
                                              Map.of("evtTypeName", "UnitDone",
                                                     "loop", birthLoop,
                                                     "controlPlayerId", playerId,
                                                     "unitTypeName", pythonName,
                                                     "unitTagIndex", tag,
                                                     "unitTagRecycle", 0)));
            } else {
                events.add(new SyntheticEvent(birthLoop, EventOrdinal.UNIT_BORN, playerId,
                                              Map.of("evtTypeName", "UnitBorn",
                                                     "loop", birthLoop,
                                                     "controlPlayerId", playerId,
                                                     "unitTypeName", pythonName,
                                                     "unitTagIndex", tag,
                                                     "unitTagRecycle", 0)));
            }
        }
        return tagCounter;
    }

    private int handleBuild(ReplayCommand.BuildCommand bc, int playerId,
                            PlayerState state, List<SyntheticEvent> events, int tagCounter) {
        String buildingName = bc.buildingName();
        if (buildingName == null) return tagCounter;

        long commandLoop = bc.loop();
        int tag = tagCounter++;
        int buildTime = getBuildTime(buildingName);

        events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_INIT, playerId,
            Map.of("evtTypeName", "UnitInit",
                "loop", commandLoop,
                "controlPlayerId", playerId,
                "unitTypeName", buildingName,
                "unitTagIndex", tag,
                "unitTagRecycle", 0)));

        long doneLoop = commandLoop + buildTime;
        events.add(new SyntheticEvent(doneLoop, EventOrdinal.UNIT_DONE, playerId,
            Map.of("evtTypeName", "UnitDone",
                "loop", doneLoop,
                "controlPlayerId", playerId,
                "unitTypeName", buildingName,
                "unitTagIndex", tag,
                "unitTagRecycle", 0)));

        state.trackedBuildings.add(new TrackedBuilding(tag, buildingName, doneLoop));
        Integer abilForBuilding = BUILDING_TO_ABIL_LINK.get(buildingName);
        if (abilForBuilding != null) {
            state.productionBuildingCounts.merge(abilForBuilding, 1, Integer::sum);
        }
        trackBuildSpending(buildingName, state);

        if (ZERG_BUILDINGS.contains(buildingName)) {
            int droneTag = tagCounter++;
            events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_DIED, playerId,
                Map.of("evtTypeName", "UnitDied",
                    "loop", commandLoop,
                    "controlPlayerId", playerId,
                    "unitTypeName", "Drone",
                    "unitTagIndex", droneTag,
                    "unitTagRecycle", 0)));
        }

        return tagCounter;
    }

    private int handleUpgrade(ReplayCommand.UpgradeCommand uc, int playerId,
                              PlayerState state, List<SyntheticEvent> events, int tagCounter) {
        String      upgradeName = uc.upgradeName();
        UpgradeType upgradeType = UpgradeType.fromPythonName(upgradeName);
        if (upgradeType == null) {return tagCounter;}

        long commandLoop    = uc.loop();
        int  upgradeTime    = SC2Data.upgradeTimeInLoops(upgradeType);
        long completionLoop = commandLoop + upgradeTime;

        events.add(new SyntheticEvent(completionLoop, EventOrdinal.UPGRADE, playerId,
                                      Map.of("evtTypeName", "Upgrade",
                                             "loop", completionLoop,
                                             "playerId", playerId,
                                             "upgradeTypeName", upgradeName)));

        if (upgradeType == UpgradeType.WARP_GATE_RESEARCH) {
            state.warpGateCompletionLoop = completionLoop;
        }

        trackUpgradeSpending(upgradeType, state);

        return tagCounter;
    }

    int handleMorph(ReplayCommand.MorphCommand mc, int playerId,
                    List<SyntheticEvent> events, int tagCounter) {
        String sourceName  = mc.sourceName();
        String targetName  = mc.targetName();
        long   commandLoop = mc.loop();

        int sourceDeathCount = "Archon".equals(targetName) ? 2 : 1;
        for (int i = 0; i < sourceDeathCount; i++) {
            int tag = tagCounter++;
            events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_DIED, playerId,
                                          Map.of("evtTypeName", "UnitDied",
                                                 "loop", commandLoop,
                                                 "controlPlayerId", playerId,
                                                 "unitTypeName", sourceName,
                                                 "unitTagIndex", tag,
                                                 "unitTagRecycle", 0)));
        }

        if (BUILDING_MORPH_TARGETS.contains(targetName) || "Archon".equals(targetName)) {
            int morphTime = BUILDING_MORPH_TARGETS.contains(targetName)
                            ? getBuildTime(targetName) : ARCHON_MORPH_TIME;
            int tag = tagCounter++;
            events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_INIT, playerId,
                                          Map.of("evtTypeName", "UnitInit",
                                                 "loop", commandLoop,
                                                 "controlPlayerId", playerId,
                                                 "unitTypeName", targetName,
                                                 "unitTagIndex", tag,
                                                 "unitTagRecycle", 0)));
            long doneLoop = commandLoop + morphTime;
            events.add(new SyntheticEvent(doneLoop, EventOrdinal.UNIT_DONE, playerId,
                                          Map.of("evtTypeName", "UnitDone",
                                                 "loop", doneLoop,
                                                 "controlPlayerId", playerId,
                                                 "unitTypeName", targetName,
                                                 "unitTagIndex", tag,
                                                 "unitTagRecycle", 0)));
        } else {
            int tag = tagCounter++;
            events.add(new SyntheticEvent(commandLoop, EventOrdinal.UNIT_BORN, playerId,
                                          Map.of("evtTypeName", "UnitBorn",
                                                 "loop", commandLoop,
                                                 "controlPlayerId", playerId,
                                                 "unitTypeName", targetName,
                                                 "unitTagIndex", tag,
                                                 "unitTagRecycle", 0)));
        }

        return tagCounter;
    }

    private int emitWarpGateAutoMorph(int playerId, PlayerState state,
                                      List<SyntheticEvent> events, int tagCounter) {
        long completionLoop = state.warpGateCompletionLoop;

        for (TrackedBuilding b : state.trackedBuildings) {
            if ("Gateway".equals(b.name) && b.doneLoop <= completionLoop) {
                events.add(new SyntheticEvent(completionLoop, EventOrdinal.UNIT_DIED, playerId,
                    Map.of("evtTypeName", "UnitDied",
                        "loop", completionLoop,
                        "controlPlayerId", playerId,
                        "unitTypeName", "Gateway",
                        "unitTagIndex", b.tag,
                        "unitTagRecycle", 0)));

                int wgTag = tagCounter++;
                events.add(new SyntheticEvent(completionLoop, EventOrdinal.UNIT_INIT, playerId,
                    Map.of("evtTypeName", "UnitInit",
                        "loop", completionLoop,
                        "controlPlayerId", playerId,
                        "unitTypeName", "WarpGate",
                        "unitTagIndex", wgTag,
                        "unitTagRecycle", 0)));
                events.add(new SyntheticEvent(completionLoop, EventOrdinal.UNIT_DONE, playerId,
                    Map.of("evtTypeName", "UnitDone",
                        "loop", completionLoop,
                        "controlPlayerId", playerId,
                        "unitTypeName", "WarpGate",
                        "unitTagIndex", wgTag,
                        "unitTagRecycle", 0)));
            }
        }
        return tagCounter;
    }

    List<Map<String, Object>> processMorphForTest(ReplayCommand.MorphCommand mc,
                                                   int playerId, int startTag) {
        List<SyntheticEvent> events = new ArrayList<>();
        handleMorph(mc, playerId, events, startTag);
        return events.stream().map(SyntheticEvent::data).toList();
    }

    List<Map<String, Object>> processWarpGateScenarioForTest(
            List<ReplayCommand.BuildCommand> builds,
            ReplayCommand.UpgradeCommand upgrade,
            int playerId, int startTag) {
        List<SyntheticEvent> events = new ArrayList<>();
        var state = new PlayerState();
        int tag = startTag;

        for (ReplayCommand.BuildCommand bc : builds) {
            tag = handleBuild(bc, playerId, state, events, tag);
        }
        tag = handleUpgrade(upgrade, playerId, state, events, tag);

        if (state.warpGateCompletionLoop > 0) {
            emitWarpGateAutoMorph(playerId, state, events, tag);
        }

        return events.stream().map(SyntheticEvent::data).toList();
    }


    List<Map<String, Object>> processTrainForTest(TrainIntent train, long commandLoop,
                                                  int playerId, int startTag,
                                                  long warpGateCompletionLoop) {
        List<SyntheticEvent> events = new ArrayList<>();
        var                  state  = new PlayerState();
        state.warpGateCompletionLoop = warpGateCompletionLoop;
        handleTrain(train, commandLoop, playerId, state, events, startTag);
        return events.stream().map(SyntheticEvent::data).toList();
    }

    List<Map<String, Object>> processZergBuildForTest(ReplayCommand.BuildCommand bc,
                                                      int playerId, int startTag) {
        List<SyntheticEvent> events = new ArrayList<>();
        var state = new PlayerState();
        handleBuild(bc, playerId, state, events, startTag);
        return events.stream().map(SyntheticEvent::data).toList();
    }

    private int getBuildTime(String buildingName) {
        BuildingType bt = BUILDING_NAME_TO_TYPE.get(buildingName);
        if (bt != null) return SC2Data.buildTimeInLoops(bt);
        Integer addonTime = ADDON_BUILD_TIMES.get(buildingName);
        if (addonTime != null) return addonTime;
        return 880; // default estimate
    }

    private Map<String, Object> buildGameJson(Replay replay, Player[] players,
                                              List<SyntheticEvent> events) {
        Map<String, Object> gameJson = new LinkedHashMap<>();

        // ToonPlayerDescMap
        Map<String, Object> toonMap = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(players.length, 2); i++) {
            Player p = players[i];
            Map<String, Object> pDesc = new LinkedHashMap<>();
            pDesc.put("playerID", i + 1);
            pDesc.put("race", p.getRace().text);
            pDesc.put("result", p.getResult() == Result.VICTORY ? "Win" : "Loss");
            toonMap.put(String.valueOf(i + 1), pDesc);
        }
        gameJson.put("ToonPlayerDescMap", toonMap);

        // trackerEvents
        List<Map<String, Object>> trackerEvents = new ArrayList<>(events.size());
        for (SyntheticEvent se : events) {
            trackerEvents.add(se.data());
        }
        gameJson.put("trackerEvents", trackerEvents);

        // header
        Integer elapsedLoops = replay.header.getElapsedGameLoops();
        gameJson.put("header", Map.of(
            "elapsedGameLoops", elapsedLoops != null ? elapsedLoops : 0));

        // metadata
        gameJson.put("metadata", Map.of(
            "mapName", replay.details.title != null ? replay.details.title : ""));

        return gameJson;
    }

    // --- Economy ---

    private static final int STATS_INTERVAL = 160;
    private static final Set<String> GAS_BUILDINGS = Set.of("Assimilator", "Refinery", "Extractor");
    private static final Set<String> SUPPLY_BUILDINGS = Set.of("Pylon", "SupplyDepot");
    private static final Set<String> BASE_BUILDINGS = Set.of(
        "Nexus", "CommandCenter", "OrbitalCommand", "PlanetaryFortress",
        "Hatchery", "Lair", "Hive"
    );
    private static final double GAS_INCOME_PER_WORKER_PER_TICK =
        38.0 / 60.0 * SC2Data.LOOPS_PER_TICK / SC2Data.GAME_LOOPS_PER_SECOND;
    private static final int GAS_WORKERS_PER_GEYSER = 3;

    private void trackTrainSpending(UnitType unitType, PlayerState state) {
        int mineralCost = SC2Data.mineralCost(unitType);
        int gasCost = SC2Data.gasCost(unitType);
        if (SC2Data.isWorker(unitType)) {
            state.mineralsUsedEconomy += mineralCost;
            state.gasUsedEconomy += gasCost;
        } else {
            state.mineralsUsedArmy += mineralCost;
            state.gasUsedArmy += gasCost;
        }
        state.mineralsCurrent -= mineralCost;
        state.vespeneCurrent -= gasCost;
        state.foodUsed += SC2Data.supplyCost(unitType) * 4096;
    }

    private void trackBuildSpending(String buildingName, PlayerState state) {
        BuildingType bt = BUILDING_NAME_TO_TYPE.get(buildingName);
        if (bt == null) return;
        int mineralCost = SC2Data.mineralCost(bt);
        int gasCost = gasCostForBuilding(bt);
        if (GAS_BUILDINGS.contains(buildingName) || BASE_BUILDINGS.contains(buildingName)
            || SUPPLY_BUILDINGS.contains(buildingName)) {
            state.mineralsUsedEconomy += mineralCost;
            state.gasUsedEconomy += gasCost;
        } else {
            state.mineralsUsedTechnology += mineralCost;
            state.gasUsedTechnology += gasCost;
        }
        state.mineralsCurrent -= mineralCost;
        state.vespeneCurrent -= gasCost;
        int supplyBonus = SC2Data.supplyBonus(bt);
        state.foodMade += supplyBonus * 4096;
    }

    private void trackUpgradeSpending(UpgradeType type, PlayerState state) {
        int mineralCost = upgradeMineralCost(type);
        int gasCost = upgradeGasCost(type);
        state.mineralsUsedTechnology += mineralCost;
        state.gasUsedTechnology += gasCost;
        state.mineralsCurrent -= mineralCost;
        state.vespeneCurrent -= gasCost;
    }

    private void generatePlayerStats(int playerId, PlayerState state,
                                     List<SyntheticEvent> allEvents, long elapsedLoops) {
        List<SyntheticEvent> playerEvents = allEvents.stream()
            .filter(e -> e.playerId() == playerId)
            .sorted(Comparator.comparingLong(SyntheticEvent::loop))
            .toList();

        int eventIdx = 0;
        long lastTickLoop = 0;

        for (long tick = STATS_INTERVAL; tick <= elapsedLoops; tick += STATS_INTERVAL) {
            while (eventIdx < playerEvents.size()
                   && playerEvents.get(eventIdx).loop() <= tick) {
                applyEventToEconomy(playerEvents.get(eventIdx), state);
                eventIdx++;
            }

            long loopsSinceLastTick = tick - lastTickLoop;
            int fullTicks = (int) (loopsSinceLastTick / SC2Data.LOOPS_PER_TICK);
            int miningWorkers = Math.max(state.workersActive - state.gasBuildingCount * GAS_WORKERS_PER_GEYSER, 0);
            for (int t = 0; t < fullTicks; t++) {
                state.mineralsCurrent += SC2Data.mineralIncomePerTick(miningWorkers);
                state.vespeneCurrent += state.gasBuildingCount * GAS_WORKERS_PER_GEYSER * GAS_INCOME_PER_WORKER_PER_TICK;
            }
            lastTickLoop = tick;

            double rateMinerals = SC2Data.mineralIncomePerTick(miningWorkers)
                * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK * 60;
            double rateVespene = state.gasBuildingCount * GAS_WORKERS_PER_GEYSER
                * GAS_INCOME_PER_WORKER_PER_TICK * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK * 60;

            // Non-food stats × 1000, food stats × 4096 — matches sc2reader_to_game_json
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("scoreValueMineralsCurrent", Math.max(0, (int) state.mineralsCurrent) * 1000);
            stats.put("scoreValueVespeneCurrent", Math.max(0, (int) state.vespeneCurrent) * 1000);
            stats.put("scoreValueMineralsCollectionRate", (int) (rateMinerals * 1000));
            stats.put("scoreValueVespeneCollectionRate", (int) (rateVespene * 1000));
            stats.put("scoreValueFoodMade", state.foodMade);
            stats.put("scoreValueFoodUsed", state.foodUsed);
            stats.put("scoreValueWorkersActiveCount", state.workersActive * 1000);
            stats.put("scoreValueMineralsUsedCurrentArmy", state.mineralsUsedArmy * 1000);
            stats.put("scoreValueMineralsUsedCurrentEconomy", state.mineralsUsedEconomy * 1000);
            stats.put("scoreValueMineralsUsedCurrentTechnology", state.mineralsUsedTechnology * 1000);
            stats.put("scoreValueVespeneUsedCurrentArmy", state.gasUsedArmy * 1000);
            stats.put("scoreValueVespeneUsedCurrentEconomy", state.gasUsedEconomy * 1000);
            stats.put("scoreValueVespeneUsedCurrentTechnology", state.gasUsedTechnology * 1000);

            allEvents.add(new SyntheticEvent(tick, EventOrdinal.PLAYER_STATS, playerId,
                Map.of("evtTypeName", "PlayerStats",
                    "loop", tick,
                    "controlPlayerId", playerId,
                    "stats", stats)));
        }
    }

    private void applyEventToEconomy(SyntheticEvent event, PlayerState state) {
        String evtType = (String) event.data().get("evtTypeName");
        String unitName = (String) event.data().get("unitTypeName");
        switch (evtType) {
            case "UnitBorn" -> {
                if ("Overlord".equals(unitName) || "Overseer".equals(unitName)) {
                    state.foodMade += 8 * 4096;
                }
                if ("Probe".equals(unitName) || "SCV".equals(unitName) || "Drone".equals(unitName)) {
                    state.workersActive++;
                }
            }
            case "UnitDone" -> {
                if (GAS_BUILDINGS.contains(unitName)) state.gasBuildingCount++;
            }
            case "UnitDied" -> {
                if ("Drone".equals(unitName)) state.workersActive--;
                if ("Overlord".equals(unitName)) state.foodMade -= 8 * 4096;
            }
            default -> {}
        }
    }

    private static int gasCostForBuilding(BuildingType bt) {
        return switch (bt) {
            case CYBERNETICS_CORE, TWILIGHT_COUNCIL, ROBOTICS_BAY,
                 FLEET_BEACON, DARK_SHRINE, TEMPLAR_ARCHIVES -> 100;
            case GHOST_ACADEMY -> 50;
            case STARGATE, SPIRE, LURKER_DEN, ULTRALISK_CAVERN,
                 GREATER_SPIRE, INFESTATION_PIT -> 200;
            case LAIR -> 100;
            case HIVE -> 150;
            default -> 0;
        };
    }

    private static int upgradeMineralCost(UpgradeType type) {
        return switch (type) {
            case STIMPACK, COMBAT_SHIELD, ZERGLING_SPEED, GLIAL_RECONSTITUTION,
                 BANSHEE_CLOAK, TERRAN_VEHICLE_WEAPONS_1, BURROW,
                 CHARGE, ADEPT_PIERCING -> 100;
            case CENTRIFUGAL_HOOKS, PERSONAL_CLOAKING, BLINK -> 150;
            case CONCUSSIVE_SHELLS, WARP_GATE_RESEARCH -> 50;
            case DRILL_CLAWS -> 75;
        };
    }

    private static int upgradeGasCost(UpgradeType type) {
        return switch (type) {
            case STIMPACK, COMBAT_SHIELD, ZERGLING_SPEED, GLIAL_RECONSTITUTION,
                 BANSHEE_CLOAK, TERRAN_VEHICLE_WEAPONS_1, BURROW,
                 CHARGE, ADEPT_PIERCING -> 100;
            case CENTRIFUGAL_HOOKS, PERSONAL_CLOAKING, BLINK -> 150;
            case CONCUSSIVE_SHELLS, WARP_GATE_RESEARCH -> 50;
            case DRILL_CLAWS -> 75;
        };
    }

    static String buildingTypeToPythonName(BuildingType bt) {
        return switch (bt) {
            case COMMAND_CENTER -> "CommandCenter";
            case ORBITAL_COMMAND -> "OrbitalCommand";
            case PLANETARY_FORTRESS -> "PlanetaryFortress";
            case BARRACKS -> "Barracks";
            case FACTORY -> "Factory";
            case STARPORT -> "Starport";
            case ENGINEERING_BAY -> "EngineeringBay";
            case ARMORY -> "Armory";
            case GHOST_ACADEMY -> "GhostAcademy";
            case FUSION_CORE -> "FusionCore";
            case BUNKER -> "Bunker";
            case MISSILE_TURRET -> "MissileTurret";
            case SENSOR_TOWER -> "SensorTower";
            case SUPPLY_DEPOT -> "SupplyDepot";
            case REFINERY -> "Refinery";
            case HATCHERY -> "Hatchery";
            case LAIR -> "Lair";
            case HIVE -> "Hive";
            case SPAWNING_POOL -> "SpawningPool";
            case BANELING_NEST -> "BanelingNest";
            case ROACH_WARREN -> "RoachWarren";
            case EVOLUTION_CHAMBER -> "EvolutionChamber";
            case EXTRACTOR -> "Extractor";
            case HYDRALISK_DEN -> "HydraliskDen";
            case SPINE_CRAWLER -> "SpineCrawler";
            case SPORE_CRAWLER -> "SporeCrawler";
            case SPIRE -> "Spire";
            case GREATER_SPIRE -> "GreaterSpire";
            case INFESTATION_PIT -> "InfestationPit";
            case NYDUS_NETWORK -> "NydusNetwork";
            case ULTRALISK_CAVERN -> "UltraliskCavern";
            case NEXUS -> "Nexus";
            case GATEWAY -> "Gateway";
            case CYBERNETICS_CORE -> "CyberneticsCore";
            case FORGE -> "Forge";
            case ASSIMILATOR -> "Assimilator";
            case PYLON -> "Pylon";
            case PHOTON_CANNON -> "PhotonCannon";
            case SHIELD_BATTERY -> "ShieldBattery";
            case ROBOTICS_FACILITY -> "RoboticsFacility";
            case ROBOTICS_BAY -> "RoboticsBay";
            case STARGATE -> "Stargate";
            case FLEET_BEACON -> "FleetBeacon";
            case TWILIGHT_COUNCIL -> "TwilightCouncil";
            case TEMPLAR_ARCHIVES -> "TemplarArchive";
            case DARK_SHRINE -> "DarkShrine";
            default -> null;
        };
    }

    private enum EventOrdinal {
        UNIT_INIT, UNIT_BORN, UNIT_DONE, UNIT_DIED, UPGRADE, PLAYER_STATS;
    }

    private record SyntheticEvent(long loop, EventOrdinal ordinal, int playerId,
                                  Map<String, Object> data) {}

    private record TrackedBuilding(int tag, String name, long doneLoop) {}

    private static class PlayerState {
        final Map<String, Long> buildingBusyUntil = new HashMap<>();
        final List<TrackedBuilding> trackedBuildings = new ArrayList<>();
        final Map<Integer, Integer> productionBuildingCounts = new HashMap<>();
        long warpGateCompletionLoop = -1;

        // Economy — Tier 1: cumulative spending
        int mineralsUsedArmy;
        int gasUsedArmy;
        int mineralsUsedEconomy;
        int gasUsedEconomy;
        int mineralsUsedTechnology;
        int gasUsedTechnology;
        int foodMade = SC2Data.INITIAL_SUPPLY * 4096;
        int foodUsed = SC2Data.INITIAL_SUPPLY_USED * 4096;

        // Tier 2/3: state tracking
        double mineralsCurrent = SC2Data.INITIAL_MINERALS;
        double vespeneCurrent = SC2Data.INITIAL_VESPENE;
        int workersActive = SC2Data.INITIAL_PROBES;
        int gasBuildingCount;
    }

    static final class SelectionUnitLinkTracker {
        private final ArrayList<Integer> unitLinks = new ArrayList<>();
        private final int                userId;

        SelectionUnitLinkTracker(int userId) {this.userId = userId;}

        void onSelection(SelectionDeltaEvent sel) {
            if (sel.getUserId() != userId) {return;}
            var delta = sel.getDelta();
            if (delta == null) {
                unitLinks.clear();
                return;
            }

            var    removeMask = delta.getRemoveMask();
            String variant    = removeMask != null ? removeMask.value1 : null;
            if ("ZeroIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                var kept = new ArrayList<Integer>();
                for (int idx : indices) {
                    if (idx >= 0 && idx < unitLinks.size()) {kept.add(unitLinks.get(idx));}
                }
                unitLinks.clear();
                unitLinks.addAll(kept);
            } else if ("OneIndices".equals(variant) && removeMask.value2 instanceof Integer[] indices) {
                for (int i = indices.length - 1; i >= 0; i--) {
                    int idx = indices[i];
                    if (idx >= 0 && idx < unitLinks.size()) {unitLinks.remove(idx);}
                }
            } else if ("Mask".equals(variant) && removeMask.value2 instanceof hu.belicza.andras.util.type.BitArray bitArray) {
                for (int i = unitLinks.size() - 1; i >= 0; i--) {
                    if (i < bitArray.getCount() && bitArray.getBit(i)) {unitLinks.remove(i);}
                }
            } else if (variant != null && !"None".equals(variant)) {
                unitLinks.clear();
            }

            var subgroups = delta.getAddSubgroups();
            if (subgroups != null) {
                for (var sg : subgroups) {
                    Integer link  = sg.getUnitLink();
                    Integer count = sg.getCount();
                    if (link != null && count != null) {
                        for (int i = 0; i < count; i++) {unitLinks.add(link);}
                    }
                }
            }
        }

        int countMatching(Set<Integer> validLinks) {
            int count = 0;
            for (Integer link : unitLinks) {
                if (validLinks.contains(link)) {count++;}
            }
            return count;
        }
    }
}
