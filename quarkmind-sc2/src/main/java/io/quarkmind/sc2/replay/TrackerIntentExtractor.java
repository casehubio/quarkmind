package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.SelectionState;
import io.quarkmind.domain.Point2d;
import io.quarkmind.sc2.intent.AbilityIntent;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.BuildingBornIntent;
import io.quarkmind.sc2.intent.BuildingDiedIntent;
import io.quarkmind.sc2.intent.Intent;
import io.quarkmind.sc2.intent.MorphIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.intent.UnitDiedIntent;
import io.quarkmind.sc2.mock.Sc2ReplayShared;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class TrackerIntentExtractor {

    private static final int ABIL_CHRONO_BOOST = 716;

    private TrackerIntentExtractor() {}

    public static List<TimedIntent> extract(Path replayPath, int playerId) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(replayPath,
                EnumSet.of(RepContent.TRACKER_EVENTS, RepContent.DETAILS, RepContent.GAME_EVENTS));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse replay: " + replayPath, e);
        }
        if (replay == null || replay.trackerEvents == null) {
            throw new IllegalArgumentException("No tracker events in replay: " + replayPath);
        }

        return extractFromReplay(replay, playerId);
    }

    static List<TimedIntent> extractFromReplay(Replay replay, int playerId) {
        List<TimedIntent> intents = new ArrayList<>();

        int userId = playerId - 1;
        Map<String, BuildingType> buildingTypeMap = buildBuildingTypeMap(
                replay.trackerEvents.getEvents(), playerId);

        TreeMap<Integer, List<String>> buildingIndex =
                replay.gameEvents != null
                ? buildBuildingIndex(replay.gameEvents.getEvents(), userId)
                : new TreeMap<>();

        int warpGateResearchLoop = findWarpGateResearchLoop(replay.trackerEvents.getEvents(), playerId);

        Map<Long, UnitType>     unitTagToType     = new HashMap<>();
        Map<Long, BuildingType> buildingTagToType = new HashMap<>();

        // Pre-pass: collect UnitDone loops for buildings (pinned completion).
        // A building that morphs (CC→OC, Hatchery→Lair) has multiple UnitDone events:
        // one for initial construction and one for each morph. Store all in order
        // so each consumer (UnitInit for construction, UnitInit for morph) gets its own.
        Map<Long, Deque<Integer>> buildingDoneLoops = new HashMap<>();
        for (Event raw : replay.trackerEvents.getEvents()) {
            if (raw.getId() == ITrackerEvents.ID_UNIT_DONE) {
                Integer tagIndex   = raw.get("unitTagIndex");
                Integer tagRecycle = raw.get("unitTagRecycle");
                if (tagIndex != null && tagRecycle != null) {
                    long compositeTag = ((long) tagIndex << 32) | (tagRecycle & 0xFFFFFFFFL);
                    buildingDoneLoops.computeIfAbsent(compositeTag, k -> new ArrayDeque<>()).add(raw.getLoop());
                }
            }
        }

        for (Event raw : replay.trackerEvents.getEvents()) {
            int id = raw.getId();
            switch (id) {
                case ITrackerEvents.ID_UNIT_BORN -> {
                    if (!(raw instanceof IBaseUnitEvent ue)) {continue;}
                    Integer pid = ue.getControlPlayerId();
                    if (pid == null || pid != playerId) {continue;}
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) {
                        long         compositeTag = ((long) ue.getUnitTagIndex() << 32) | (ue.getUnitTagRecycle() & 0xFFFFFFFFL);
                        BuildingType bt           = Sc2ReplayShared.toBuildingType(typeName);
                        buildingTagToType.put(compositeTag, bt);
                        String  tag = "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                        Point2d pos = new Point2d(ue.getXCoord(), ue.getYCoord());
                        intents.add(new TimedIntent(ue.getLoop(), new BuildingBornIntent(tag, bt, pos)));
                        continue;
                    }

                    UnitType unitType = Sc2ReplayShared.toUnitType(typeName);
                    if (unitType != UnitType.UNKNOWN) {
                        long compositeTag = ((long) ue.getUnitTagIndex() << 32) | (ue.getUnitTagRecycle() & 0xFFFFFFFFL);
                        unitTagToType.put(compositeTag, unitType);
                    }
                    if (unitType == UnitType.UNKNOWN) {continue;}
                    if (unitType == UnitType.LARVA) {continue;}
                    if (unitType == UnitType.MULE) {continue;}

                    int completionLoop = ue.getLoop();
                    int trainTime      = SC2Data.trainTimeInLoops(unitType);
                    int commandLoop    = Math.max(0, completionLoop - trainTime);

                    String resolved = lookupBuildingTag(
                            buildingIndex, buildingTypeMap, commandLoop, unitType);
                    String bldgTag = resolved != null ? resolved
                                                      : "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                    String unitTag = "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                    intents.add(new TimedIntent(commandLoop,
                                                new TrainIntent(bldgTag, unitType, true, false, completionLoop, unitTag)));
                }
                case ITrackerEvents.ID_UNIT_INIT -> {
                    if (!(raw instanceof IBaseUnitEvent ue)) {continue;}
                    Integer pid = ue.getControlPlayerId();
                    if (pid == null || pid != playerId) {continue;}
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) {
                        long         compositeTag = ((long) ue.getUnitTagIndex() << 32) | (ue.getUnitTagRecycle() & 0xFFFFFFFFL);
                        BuildingType bt           = Sc2ReplayShared.toBuildingType(typeName);
                        String       tag          = "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                        Point2d      pos          = new Point2d(ue.getXCoord(), ue.getYCoord());
                        Deque<Integer> doneQueue   = buildingDoneLoops.get(compositeTag);
                        int          doneLoop     = (doneQueue != null && !doneQueue.isEmpty()) ? doneQueue.pollFirst() : -1;

                        if (MORPH_BUILDING_SOURCES.containsKey(typeName)) {
                            BuildingType sourceType = buildingTagToType.getOrDefault(compositeTag, MORPH_BUILDING_SOURCES.get(typeName));
                            intents.add(new TimedIntent(ue.getLoop(),
                                                        new MorphIntent(tag, sourceType.name(), typeName, doneLoop)));
                        } else {
                            intents.add(new TimedIntent(ue.getLoop(),
                                                        new BuildIntent("worker", bt, pos, tag, doneLoop)));
                        }
                        buildingTagToType.put(compositeTag, bt);
                        continue;
                    }

                    UnitType unitType = Sc2ReplayShared.toUnitType(typeName);
                    if (unitType != UnitType.UNKNOWN) {
                        long compositeTag = ((long) ue.getUnitTagIndex() << 32) | (ue.getUnitTagRecycle() & 0xFFFFFFFFL);
                        unitTagToType.put(compositeTag, unitType);
                    }
                    if (unitType == UnitType.UNKNOWN) {continue;}
                    if (!SC2Data.isGatewayUnit(unitType)) {continue;}
                    if (warpGateResearchLoop < 0 || ue.getLoop() < warpGateResearchLoop) {continue;}

                    int commandLoop = ue.getLoop();
                    String resolved = lookupBuildingTag(
                            buildingIndex, buildingTypeMap, commandLoop, unitType);
                    String bldgTag = resolved != null ? resolved
                                                      : "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                    String unitTag = "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                    intents.add(new TimedIntent(commandLoop,
                                                new TrainIntent(bldgTag, unitType, true, true, -1, unitTag)));
                }
                case ITrackerEvents.ID_UNIT_DONE -> {}
                case ITrackerEvents.ID_UNIT_TYPE_CHANGE -> {
                    Integer tagIndex   = raw.get("unitTagIndex");
                    Integer tagRecycle = raw.get("unitTagRecycle");
                    if (tagIndex == null || tagRecycle == null) {continue;}
                    long   compositeTag = ((long) tagIndex << 32) | (tagRecycle & 0xFFFFFFFFL);
                    Object newTypeObj   = raw.get("unitTypeName");
                    if (newTypeObj == null) {continue;}
                    String newTypeName = String.valueOf(newTypeObj).trim();

                    // Building type change (morph)
                    BuildingType oldBt = buildingTagToType.get(compositeTag);
                    if (oldBt != null) {
                        BuildingType newBt = Sc2ReplayShared.toBuildingType(newTypeName);
                        if (newBt != BuildingType.UNKNOWN && newBt != oldBt) {
                            buildingTagToType.put(compositeTag, newBt);
                            String tag = "r-" + tagIndex + "-" + tagRecycle;
                            intents.add(new TimedIntent(raw.getLoop(),
                                                        MorphIntent.of(tag, oldBt.name(), newTypeName)));
                        }
                        continue;
                    }

                    // Unit type change (morph or mode switch)
                    if (!unitTagToType.containsKey(compositeTag)) {continue;}
                    UnitType newType = Sc2ReplayShared.toUnitType(newTypeName);
                    if (newType != UnitType.UNKNOWN) {
                        UnitType oldType = unitTagToType.put(compositeTag, newType);
                        if (oldType != null && newType != oldType) {
                            String unitTag = "r-" + tagIndex + "-" + tagRecycle;
                            intents.add(new TimedIntent(raw.getLoop(),
                                                        MorphIntent.of(unitTag, oldType.name(), newTypeName)));
                        }
                    }
                }
                case ITrackerEvents.ID_UPGRADE -> {
                    if (!(raw instanceof IUpgradeEvent up)) {continue;}
                    if (up.getPlayerId() == null || up.getPlayerId() != playerId) {continue;}
                    String      upgradeName = String.valueOf(up.getUpgradeTypeName()).trim();
                    UpgradeType ut          = UpgradeType.fromTrackerName(upgradeName);
                    if (ut == null) {continue;}

                    int completionLoop = up.getLoop();
                    int upgradeTime    = SC2Data.upgradeTimeInLoops(ut);
                    int commandLoop    = Math.max(0, completionLoop - upgradeTime);

                    String resolvedBuilding = lookupBuildingTagSimple(
                            buildingIndex, commandLoop);
                    intents.add(new TimedIntent(commandLoop,
                                                new ResearchIntent(resolvedBuilding != null ? resolvedBuilding : "r-tracker",
                                                                   ut, commandLoop, true)));
                }
                case ITrackerEvents.ID_UNIT_DIED -> {
                    Integer tagIndex   = raw.get("unitTagIndex");
                    Integer tagRecycle = raw.get("unitTagRecycle");
                    if (tagIndex == null || tagRecycle == null) {continue;}
                    long compositeTag = ((long) tagIndex << 32) | (tagRecycle & 0xFFFFFFFFL);

                    // Building death
                    BuildingType deadBt = buildingTagToType.remove(compositeTag);
                    if (deadBt != null) {
                        String tag = "r-" + tagIndex + "-" + tagRecycle;
                        intents.add(new TimedIntent(raw.getLoop(), new BuildingDiedIntent(tag, deadBt)));
                        continue;
                    }

                    // Unit death
                    UnitType deadType = unitTagToType.remove(compositeTag);
                    if (deadType == null) {continue;}
                    String tag = "r-" + tagIndex + "-" + tagRecycle;
                    intents.add(new TimedIntent(raw.getLoop(),
                                                new UnitDiedIntent(tag, deadType)));
                }
                default -> {}
            }
        }

        if (replay.gameEvents != null) {
            for (Event ge : replay.gameEvents.getEvents()) {
                if (!(ge instanceof CmdEvent cmd)) {continue;}
                Integer cmdUser = cmd.getUserId();
                if (cmdUser == null || cmdUser != userId) {continue;}
                Integer abl = cmd.getAbilLink();
                if (abl == null || abl != ABIL_CHRONO_BOOST) {continue;}
                var tu = cmd.getTargetUnit();
                if (tu == null || tu.getTag() == null) {continue;}
                String targetTag = GameEventStream.decodeTag(tu.getTag());
                String resolvedCaster = lookupBuildingTagSimple(
                        buildingIndex, cmd.getLoop());
                intents.add(new TimedIntent(cmd.getLoop(),
                                            new AbilityIntent(resolvedCaster != null ? resolvedCaster : "r-NEXUS",
                                                              "CHRONO_BOOST", targetTag)));
            }
        }

        intents.sort(Comparator.comparingLong(TimedIntent::loop)
                               .thenComparingInt(ti -> intentPriority(ti.intent())));
        return Collections.unmodifiableList(intents);
    }

    public static Race resolvePlayerRace(Replay replay, int playerId) {
        if (replay.details != null) {
            var players = replay.details.getPlayerList();
            if (players.length >= playerId) {
                var scRace = players[playerId - 1].getRace();
                if (scRace == hu.scelight.sc2.rep.model.details.Race.TERRAN) return Race.TERRAN;
                if (scRace == hu.scelight.sc2.rep.model.details.Race.ZERG) return Race.ZERG;
            }
        }
        return Race.PROTOSS;
    }

    static int findWarpGateResearchLoop(Event[] trackerEvents, int playerId) {
        for (Event raw : trackerEvents) {
            if (raw.getId() != ITrackerEvents.ID_UPGRADE) {continue;}
            if (!(raw instanceof IUpgradeEvent up)) {continue;}
            if (up.getPlayerId() == null || up.getPlayerId() != playerId) {continue;}
            String name = String.valueOf(up.getUpgradeTypeName()).trim();
            if ("WarpGateResearch".equals(name)) {
                return up.getLoop();
            }
        }
        return -1;
    }


    private static Map<String, BuildingType> buildBuildingTypeMap(Event[] trackerEvents, int playerId) {
        Map<String, BuildingType> map = new HashMap<>();
        for (Event raw : trackerEvents) {
            int eid = raw.getId();
            if ((eid == ITrackerEvents.ID_UNIT_INIT || eid == ITrackerEvents.ID_UNIT_BORN)
                && raw instanceof IBaseUnitEvent ue) {
                Integer pid = ue.getControlPlayerId();
                if (pid != null && pid == playerId) {
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) {
                        String tag = "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                        map.put(tag, Sc2ReplayShared.toBuildingType(typeName));
                    }
                }
            }
        }
        return map;
    }

    private static TreeMap<Integer, List<String>> buildBuildingIndex(Event[] gameEvents, int userId) {
        TreeMap<Integer, List<String>> index     = new TreeMap<>();
        SelectionState                 selection = new SelectionState();

        for (Event raw : gameEvents) {
            if (raw instanceof SelectionDeltaEvent sel) {
                Integer selUser = sel.getUserId();
                if (selUser == null || selUser != userId) {continue;}
                var delta = sel.getDelta();
                if (delta == null) {
                    selection.clear();
                    continue;
                }

                var    removeMask = delta.getRemoveMask();
                String variant    = removeMask != null ? removeMask.value1 : null;

                if (variant == null || "None".equals(variant)) {
                    // carry forward
                } else if ("ZeroIndices".equals(variant)) {
                    if (removeMask.value2 instanceof Integer[] indices) {
                        int[] prims = new int[indices.length];
                        for (int i = 0; i < indices.length; i++) {prims[i] = indices[i];}
                        selection.retainOnly(prims);
                    } else {
                        selection.clear();
                    }
                } else if ("Mask".equals(variant)) {
                    if (removeMask.value2 instanceof hu.belicza.andras.util.type.BitArray bitArray) {
                        selection.removeIf(i -> i < bitArray.getCount() && bitArray.getBit(i));
                    } else {
                        selection.clear();
                    }
                } else if ("OneIndices".equals(variant)) {
                    if (removeMask.value2 instanceof Integer[] indices) {
                        for (int i = indices.length - 1; i >= 0; i--) {
                            selection.removeAt(indices[i]);
                        }
                    } else {
                        selection.clear();
                    }
                } else {
                    selection.clear();
                }

                var addTags = delta.getAddUnitTags();
                if (addTags != null) {
                    for (Integer rawTag : addTags) {
                        if (rawTag != null) {
                            selection.addTag(GameEventStream.decodeTag(rawTag));
                        }
                    }
                }
            } else if (raw instanceof CmdEvent cmd) {
                Integer cmdUser = cmd.getUserId();
                if (cmdUser == null || cmdUser != userId) {continue;}
                if (!selection.isEmpty()) {
                    index.put(cmd.getLoop(), selection.snapshot());
                }
            }
        }

        return index;
    }

    static String lookupBuildingTag(TreeMap<Integer, List<String>> index,
                                    Map<String, BuildingType> tagToType,
                                    int commandLoop, UnitType unitType) {
        BuildingType                     required = SC2Data.trainedBy(unitType);
        Map.Entry<Integer, List<String>> entry    = index.floorEntry(commandLoop + 5);
        if (entry != null && commandLoop - entry.getKey() <= 5) {
            if (required == BuildingType.UNKNOWN) {
                return entry.getValue().get(0);
            }
            for (String tag : entry.getValue()) {
                BuildingType bt = tagToType.get(tag);
                if (bt != null && SC2Data.canTrainFrom(bt, required)) {
                    return tag;
                }
            }
            return null;
        }
        return null;
    }

    private static String lookupBuildingTagSimple(TreeMap<Integer, List<String>> index, int commandLoop) {
        Map.Entry<Integer, List<String>> entry = index.floorEntry(commandLoop + 5);
        if (entry != null && commandLoop - entry.getKey() <= 5) {
            return entry.getValue().get(0);
        }
        return null;
    }

    private static final Map<String, BuildingType> MORPH_BUILDING_SOURCES = Map.of(
            "Lair", BuildingType.HATCHERY,
            "Hive", BuildingType.LAIR,
            "GreaterSpire", BuildingType.SPIRE,
            "OrbitalCommand", BuildingType.COMMAND_CENTER,
            "PlanetaryFortress", BuildingType.COMMAND_CENTER
                                                                                  );

    private static int intentPriority(Intent intent) {
        return switch (intent) {
            case BuildingBornIntent ignored -> 0;
            case BuildingDiedIntent ignored -> 1;
            case BuildIntent ignored -> 2;
            case MorphIntent ignored -> 3;
            case TrainIntent ignored -> 5;
            case ResearchIntent ignored -> 5;
            case AbilityIntent ignored -> 5;
            case UnitDiedIntent ignored -> 8;
            default -> 10;
        };
    }
}
