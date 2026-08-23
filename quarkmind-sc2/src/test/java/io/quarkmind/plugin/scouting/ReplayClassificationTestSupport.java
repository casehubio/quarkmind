package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.mock.IEM10JsonSimulatedGame;
import io.quarkmind.sc2.mock.ReplaySimulatedGame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ReplayClassificationTestSupport {

    static final Path AI_ARENA_DIR = Path.of("replays/aiarena_protoss");
    static final Path IEM10_ZIP = Path.of("replays/2016_IEM_10_Taipei.zip");
    static final int TICKS_PER_MINUTE = 61;
    static final double FRAMES_PER_SECOND = SC2Data.GAME_LOOPS_PER_SECOND;

    private ReplayClassificationTestSupport() {}

    record ClassificationResult(String matchup, String gameName,
                                StrategyArchetype groundTruth, StrategyArchetype predicted,
                                boolean correct, double confidence) {
        boolean isRush() {
            return groundTruth == StrategyArchetype.TERRAN_MARINE_RUSH
                    || groundTruth == StrategyArchetype.ZERG_ZERGLING_RUSH
                    || groundTruth == StrategyArchetype.ZERG_ROACH_RUSH
                    || groundTruth == StrategyArchetype.PROTOSS_GATEWAY_RUSH;
        }

        boolean isAirThreat() {
            return groundTruth == StrategyArchetype.TERRAN_BANSHEE_HARASS;
        }

        String reportLine() {
            return String.format("  %-4s %-40s truth=%-24s pred=%-24s conf=%.2f %s",
                    matchup, gameName, groundTruth, predicted, confidence, correct ? "✓" : "✗");
        }
    }

    static List<IEM10JsonSimulatedGame> loadIEM10Games() throws IOException {
        return IEM10JsonSimulatedGame.enumerate(IEM10_ZIP);
    }

    static List<ReplaySimulatedGame> loadAIArenaGames() throws IOException {
        return Files.list(AI_ARENA_DIR)
                .filter(p -> p.toString().endsWith(".SC2Replay"))
                .sorted()
                .map(p -> {
                    try {
                        return new ReplaySimulatedGame(p, 1);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    static Race enemyRaceFromMatchup(String matchup) {
        return switch (matchup) {
            case "PvT" -> Race.TERRAN;
            case "PvZ" -> Race.ZERG;
            case "PvP" -> Race.PROTOSS;
            default -> null;
        };
    }

    static StrategyArchetype deriveGroundTruth(Map<UnitType, Long> counts, double gameTimeMin) {
        long marines = counts.getOrDefault(UnitType.MARINE, 0L);
        long roaches = counts.getOrDefault(UnitType.ROACH, 0L);
        long zerglings = counts.getOrDefault(UnitType.ZERGLING, 0L);
        long stalkers = counts.getOrDefault(UnitType.STALKER, 0L);
        long zealots = counts.getOrDefault(UnitType.ZEALOT, 0L);
        long siegeTanks = counts.getOrDefault(UnitType.SIEGE_TANK, 0L);
        long banshees = counts.getOrDefault(UnitType.BANSHEE, 0L);
        long hydralisks = counts.getOrDefault(UnitType.HYDRALISK, 0L);
        long mutalisks = counts.getOrDefault(UnitType.MUTALISK, 0L);
        long broodLords = counts.getOrDefault(UnitType.BROOD_LORD, 0L);
        long hellions = counts.getOrDefault(UnitType.HELLION, 0L);
        long thors = counts.getOrDefault(UnitType.THOR, 0L);
        long bcs = counts.getOrDefault(UnitType.BATTLECRUISER, 0L);
        long colossus = counts.getOrDefault(UnitType.COLOSSUS, 0L);
        long archons = counts.getOrDefault(UnitType.ARCHON, 0L);
        long carriers = counts.getOrDefault(UnitType.CARRIER, 0L);
        long dts = counts.getOrDefault(UnitType.DARK_TEMPLAR, 0L);

        // Early game (< 5 min)
        if (marines >= 5 && gameTimeMin < 4.0) return StrategyArchetype.TERRAN_MARINE_RUSH;
        if (banshees >= 1 && gameTimeMin < 8.0) return StrategyArchetype.TERRAN_BANSHEE_HARASS;
        if (dts >= 1 && gameTimeMin < 8.0) return StrategyArchetype.PROTOSS_DT_HARASS;
        if (zerglings >= 6 && gameTimeMin < 4.0) return StrategyArchetype.ZERG_ZERGLING_RUSH;
        if (roaches >= 4 && gameTimeMin < 5.0) return StrategyArchetype.ZERG_ROACH_RUSH;
        if (stalkers + zealots >= 4 && gameTimeMin < 5.0) return StrategyArchetype.PROTOSS_GATEWAY_RUSH;

        // Late game (specific tech — check before mid-game compositions)
        if (bcs >= 1 && gameTimeMin >= 10.0) return StrategyArchetype.TERRAN_BC_TRANSITION;
        if (broodLords >= 2 && gameTimeMin >= 12.0) return StrategyArchetype.ZERG_BROOD_LORD;
        if (carriers >= 2 && gameTimeMin >= 12.0) return StrategyArchetype.PROTOSS_CARRIER;

        // Mid game compositions (more specific first)
        if (marines >= 6 && siegeTanks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.TERRAN_MARINE_TANK;
        if (hellions >= 3 && thors >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.TERRAN_BATTLE_MECH;
        if (roaches >= 3 && hydralisks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.ZERG_ROACH_HYDRA;
        if (mutalisks >= 3 && gameTimeMin >= 5.0) return StrategyArchetype.ZERG_MUTALISK_HARASS;
        if (stalkers >= 3 && colossus >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.PROTOSS_STALKER_COLOSSUS;
        if (zealots >= 4 && archons >= 1 && gameTimeMin >= 6.0) return StrategyArchetype.PROTOSS_CHARGELOT_ARCHON;

        // Mid game fallbacks (less specific)
        if (marines >= 6 && gameTimeMin >= 4.0) return StrategyArchetype.TERRAN_BIO_TIMING;
        if (siegeTanks >= 2 && gameTimeMin >= 5.0) return StrategyArchetype.TERRAN_MECH_PUSH;
        return null;
    }
}
