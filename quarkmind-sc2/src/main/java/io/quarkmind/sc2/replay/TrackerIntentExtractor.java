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
import io.quarkmind.sc2.intent.AbilityIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.intent.UnitDiedIntent;
import io.quarkmind.sc2.mock.Sc2ReplayShared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

        Map<Long, UnitType> unitTagToType = new HashMap<>();

        for (Event raw : replay.trackerEvents.getEvents()) {
            int id = raw.getId();
            switch (id) {
                case ITrackerEvents.ID_UNIT_BORN -> {
                    if (!(raw instanceof IBaseUnitEvent ue)) {continue;}
                    Integer pid = ue.getControlPlayerId();
                    if (pid == null || pid != playerId) {continue;}
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) {continue;}

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
                        consumeDroneForBuilding(unitTagToType, intents, ue.getLoop(), typeName);
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

        intents.sort(Comparator.comparingLong(TimedIntent::loop));
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

    private static final Set<String> NON_DRONE_BUILDINGS = Set.of(
        "Lair", "Hive", "GreaterSpire", "NydusCanal",
        "SpineCrawlerUprooted", "SporeCrawlerUprooted"
    );

    private static void consumeDroneForBuilding(Map<Long, UnitType> unitTagToType,
                                                 List<TimedIntent> intents, int loop,
                                                 String buildingTypeName) {
        if (NON_DRONE_BUILDINGS.contains(buildingTypeName)) return;
        Long droneTag = null;
        for (Map.Entry<Long, UnitType> entry : unitTagToType.entrySet()) {
            if (entry.getValue() == UnitType.DRONE) {
                droneTag = entry.getKey();
                break;
            }
        }
        if (droneTag == null) return;
        unitTagToType.remove(droneTag);
        int tagIndex = (int) (droneTag >>> 32);
        int tagRecycle = (int) (droneTag & 0xFFFFFFFFL);
        intents.add(new TimedIntent(loop, new UnitDiedIntent("r-" + tagIndex + "-" + tagRecycle, UnitType.DRONE)));
    }
}
