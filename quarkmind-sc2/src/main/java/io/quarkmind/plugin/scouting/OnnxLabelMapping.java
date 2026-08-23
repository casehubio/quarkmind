package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;

import java.util.List;
import java.util.Map;

public final class OnnxLabelMapping {

    public static final List<String> VS_TERRAN_LABELS = List.of(
        "RUSH", "BANSHEE_HARASS", "AIR_SUPERIORITY", "MECH_PUSH", "BIO_TIMING");
    public static final List<String> VS_ZERG_LABELS = List.of(
        "RUSH", "ROACH_RUSH", "LING_BANE", "MUTA_HARASS", "HYDRA_PUSH", "MACRO_ECONOMY");
    public static final List<String> VS_PROTOSS_LABELS = List.of(
        "RUSH", "PROXY", "CANNON_RUSH", "DT_RUSH", "BLINK_STALKER", "COLOSSUS_PUSH",
        "AIR_SUPERIORITY");

    private static final Map<String, StrategyArchetype> TERRAN_MAP = Map.of(
        "RUSH", StrategyArchetype.TERRAN_MARINE_RUSH,
        "BANSHEE_HARASS", StrategyArchetype.TERRAN_BANSHEE_HARASS,
        "AIR_SUPERIORITY", StrategyArchetype.TERRAN_AIR_SUPERIORITY,
        "MECH_PUSH", StrategyArchetype.TERRAN_MECH_PUSH,
        "BIO_TIMING", StrategyArchetype.TERRAN_BIO_TIMING);

    private static final Map<String, StrategyArchetype> ZERG_MAP = Map.of(
        "RUSH", StrategyArchetype.ZERG_ZERGLING_RUSH,
        "ROACH_RUSH", StrategyArchetype.ZERG_ROACH_RUSH,
        "LING_BANE", StrategyArchetype.ZERG_LING_BANE,
        "MUTA_HARASS", StrategyArchetype.ZERG_MUTALISK_HARASS,
        "HYDRA_PUSH", StrategyArchetype.ZERG_HYDRA_PUSH,
        "MACRO_ECONOMY", StrategyArchetype.ZERG_MACRO);

    private static final Map<String, StrategyArchetype> PROTOSS_MAP = Map.ofEntries(
        Map.entry("RUSH", StrategyArchetype.PROTOSS_GATEWAY_RUSH),
        Map.entry("PROXY", StrategyArchetype.PROTOSS_PROXY_GATE),
        Map.entry("CANNON_RUSH", StrategyArchetype.PROTOSS_CANNON_RUSH),
        Map.entry("DT_RUSH", StrategyArchetype.PROTOSS_DT_RUSH),
        Map.entry("BLINK_STALKER", StrategyArchetype.PROTOSS_BLINK_STALKER),
        Map.entry("COLOSSUS_PUSH", StrategyArchetype.PROTOSS_COLOSSUS_PUSH),
        Map.entry("AIR_SUPERIORITY", StrategyArchetype.PROTOSS_AIR_SUPERIORITY));

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
