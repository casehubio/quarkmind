package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;

import java.util.List;
import java.util.Map;

public final class OnnxLabelMapping {

    public static final List<String> VS_TERRAN_LABELS = List.of(
        "RUSH", "PROXY", "BANSHEE_HARASS", "AIR_SUPERIORITY",
        "MECH_PUSH", "BIO_TIMING", "MACRO_ECONOMY", "TECH_RUSH");
    public static final List<String> VS_ZERG_LABELS = List.of(
        "RUSH", "ROACH_RUSH", "LING_BANE", "MUTA_HARASS",
        "HYDRA_PUSH", "MACRO_ECONOMY", "TECH_RUSH");
    public static final List<String> VS_PROTOSS_LABELS = List.of(
        "RUSH", "PROXY", "CANNON_RUSH", "DT_RUSH", "BLINK_STALKER",
        "COLOSSUS_PUSH", "AIR_SUPERIORITY", "MACRO_ECONOMY", "TECH_RUSH");

    private static final Map<String, StrategyArchetype> TERRAN_MAP = Map.ofEntries(
        Map.entry("RUSH", StrategyArchetype.TERRAN_MARINE_RUSH),
        Map.entry("PROXY", StrategyArchetype.TERRAN_PROXY_BARRACKS),
        Map.entry("BANSHEE_HARASS", StrategyArchetype.TERRAN_BANSHEE_HARASS),
        Map.entry("AIR_SUPERIORITY", StrategyArchetype.TERRAN_AIR_SUPERIORITY),
        Map.entry("MECH_PUSH", StrategyArchetype.TERRAN_MECH_PUSH),
        Map.entry("BIO_TIMING", StrategyArchetype.TERRAN_BIO_TIMING),
        Map.entry("MACRO_ECONOMY", StrategyArchetype.TERRAN_FAST_EXPAND),
        Map.entry("TECH_RUSH", StrategyArchetype.TERRAN_TECH_RUSH));

    private static final Map<String, StrategyArchetype> ZERG_MAP = Map.of(
        "RUSH", StrategyArchetype.ZERG_ZERGLING_RUSH,
        "ROACH_RUSH", StrategyArchetype.ZERG_ROACH_RUSH,
        "LING_BANE", StrategyArchetype.ZERG_LING_BANE,
        "MUTA_HARASS", StrategyArchetype.ZERG_MUTALISK_HARASS,
        "HYDRA_PUSH", StrategyArchetype.ZERG_HYDRA_PUSH,
        "MACRO_ECONOMY", StrategyArchetype.ZERG_MACRO,
        "TECH_RUSH", StrategyArchetype.ZERG_TECH_RUSH);

    private static final Map<String, StrategyArchetype> PROTOSS_MAP = Map.ofEntries(
        Map.entry("RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH),
        Map.entry("PROXY", StrategyArchetype.PROTOSS_PROXY_GATE),
        Map.entry("CANNON_RUSH", StrategyArchetype.PROTOSS_CANNON_RUSH),
        Map.entry("DT_RUSH", StrategyArchetype.PROTOSS_DT_RUSH),
        Map.entry("BLINK_STALKER", StrategyArchetype.PROTOSS_BLINK_STALKER),
        Map.entry("COLOSSUS_PUSH", StrategyArchetype.PROTOSS_COLOSSUS_PUSH),
        Map.entry("AIR_SUPERIORITY", StrategyArchetype.PROTOSS_AIR_SUPERIORITY),
        Map.entry("MACRO_ECONOMY", StrategyArchetype.PROTOSS_MACRO),
        Map.entry("TECH_RUSH", StrategyArchetype.PROTOSS_TECH_RUSH));

    public static StrategyArchetype resolve(String label, Race race) {
        return switch (race) {
            case TERRAN  -> TERRAN_MAP.get(label);
            case ZERG    -> ZERG_MAP.get(label);
            case PROTOSS -> PROTOSS_MAP.get(label);
        };
    }

    public static List<String> labelsForRace(Race race) {
        return switch (race) {
            case TERRAN  -> VS_TERRAN_LABELS;
            case ZERG    -> VS_ZERG_LABELS;
            case PROTOSS -> VS_PROTOSS_LABELS;
        };
    }

    private OnnxLabelMapping() {}
}
