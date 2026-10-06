package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.details.Player;
import hu.scelight.sc2.rep.model.details.Race;
import hu.scelight.sc2.rep.model.details.Result;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.sc2.intent.TrainIntent;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.IPlayerStatsEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import hu.scelightapi.sc2.rep.model.trackerevents.IUpgradeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class TrackerEventFeatureExtractor {

    private static final String[] UNIT_EVENT_NAMES;
    static {
        UNIT_EVENT_NAMES = new String[8];
        UNIT_EVENT_NAMES[ITrackerEvents.ID_UNIT_BORN] = "UnitBorn";
        UNIT_EVENT_NAMES[ITrackerEvents.ID_UNIT_DIED] = "UnitDied";
        UNIT_EVENT_NAMES[ITrackerEvents.ID_UNIT_INIT] = "UnitInit";
        UNIT_EVENT_NAMES[ITrackerEvents.ID_UNIT_DONE] = "UnitDone";
    }

    Map<String, Object> extract(Replay replay) {
        if (replay.trackerEvents == null) {
            throw new IllegalArgumentException("Replay has no tracker events");
        }

        Player[] players = replay.details.getPlayerList();
        if (players.length < 2) {
            throw new IllegalArgumentException("Need at least 2 players");
        }

        List<Map<String, Object>> trackerEvents = new ArrayList<>();
        record UnitInfo(int pid, String typeName) {}
        Map<Long, UnitInfo> tagToUnit = new HashMap<>();

        for (Event raw : replay.trackerEvents.getEvents()) {
            int id = raw.getId();
            switch (id) {
                case ITrackerEvents.ID_UNIT_BORN,
                     ITrackerEvents.ID_UNIT_INIT -> {
                    if (!(raw instanceof IBaseUnitEvent ue)) continue;
                    Integer pid = ue.getControlPlayerId();
                    if (pid == null || pid == 0) continue;
                    int tagIdx = ue.getUnitTagIndex() != null ? ue.getUnitTagIndex() : 0;
                    int tagRec = ue.getUnitTagRecycle() != null ? ue.getUnitTagRecycle() : 0;
                    String typeName = String.valueOf(ue.getUnitTypeName()).trim();
                    long compositeTag = ((long) tagIdx << 32) | (tagRec & 0xFFFFFFFFL);
                    tagToUnit.put(compositeTag, new UnitInfo(pid, typeName));
                    trackerEvents.add(Map.of(
                        "evtTypeName", UNIT_EVENT_NAMES[id],
                        "loop", (long) ue.getLoop(),
                        "controlPlayerId", (int) pid,
                        "unitTypeName", typeName,
                        "unitTagIndex", tagIdx,
                        "unitTagRecycle", tagRec
                    ));
                }
                case ITrackerEvents.ID_UNIT_DONE,
                     ITrackerEvents.ID_UNIT_DIED -> {
                    Integer tagIdx = raw.get("unitTagIndex");
                    Integer tagRec = raw.get("unitTagRecycle");
                    if (tagIdx == null || tagRec == null) continue;
                    long compositeTag = ((long) tagIdx << 32) | (tagRec & 0xFFFFFFFFL);
                    UnitInfo info = tagToUnit.get(compositeTag);
                    if (info == null) continue;
                    trackerEvents.add(Map.of(
                        "evtTypeName", UNIT_EVENT_NAMES[id],
                        "loop", (long) raw.getLoop(),
                        "controlPlayerId", info.pid,
                        "unitTypeName", info.typeName,
                        "unitTagIndex", (int) tagIdx,
                        "unitTagRecycle", (int) tagRec
                    ));
                }
                case ITrackerEvents.ID_UPGRADE -> {
                    if (!(raw instanceof IUpgradeEvent up)) continue;
                    if (up.getPlayerId() == null) continue;
                    trackerEvents.add(Map.of(
                        "evtTypeName", "Upgrade",
                        "loop", (long) up.getLoop(),
                        "playerId", (int) up.getPlayerId(),
                        "upgradeTypeName", String.valueOf(up.getUpgradeTypeName()).trim()
                    ));
                }
                case ITrackerEvents.ID_PLAYER_STATS -> {
                    if (!(raw instanceof IPlayerStatsEvent ps)) continue;
                    if (ps.getPlayerId() == null || ps.getPlayerId() == 0) continue;
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("scoreValueMineralsCurrent", safe(ps.getMineralsCurrent()) * 1000);
                    stats.put("scoreValueVespeneCurrent", safe(ps.getGasCurrent()) * 1000);
                    stats.put("scoreValueMineralsCollectionRate", safe(ps.getMinsCollRate()) * 1000);
                    stats.put("scoreValueVespeneCollectionRate", safe(ps.getGasCollRate()) * 1000);
                    stats.put("scoreValueFoodMade", safe(ps.getFoodMade()));
                    stats.put("scoreValueFoodUsed", safe(ps.getFoodUsed()));
                    stats.put("scoreValueWorkersActiveCount", safe(ps.getWorkersActiveCount()) * 1000);
                    stats.put("scoreValueMineralsUsedCurrentArmy", safe(ps.getMinsUsedInCurrentArmy()) * 1000);
                    stats.put("scoreValueMineralsUsedCurrentEconomy", safe(ps.getMinsUsedInCurrentEcon()) * 1000);
                    stats.put("scoreValueMineralsUsedCurrentTechnology", safe(ps.getMinsUsedInCurrentTech()) * 1000);
                    stats.put("scoreValueVespeneUsedCurrentArmy", safe(ps.getGasUsedInCurrentArmy()) * 1000);
                    stats.put("scoreValueVespeneUsedCurrentEconomy", safe(ps.getGasUsedInCurrentEcon()) * 1000);
                    stats.put("scoreValueVespeneUsedCurrentTechnology", safe(ps.getGasUsedInCurrentTech()) * 1000);
                    trackerEvents.add(Map.of(
                        "evtTypeName", "PlayerStats",
                        "loop", (long) ps.getLoop(),
                        "controlPlayerId", (int) ps.getPlayerId(),
                        "stats", stats
                    ));
                }
                default -> {}
            }
        }

        trackerEvents.sort(Comparator.comparingLong(e -> ((Number) e.get("loop")).longValue()));

        return buildGameJson(replay, players, trackerEvents);
    }

    Map<String, Object> extractWithCommands(Replay replay) {
        Map<String, Object> result = extract(replay);

        if (replay.gameEvents == null) {
            result.put("gameCommands", List.of());
            return result;
        }

        Player[] players = replay.details.getPlayerList();
        List<Map<String, Object>> commands = new ArrayList<>();
        Event[] gameEvents = replay.gameEvents.getEvents();

        Map<Integer, Integer> cmdCounts = new HashMap<>();
        for (Event e : gameEvents) {
            if (e instanceof CmdEvent cmd && cmd.getUserId() >= 0) {
                cmdCounts.merge(cmd.getUserId(), 1, Integer::sum);
            }
        }
        List<Integer> sorted = cmdCounts.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey).toList();
        int[] userIds = sorted.size() >= 2
            ? new int[]{Math.min(sorted.get(0), sorted.get(1)), Math.max(sorted.get(0), sorted.get(1))}
            : new int[]{0, 1};

        AbilityProfile profile = AbilityProfile.resolve(
            replay.header != null && replay.header.baseBuild != null ? replay.header.baseBuild : 75689);

        for (int playerId = 1; playerId <= 2; playerId++) {
            int pi = playerId - 1;
            Race race = players[pi].getRace();
            int userId = userIds[pi];
            AbilityMapping mapping = new AbilityMapping(userId + 1, true, race, profile);
            int pid = playerId;

            for (Event raw : gameEvents) {
                if (raw instanceof SelectionDeltaEvent sel) {
                    mapping.onSelection(sel);
                } else if (raw instanceof CmdEvent cmd) {
                    for (ReplayCommand rc : mapping.process(cmd)) {
                        switch (rc) {
                            case ReplayCommand.IntentCommand ic -> {
                                var ti = ic.intent();
                                if (ti.intent() instanceof TrainIntent train) {
                                    var m = new LinkedHashMap<String, Object>();
                                    m.put("type", "train");
                                    m.put("loop", ti.loop());
                                    m.put("playerId", pid);
                                    m.put("unitType", train.unitType().name());
                                    commands.add(m);
                                }
                            }
                            case ReplayCommand.BuildCommand bc -> {
                                var m = new LinkedHashMap<String, Object>();
                                m.put("type", "build");
                                m.put("loop", bc.loop());
                                m.put("playerId", pid);
                                m.put("buildingName", bc.buildingName());
                                m.put("x", bc.position().x());
                                m.put("y", bc.position().y());
                                commands.add(m);
                            }
                            case ReplayCommand.UpgradeCommand uc -> {
                                var m = new LinkedHashMap<String, Object>();
                                m.put("type", "upgrade");
                                m.put("loop", uc.loop());
                                m.put("playerId", pid);
                                m.put("upgradeName", uc.upgradeName());
                                commands.add(m);
                            }
                            case ReplayCommand.MorphCommand mc -> {
                                var m = new LinkedHashMap<String, Object>();
                                m.put("type", "morph");
                                m.put("loop", mc.loop());
                                m.put("playerId", pid);
                                m.put("source", mc.sourceName());
                                m.put("target", mc.targetName());
                                commands.add(m);
                            }
                            case ReplayCommand.Movement mv -> {
                                var order = mv.order();
                                var m = new LinkedHashMap<String, Object>();
                                m.put("type", "move");
                                m.put("loop", order.loop());
                                m.put("playerId", pid);
                                m.put("unitTag", order.unitTag());
                                var target = order.targetPos();
                                m.put("targetX", target != null ? target.x() : 0f);
                                m.put("targetY", target != null ? target.y() : 0f);
                                commands.add(m);
                            }
                            case ReplayCommand.CancelCommand ignored -> {}
                        }
                    }
                }
            }
        }

        commands.sort(Comparator.comparingLong(c -> ((Number) c.get("loop")).longValue()));
        result.put("gameCommands", commands);
        return result;
    }

    private Map<String, Object> buildGameJson(Replay replay, Player[] players,
                                              List<Map<String, Object>> trackerEvents) {
        Map<String, Object> gameJson = new LinkedHashMap<>();

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
        gameJson.put("trackerEvents", trackerEvents);

        Integer elapsedLoops = replay.header.getElapsedGameLoops();
        int baseBuild = replay.header.baseBuild != null ? replay.header.baseBuild : 0;
        gameJson.put("header", Map.of(
            "elapsedGameLoops", elapsedLoops != null ? elapsedLoops : 0,
            "baseBuild", baseBuild));

        gameJson.put("metadata", Map.of(
            "mapName", replay.details.title != null ? replay.details.title : ""));

        return gameJson;
    }

    private static int safe(Integer v) { return v != null ? v : 0; }
}
