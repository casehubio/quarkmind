package io.quarkmind.sc2.mock;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.replay.GameEventStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration test: empirically determines the exact game-loop train time for each unit type
 * by correlating training commands (GAME_EVENTS via abilLink) with unit-born tracker events.
 *
 * Two independent sources are cross-validated:
 *   A — the default AI Arena replay (single game, scelight binary parser)
 *   C — all 29 other AI Arena replays (same patch, same abilLinks, different game patterns)
 *
 * Commands are extracted directly by abilLink without AbilityMapping selection tracking,
 * eliminating gaps that occur when selection events are missed.
 *
 * Matching uses a range-bounded modal approach: for each (born, command) pair in the
 * expected training-time window, count diff occurrences. The modal value is T_real.
 *
 * Cross-validation note: the IEM10 JSON dataset uses different abilLink values
 * (2016 patch vs. 2023+ AI Arena patch) and cannot be used as a direct cross-source.
 * See issue #149 comments for details.
 *
 * <p>Package placement: this test lives in {@code io.quarkmind.sc2.mock} (not {@code sc2.replay})
 * because it needs package-private access to {@link Sc2ReplayShared#toUnitType} for unit name
 * resolution from tracker events.
 */
class SC2TrainTimeCalibrationTest {

    private static final Path REPLAY_DIR   =
        Path.of("replays/aiarena_protoss");
    private static final String DEFAULT_REPLAY_NAME =
        "Nothing_4720936.SC2Replay";

    // abilLink constants — mirrors AbilityMapping (no selection state required here).
    // Keep in sync with AbilityMapping if new unit types or abilLinks are added.
    private static final int ABIL_NEXUS     = 175;
    private static final int ABIL_GATEWAY   = 172;
    private static final int ABIL_ROBOTICS  = 173;
    private static final int ABIL_STARGATE  = 174;

    private static final Map<Integer, UnitType> GATEWAY_UNITS  = Map.of(0, UnitType.STALKER,  1, UnitType.ZEALOT,   5, UnitType.ADEPT);
    private static final Map<Integer, UnitType> ROBOTICS_UNITS = Map.of(0, UnitType.IMMORTAL, 1, UnitType.OBSERVER);
    private static final Map<Integer, UnitType> STARGATE_UNITS = Map.of(0, UnitType.PHOENIX,  1, UnitType.VOID_RAY, 2, UnitType.ORACLE);

    /** Expected T_real range per unit type — centred on seconds × 22.4, ±12 loops slack. */
    private static final Map<UnitType, int[]> EXPECTED_RANGES = Map.of(
        UnitType.PROBE,    new int[]{256, 282},   // 268.8 ± 12
        UnitType.ZEALOT,   new int[]{615, 641},   // 627.2 ± 12
        UnitType.STALKER,  new int[]{682, 708},   // 694.4 ± 12
        UnitType.IMMORTAL, new int[]{884, 910},   // 896.0 ± 12
        UnitType.OBSERVER, new int[]{481, 507}    // 492.8 ± 12
    );

    @Test
    void trainTimeInLoopsAgreesAcrossSourcesAndMatchesSC2Data() throws IOException {
        final Path defaultReplay = REPLAY_DIR.resolve(DEFAULT_REPLAY_NAME);
        final Map<UnitType, Integer> tRealA = extractFromReplay(defaultReplay, 1);
        assertThat(tRealA).as("Source A (default replay) must yield at least one unit type").isNotEmpty();

        // Source C: all other replays in the directory (same patch, different game patterns)
        final Map<UnitType, List<Integer>> allCommandsC = new TreeMap<>();
        final Map<UnitType, List<Integer>> allBornC     = new TreeMap<>();
        int replayCount = 0;
        try (var dirStream = Files.list(REPLAY_DIR)) {
            for (Path p : dirStream.sorted().toList()) {
                if (!p.getFileName().toString().endsWith(".SC2Replay")) continue;
                if (p.getFileName().toString().equals(DEFAULT_REPLAY_NAME)) continue;
                accumulateFromReplay(p, 1, allCommandsC, allBornC);
                replayCount++;
            }
        }
        final Map<UnitType, Integer> tRealC = calibrate(allCommandsC, allBornC, "AI Arena (C)");
        System.out.printf("  Processed %d replays for source C%n", replayCount);
        assertThat(tRealC).as("Source C (%d AI Arena replays) must yield at least one unit type", replayCount)
            .isNotEmpty();

        System.out.println("=== Calibration Results ===");
        for (UnitType ut : UnitType.values()) {
            final Integer a = tRealA.get(ut);
            final Integer c = tRealC.get(ut);
            if (a != null || c != null) {
                System.out.printf("  %-12s  A=%-5s  C=%s%n", ut, a, c);
            }
        }

        // Cross-validate: A and C must agree for every unit type present in both
        for (UnitType ut : tRealA.keySet()) {
            final Integer fromC = tRealC.get(ut);
            if (fromC == null) continue;
            assertThat(tRealA.get(ut))
                .as("T_real for %s: default-replay(A)=%d disagrees with other-replays(C)=%d — check patch versions",
                    ut, tRealA.get(ut), fromC)
                .isEqualTo(fromC);
        }

        // SC2Data must match calibrated values.
        // Use source A where available; supplement with source C for unit types not in source A.
        // Only assert unit types with ≥5 observations across sources (or exact matches between A and C).
        final Map<UnitType, Integer> assertMap = new TreeMap<>(tRealA);
        for (Map.Entry<UnitType, Integer> e : tRealC.entrySet()) {
            if (!assertMap.containsKey(e.getKey())) {
                assertMap.put(e.getKey(), e.getValue()); // supplement with source C
            }
        }
        for (Map.Entry<UnitType, Integer> e : assertMap.entrySet()) {
            final UnitType ut = e.getKey();
            final int expected = e.getValue();
            assertThat(SC2Data.trainTimeInLoops(ut))
                .as("SC2Data.trainTimeInLoops(%s) must equal calibrated T_real=%d", ut, expected)
                .isEqualTo(expected);
        }
    }

    // ---- Per-replay extraction ----

    private Map<UnitType, Integer> extractFromReplay(Path replay, int playerId) {
        final Map<UnitType, List<Integer>> commands = new TreeMap<>();
        final Map<UnitType, List<Integer>> born     = new TreeMap<>();
        accumulateFromReplay(replay, playerId, commands, born);
        return calibrate(commands, born, "scelight:" + replay.getFileName());
    }

    private void accumulateFromReplay(Path replay, int playerId,
                                       Map<UnitType, List<Integer>> commandsByType,
                                       Map<UnitType, List<Integer>> bornByType) {
        final int userId = playerId - 1;

        // Commands: read GAME_EVENTS directly by abilLink — no selection tracking needed.
        // Some replays have no game events (bot-only formats) — skip them gracefully.
        final List<Event> gameEvents;
        try {
            gameEvents = GameEventStream.events(replay);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        for (Event raw : gameEvents) {
            if (!(raw instanceof CmdEvent cmd)) continue;
            if (cmd.getUserId() != userId) continue;
            final UnitType ut = resolveTrainUnit(cmd.getAbilLink(),
                Objects.requireNonNullElse(cmd.getAbilCmdIndex(), 0));
            if (ut == null) continue;
            commandsByType.computeIfAbsent(ut, k -> new ArrayList<>()).add(cmd.getLoop());
        }

        // Unit-born events from tracker
        final Replay rep = RepParserEngine.parseReplay(replay, EnumSet.of(RepContent.TRACKER_EVENTS));
        if (rep == null || rep.trackerEvents == null) return;
        for (Event raw : rep.trackerEvents.getEvents()) {
            if (raw.getId() != ITrackerEvents.ID_UNIT_BORN) continue;
            final IBaseUnitEvent born = (IBaseUnitEvent) raw;
            if (!Objects.equals(born.getControlPlayerId(), playerId)) continue;
            if (born.getLoop() == 0) continue;
            final UnitType ut = Sc2ReplayShared.toUnitType(born.getUnitTypeName().toString());
            if (ut == UnitType.UNKNOWN) continue;
            bornByType.computeIfAbsent(ut, k -> new ArrayList<>()).add(born.getLoop());
        }
    }

    // ---- Range-bounded modal calibration ----

    /**
     * For each unit type, finds the modal (born_loop - command_loop) value within the
     * expected training-time range. Robust to missed commands and queued units:
     * non-queued correct pairs produce diff = T_real; all others produce different values.
     * Requires the modal value to appear at least twice for confidence.
     */
    private Map<UnitType, Integer> calibrate(
            Map<UnitType, List<Integer>> commandsByType,
            Map<UnitType, List<Integer>> bornByType,
            String label) {
        final Map<UnitType, Integer> result = new TreeMap<>();
        for (Map.Entry<UnitType, int[]> rangeEntry : EXPECTED_RANGES.entrySet()) {
            final UnitType ut   = rangeEntry.getKey();
            final int      tMin = rangeEntry.getValue()[0];
            final int      tMax = rangeEntry.getValue()[1];
            final List<Integer> cmds  = commandsByType.getOrDefault(ut, List.of());
            final List<Integer> borns = bornByType.getOrDefault(ut, List.of());
            if (cmds.isEmpty() || borns.isEmpty()) continue;

            final Map<Integer, Integer> freq = new TreeMap<>();
            for (int born : borns) {
                for (int cmd : cmds) {
                    final int diff = born - cmd;
                    if (diff >= tMin && diff <= tMax) {
                        freq.merge(diff, 1, Integer::sum);
                    }
                }
            }
            if (freq.isEmpty()) continue;

            final int modal    = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            final int modalCnt = freq.get(modal);
            if (modalCnt < 2) continue;

            result.put(ut, modal);
            System.out.printf("  [%s] T_real(%-10s) = %d  (count=%d)%n", label, ut, modal, modalCnt);
        }
        return result;
    }

    // ---- Helpers ----

    private UnitType resolveTrainUnit(Integer abilLink, int abilIdx) {
        if (abilLink == null) return null;
        return switch (abilLink) {
            case ABIL_NEXUS    -> UnitType.PROBE;
            case ABIL_GATEWAY  -> GATEWAY_UNITS.get(abilIdx);
            case ABIL_ROBOTICS -> ROBOTICS_UNITS.get(abilIdx);
            case ABIL_STARGATE -> STARGATE_UNITS.get(abilIdx);
            default            -> null;
        };
    }
}
