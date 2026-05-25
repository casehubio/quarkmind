package io.quarkmind.sc2.mock;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.SC2Data;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration test: empirically determines the exact game-loop build time for each building type
 * by diffing UnitInit and UnitDone tracker events.
 *
 * <p>Unlike training calibration, no GAME_EVENTS command matching is needed — both the
 * construction start (UnitInit) and end (UnitDone) come from tracker events, giving an exact
 * {@code T_real = doneLoop - initLoop} per building tag with no cross-source matching noise.
 *
 * <p>Package placement: lives in {@code io.quarkmind.sc2.mock} for package-private access to
 * {@link Sc2ReplayShared#toBuildingType} for building type resolution from tracker event names.
 */
class SC2BuildTimeCalibrationTest {

    private static final Path REPLAY_DIR = Path.of("replays/aiarena_protoss");

    /**
     * Addon and morphed-state names that map to a parent BuildingType in toBuildingType but have
     * a different (shorter) build time. Filtering these prevents addon completions from
     * contaminating structure build-time calibration. See #154.
     */
    private static final Set<String> ADDON_OR_MORPH_NAMES = Set.of(
        "BarracksTechLab", "BarracksReactor", "BarracksFlying",
        "FactoryTechLab",  "FactoryReactor",  "FactoryFlying",
        "StarportTechLab", "StarportReactor",  "StarportFlying",
        "SupplyDepotLowered",
        "OrbitalCommandFlying",
        "SpineCrawlerUprooted", "SporeCrawlerUprooted",
        "WarpGate"
    );

    @Test
    void buildTimeInLoopsMatchesCalibratedValues() throws IOException {
        final Map<BuildingType, List<Integer>> diffsByType = new TreeMap<>();

        try (var stream = Files.list(REPLAY_DIR)) {
            for (final Path p : stream.sorted().toList()) {
                if (!p.getFileName().toString().endsWith(".SC2Replay")) continue;
                accumulateFromReplay(p, diffsByType);
            }
        }

        System.out.println("=== Building Build Time Calibration ===");
        int assertions = 0;
        for (final Map.Entry<BuildingType, List<Integer>> e : diffsByType.entrySet()) {
            final BuildingType bt    = e.getKey();
            final List<Integer> diffs = e.getValue();
            if (diffs.size() < 2) continue;

            final int modal = modal(diffs);
            System.out.printf("  %-30s T_real=%-5d (n=%d)%n", bt, modal, diffs.size());

            assertThat(SC2Data.buildTimeInLoops(bt))
                .as("SC2Data.buildTimeInLoops(%s) must equal calibrated T_real=%d", bt, modal)
                .isEqualTo(modal);
            assertions++;
        }

        assertThat(assertions)
            .as("Must have calibrated at least one Protoss building type from AI Arena replays")
            .isGreaterThan(0);
    }

    // ---- Per-replay accumulation ----

    private void accumulateFromReplay(final Path replay,
                                      final Map<BuildingType, List<Integer>> diffsByType) {
        final Replay rep = RepParserEngine.parseReplay(replay, EnumSet.of(RepContent.TRACKER_EVENTS));
        if (rep == null || rep.trackerEvents == null) return;

        final Map<String, BuildingType> typeByTag = new HashMap<>();
        final Map<String, Integer>      initByTag = new HashMap<>();

        for (final Event rawEvent : rep.trackerEvents.getEvents()) {
            final int id = rawEvent.getId();

            if (id == ITrackerEvents.ID_UNIT_INIT) {
                final IBaseUnitEvent event    = (IBaseUnitEvent) rawEvent;
                final Integer        ctrlId   = event.getControlPlayerId();
                if (ctrlId == null || ctrlId == 0) continue;

                final String unitName = event.getUnitTypeName().toString();
                if (ADDON_OR_MORPH_NAMES.contains(unitName)) continue;

                final BuildingType bt = Sc2ReplayShared.toBuildingType(unitName);
                if (bt == BuildingType.UNKNOWN) continue;

                final String tag = makeTag(event.getUnitTagIndex(), event.getUnitTagRecycle());
                typeByTag.put(tag, bt);
                initByTag.put(tag, rawEvent.getLoop());

            } else if (id == ITrackerEvents.ID_UNIT_DONE) {
                final Integer tagIndex   = rawEvent.get("unitTagIndex");
                final Integer tagRecycle = rawEvent.get("unitTagRecycle");
                if (tagIndex == null || tagRecycle == null) continue;

                final String       tag      = makeTag(tagIndex, tagRecycle);
                final Integer      initLoop = initByTag.remove(tag);
                final BuildingType bt       = typeByTag.remove(tag);
                if (initLoop == null || bt == null) continue;

                final int tReal = rawEvent.getLoop() - initLoop;
                if (tReal > 0) {
                    diffsByType.computeIfAbsent(bt, k -> new ArrayList<>()).add(tReal);
                }
            }
        }
    }

    // ---- Helpers ----

    private static String makeTag(final int index, final int recycle) {
        return "r-" + index + "-" + recycle;
    }

    private static int modal(final List<Integer> values) {
        final Map<Integer, Integer> freq = new TreeMap<>();
        for (final int v : values) freq.merge(v, 1, Integer::sum);
        return freq.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }
}
