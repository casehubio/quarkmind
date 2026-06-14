package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.ScoutingTask;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.MoveIntent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic scouting: passive intel from visible units + active probe scout.
 *
 * <p>Deactivated in favour of {@link io.quarkmind.plugin.scouting.DroolsScoutingTask}.
 * Marked {@code @Alternative} so Quarkus Arc does not register it as a CDI bean.
 */
@Alternative
@ApplicationScoped
@CaseType("starcraft-game")
public class BasicScoutingTask implements ScoutingTask {

    static final int SCOUT_DELAY_TICKS = 20;

    private static final Logger log = Logger.getLogger(BasicScoutingTask.class);

    private final IntentQueue intentQueue;

    private volatile String scoutProbeTag;

    @Inject
    public BasicScoutingTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "scouting.basic"; }
    @Override public String getName() { return "Basic Scouting"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

    // activateIf() not overridden — default ctx -> true is correct;
    // requires() already gates on READY.

    @Override
    public void execute(final CaseContext ctx) {
        List<Unit>     enemies   = ctx.getList(QuarkMindCaseFile.ENEMY_UNITS,  Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
        Long frameL = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
        long frame = frameL != null ? frameL : 0L;

        ctx.set(QuarkMindCaseFile.ENEMY_ARMY_SIZE, enemies.size());

        if (!enemies.isEmpty()) {
            scoutProbeTag = null;
        } else {
            maybeSendScout(frame, buildings, workers);
        }
        log.debugf("[SCOUTING] visible enemies=%d | scout=%s", enemies.size(), scoutProbeTag);
    }

    @Override
    public Set<String> produces() { return Set.of(QuarkMindCaseFile.ENEMY_ARMY_SIZE); }

    // ── Phase 1 bridges ──────────────────────────────────────────────────────

    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(final CaseFile caseFile) {
        return testActivation(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(final CaseFile caseFile) {
        var ctx = new CaseFileContext(caseFile);
        execute(ctx);
        produces().forEach(k -> { Object v = ctx.get(k); if (v != null) caseFile.put(k, v); });
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void maybeSendScout(long frame, List<Building> buildings, List<Unit> workers) {
        if (frame < SCOUT_DELAY_TICKS) return;
        if (workers.isEmpty()) return;
        if (scoutProbeTag != null) {
            if (workers.stream().anyMatch(w -> w.tag().equals(scoutProbeTag))) return;
            scoutProbeTag = null;
        }
        Unit scout = workers.get(workers.size() - 1);
        scoutProbeTag = scout.tag();
        Point2d home   = nexusPosition(buildings);
        Point2d target = estimatedEnemyBase(home);
        intentQueue.add(new MoveIntent(scout.tag(), target));
        log.infof("[SCOUTING] Scout probe %s dispatched toward estimated enemy base %s", scoutProbeTag, target);
    }

    static Point2d estimatedEnemyBase(Point2d ourBase) {
        float targetX = ourBase.x() < 64 ? 224 : 32;
        float targetY = ourBase.y() < 64 ? 224 : 32;
        return new Point2d(targetX, targetY);
    }

    private static Point2d nexusPosition(List<Building> buildings) {
        return buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS).findFirst()
            .map(Building::position).orElse(new Point2d(0, 0));
    }
}
