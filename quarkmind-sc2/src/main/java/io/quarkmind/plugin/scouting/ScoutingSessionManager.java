package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.plugin.scouting.events.EnemyArmyNearBase;
import io.quarkmind.plugin.scouting.events.EnemyExpansionSeen;
import io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages Java-side event buffers for {@link DroolsScoutingTask}.
 *
 * <p>Maintains three rolling windows:
 * <ul>
 *   <li>Unit first-seen events — 3-minute build-order window (evicted by timestamp)</li>
 *   <li>Army-near-base events — 10-second threat window (evicted by timestamp)</li>
 *   <li>Expansion events     — permanent for the life of the game</li>
 * </ul>
 *
 * <p>Call {@link #reset()} on game restart (detected by frame going backwards).
 */
@ApplicationScoped
public class ScoutingSessionManager {

    /** Build-order detection window: 3 minutes at SC2 Faster speed. */
    public static final long UNIT_WINDOW_MS = 3L * 60 * 1000;

    /** Timing-attack threat window: 10 seconds. */
    public static final long ARMY_WINDOW_MS = 10L * 1000;

    /**
     * Distance threshold (tiles) from estimated enemy main base beyond which
     * a sighted unit is treated as evidence of an expansion or forward base.
     */
    public static final float EXPANSION_DISTANCE_THRESHOLD = 50f;

    /** Minimum enemy units near our Nexus to trigger an army-near-base event. */
    public static final int MIN_ARMY_NEAR_BASE = 3;

    /** Distance (tiles) from our Nexus that counts as "near our base". */
    public static final float NEAR_BASE_DISTANCE = 30f;

    private final Set<String>               seenUnitTags       = new HashSet<>();
    private final Set<String>               seenExpansionCells = new HashSet<>();
    private final Deque<EnemyUnitFirstSeen> unitBuffer         = new ArrayDeque<>();
    private final Deque<EnemyArmyNearBase>  armyBuffer         = new ArrayDeque<>();
    private final List<EnemyExpansionSeen>  expansionBuffer    = new ArrayList<>();
    public static final float               CONFIRMED_EXPANSION_DISTANCE = 25f;
    private static final float              VISION_RANGE = 9.0f;

    private static final java.util.Set<io.quarkmind.domain.BuildingType> BASE_TYPES = java.util.Set.of(
            io.quarkmind.domain.BuildingType.NEXUS, io.quarkmind.domain.BuildingType.HATCHERY, io.quarkmind.domain.BuildingType.COMMAND_CENTER,
            io.quarkmind.domain.BuildingType.LAIR, io.quarkmind.domain.BuildingType.HIVE, io.quarkmind.domain.BuildingType.ORBITAL_COMMAND,
            io.quarkmind.domain.BuildingType.PLANETARY_FORTRESS);

    private final java.util.Map<String, Point2d> confirmedExpansions = new java.util.LinkedHashMap<>();
    private       boolean                        hasEverConfirmed    = false;
    private       Point2d                        confirmedMainBase   = null;


    /** Clears all buffers. Call when a new game starts. */
    public void reset() {
        seenUnitTags.clear();
        seenExpansionCells.clear();
        unitBuffer.clear();
        armyBuffer.clear();
        expansionBuffer.clear();
        confirmedExpansions.clear();
        hasEverConfirmed = false;
        confirmedMainBase = null;
    }

    /**
     * Processes visible enemy units for this tick, inserting new events into buffers.
     *
     * @param enemies              currently visible enemy units
     * @param gameTimeMs           current game time in milliseconds (frame x 1000/22.4)
     * @param ourNexus             position of our first Nexus (home base reference)
     * @param estimatedEnemyBase   estimated position of the enemy main base
     */
    public void processFrame(List<Unit> enemies, long gameTimeMs,
                             Point2d ourNexus, Point2d estimatedEnemyBase) {
        long nearCount = 0;
        for (Unit e : enemies) {
            if (seenUnitTags.add(e.tag())) {
                unitBuffer.add(new EnemyUnitFirstSeen(e.type(), gameTimeMs));
            }

            if (e.position().distanceTo(estimatedEnemyBase) > EXPANSION_DISTANCE_THRESHOLD) {
                String cell = (int) e.position().x() + ":" + (int) e.position().y();
                if (seenExpansionCells.add(cell)) {
                    expansionBuffer.add(new EnemyExpansionSeen(e.position(), gameTimeMs));
                }
            }

            if (e.position().distanceTo(ourNexus) < NEAR_BASE_DISTANCE) {
                nearCount++;
            }
        }

        if (nearCount >= MIN_ARMY_NEAR_BASE) {
            armyBuffer.add(new EnemyArmyNearBase((int) nearCount, gameTimeMs));
        }
    }

    /**
     * Removes events that have fallen outside their temporal window.
     * Call once per tick AFTER {@link #processFrame}.
     */
    public void evict(long currentGameTimeMs) {
        unitBuffer.removeIf(e -> currentGameTimeMs - e.gameTimeMs() > UNIT_WINDOW_MS);
        armyBuffer.removeIf(e -> currentGameTimeMs - e.gameTimeMs() > ARMY_WINDOW_MS);
    }

    public void processBuildings(java.util.List<io.quarkmind.domain.Building> enemyBuildings, Point2d estimatedEnemyBase,
                                 java.util.List<Unit> friendlyUnits, java.util.List<io.quarkmind.domain.Building> friendlyBuildings) {
        java.util.List<io.quarkmind.domain.Building> baseBldgs = enemyBuildings.stream()
                                                                               .filter(b -> BASE_TYPES.contains(b.type()))
                                                                               .toList();

        if (confirmedMainBase == null) {
            baseBldgs.stream()
                     .filter(b -> b.position().distanceTo(estimatedEnemyBase) <= EXPANSION_DISTANCE_THRESHOLD)
                     .min(java.util.Comparator.comparingDouble(b -> b.position().distanceTo(estimatedEnemyBase)))
                     .ifPresent(b -> confirmedMainBase = b.position());
        }

        Point2d reference = confirmedMainBase != null ? confirmedMainBase : estimatedEnemyBase;
        float   threshold = confirmedMainBase != null ? CONFIRMED_EXPANSION_DISTANCE : EXPANSION_DISTANCE_THRESHOLD;

        for (io.quarkmind.domain.Building b : baseBldgs) {
            if (b.position().distanceTo(reference) > threshold) {
                confirmedExpansions.put(b.tag(), b.position());
                hasEverConfirmed = true;
            }
        }

        confirmedExpansions.entrySet().removeIf(entry -> {
            String  tag             = entry.getKey();
            Point2d location        = entry.getValue();
            boolean buildingPresent = enemyBuildings.stream().anyMatch(b -> b.tag().equals(tag));
            if (!buildingPresent && hasVisionOf(location, friendlyUnits, friendlyBuildings)) {
                return true;
            }
            return false;
        });
    }

    private boolean hasVisionOf(Point2d location, java.util.List<Unit> friendlyUnits,
                                java.util.List<io.quarkmind.domain.Building> friendlyBuildings) {
        for (Unit u : friendlyUnits) {
            if (u.position().distanceTo(location) < VISION_RANGE) {return true;}
        }
        for (io.quarkmind.domain.Building b : friendlyBuildings) {
            if (b.position().distanceTo(location) < VISION_RANGE) {return true;}
        }
        return false;
    }


    /**
     * Builds a fresh {@link ScoutingRuleUnit} populated from the current buffer contents.
     * Call after {@link #evict} so the rule unit only sees events within their windows.
     *
     * <p><strong>Note:</strong> requires Quarkus build-time init (GE-0053) — not callable
     * from plain JUnit tests. Use the testability accessors below in unit tests instead.
     */
    public ScoutingRuleUnit buildRuleUnit() {
        ScoutingRuleUnit data = new ScoutingRuleUnit();
        unitBuffer.forEach(data.getUnitEvents()::add);
        expansionBuffer.forEach(data.getExpansionEvents()::add);
        armyBuffer.forEach(data.getArmyNearBaseEvents()::add);
        return data;
    }

    public PatternClassificationRuleUnit buildPatternRuleUnit(double gameTimeMin) {
        PatternClassificationRuleUnit data = new PatternClassificationRuleUnit();
        unitBuffer.forEach(data.getUnitEvents()::add);
        expansionBuffer.forEach(data.getExpansionEvents()::add);
        armyBuffer.forEach(data.getArmyNearBaseEvents()::add);
        data.getGameTimeStore().add(gameTimeMin);
        return data;
    }


    // ---- Testability accessors ----
    public int seenTagCount()        { return seenUnitTags.size(); }
    public int unitBufferSize()      { return unitBuffer.size(); }
    public int armyBufferSize()      { return armyBuffer.size(); }
    public int expansionBufferSize() { return expansionBuffer.size(); }

    public List<io.quarkmind.plugin.scouting.events.EnemyUnitFirstSeen> unitBufferSnapshot() {
        return List.copyOf(unitBuffer);
    }

    public int confirmedExpansionCount() {return confirmedExpansions.size();}

    public boolean hasEverConfirmed()    {return hasEverConfirmed;}


}
