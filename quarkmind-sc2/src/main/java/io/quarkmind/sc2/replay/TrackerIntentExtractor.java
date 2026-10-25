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
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.domain.UpgradeType;
import io.quarkmind.sc2.intent.AbilityIntent;
import io.quarkmind.sc2.intent.ResearchIntent;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import io.quarkmind.sc2.SelectionState;
import io.quarkmind.sc2.mock.Sc2ReplayShared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
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
        TreeMap<Integer, String> buildingIndex =
            replay.gameEvents != null
                ? buildBuildingIndex(replay.gameEvents.getEvents(), userId)
                : new TreeMap<>();

        for (Event raw : replay.trackerEvents.getEvents()) {
            int id = raw.getId();
            switch (id) {
                case ITrackerEvents.ID_UNIT_BORN -> {
                    if (!(raw instanceof IBaseUnitEvent ue)) continue;
                    Integer pid = ue.getControlPlayerId();
                    if (pid == null || pid != playerId) continue;
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    if (Sc2ReplayShared.BUILDING_NAMES.contains(typeName)) continue;

                    UnitType unitType = Sc2ReplayShared.toUnitType(typeName);
                    if (unitType == UnitType.UNKNOWN) continue;
                    if (unitType == UnitType.LARVA) continue;
                    if (unitType == UnitType.MULE) continue;

                    int completionLoop = ue.getLoop();
                    int trainTime = SC2Data.trainTimeInLoops(unitType);
                    int commandLoop = Math.max(0, completionLoop - trainTime);

                    String resolved = lookupBuildingTag(buildingIndex, commandLoop);
                    String tag = resolved != null ? resolved
                        : "r-" + ue.getUnitTagIndex() + "-" + ue.getUnitTagRecycle();
                    intents.add(new TimedIntent(commandLoop,
                        new TrainIntent(tag, unitType)));
                }
                // Buildings are NOT converted to BuildIntents — the harness syncs them
                // from ground truth via syncBuildings(). Tracker events lack positions,
                // and generating BuildIntents would duplicate what the harness already injects.
                case ITrackerEvents.ID_UNIT_INIT, ITrackerEvents.ID_UNIT_DONE -> {}
                case ITrackerEvents.ID_UPGRADE -> {
                    if (!(raw instanceof IUpgradeEvent up)) continue;
                    if (up.getPlayerId() == null || up.getPlayerId() != playerId) continue;
                    String upgradeName = String.valueOf(up.getUpgradeTypeName()).trim();
                    UpgradeType ut = UpgradeType.fromTrackerName(upgradeName);
                    if (ut == null) continue;

                    int completionLoop = up.getLoop();
                    int upgradeTime = SC2Data.upgradeTimeInLoops(ut);
                    int commandLoop = Math.max(0, completionLoop - upgradeTime);

                    String resolvedBuilding = lookupBuildingTag(buildingIndex, commandLoop);
                    intents.add(new TimedIntent(commandLoop,
                        new ResearchIntent(resolvedBuilding != null ? resolvedBuilding : "r-tracker",
                            ut, commandLoop)));
                }
                default -> {}
            }
        }

        if (replay.gameEvents != null) {
            for (Event ge : replay.gameEvents.getEvents()) {
                if (!(ge instanceof CmdEvent cmd)) continue;
                Integer cmdUser = cmd.getUserId();
                if (cmdUser == null || cmdUser != userId) continue;
                Integer abl = cmd.getAbilLink();
                if (abl == null || abl != ABIL_CHRONO_BOOST) continue;
                var tu = cmd.getTargetUnit();
                if (tu == null || tu.getTag() == null) continue;
                String targetTag = GameEventStream.decodeTag(tu.getTag());
                String resolvedCaster = lookupBuildingTag(buildingIndex, cmd.getLoop());
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

    private static TreeMap<Integer, String> buildBuildingIndex(Event[] gameEvents, int userId) {
        TreeMap<Integer, String> index     = new TreeMap<>();
        SelectionState           selection = new SelectionState();

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
                    index.put(cmd.getLoop(), selection.first());
                }
            }
        }

        return index;
    }

    private static String lookupBuildingTag(TreeMap<Integer, String> index, int commandLoop) {
        Map.Entry<Integer, String> entry = index.floorEntry(commandLoop + 5);
        if (entry != null && commandLoop - entry.getKey() <= 5) {
            return entry.getValue();
        }
        return null;
    }

}
