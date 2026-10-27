package io.quarkmind.sc2.replay;

import io.quarkmind.domain.*;

import java.util.*;

public class ClassifierLabelMapper {

    private static final Map<String, Race> MATCHUP_TO_RACE = Map.of(
        "vs_terran", Race.TERRAN,
        "vs_zerg", Race.ZERG,
        "vs_protoss", Race.PROTOSS
    );

    public static Race opponentRaceEnum(String matchup) {
        return MATCHUP_TO_RACE.get(matchup);
    }

    public static String archLabel(StrategyArchetype arch) {
        String name = arch.name();
        return name.substring(name.indexOf('_') + 1);
    }

    public static List<StrategyArchetype> mapToArchetypes(
            String classifierLabel, String matchup, GamePhase phase) {
        Race race = opponentRaceEnum(matchup);
        String labelUpper = classifierLabel.toUpperCase();

        var result = new ArrayList<StrategyArchetype>();
        for (var arch : StrategyArchetype.values()) {
            if (arch.race() != race) continue;
            if (arch.phase() != phase) continue;
            if (arch.name().contains("UNKNOWN")) continue;

            if (matchesLabel(arch, labelUpper)) {
                result.add(arch);
            }
        }
        return result;
    }

    private static boolean matchesLabel(StrategyArchetype arch, String label) {
        String racePrefixRemoved = archLabel(arch);

        if (racePrefixRemoved.equals(label)) return true;
        if (label.equals("RUSH") && arch.category() == ArchetypeCategory.RUSH) return true;
        if (label.equals("PROXY") && arch.name().contains("PROXY")) return true;
        if (label.equals("MACRO_ECONOMY") && arch.category() == ArchetypeCategory.MACRO) return true;
        if (label.equals("TECH_RUSH") && arch.category() == ArchetypeCategory.TECH) return true;

        return false;
    }
}
