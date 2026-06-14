package io.quarkmind.plugin;

import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.TacticsTask;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.AttackIntent;
import io.quarkmind.sc2.intent.MoveIntent;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Basic tactics: act on the {@link QuarkMindCaseFile#STRATEGY} key each tick.
 *
 * <p>Superseded by {@link DroolsTacticsTask} as the active CDI bean. Retained as
 * a plain class for direct-instantiation tests.
 *
 * <p>This class intentionally carries no CDI annotations ({@code @ApplicationScoped},
 * {@code @CaseType}) — {@link DroolsTacticsTask} is the permanent active bean.
 */
public class BasicTacticsTask implements TacticsTask {

    /** Generic attack target when no scouted enemy position is available. */
    static final Point2d MAP_CENTER = new Point2d(64, 64);

    private static final Logger log = Logger.getLogger(BasicTacticsTask.class);

    private final IntentQueue intentQueue;

    @Inject
    public BasicTacticsTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "tactics.basic"; }
    @Override public String getName() { return "Basic Tactics"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY, QuarkMindCaseFile.STRATEGY); }

    // activateIf() not overridden — default ctx -> true is correct;
    // requires() already gates on READY and STRATEGY.

    @Override
    public void execute(final CaseContext ctx) {
        String strategy = ctx.getOrDefault(QuarkMindCaseFile.STRATEGY, "MACRO");
        List<Unit>     army      = ctx.getList(QuarkMindCaseFile.ARMY,         Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        if (army.isEmpty()) return;
        switch (strategy) {
            case "ATTACK" -> army.forEach(unit -> intentQueue.add(new AttackIntent(unit.tag(), MAP_CENTER)));
            case "DEFEND" -> {
                Point2d rally = buildings.stream()
                    .filter(b -> b.type() == BuildingType.NEXUS).findFirst()
                    .map(Building::position).orElse(MAP_CENTER);
                army.forEach(unit -> intentQueue.add(new MoveIntent(unit.tag(), rally)));
            }
        }
        log.debugf("[TACTICS] %s | army=%d", strategy, army.size());
    }

    @Override public Set<String> produces() { return Set.of(); }

    // ── Phase 1 bridges ──────────────────────────────────────────────────────

    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(final CaseFile caseFile) {
        return testActivation(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(final CaseFile caseFile) {
        execute(new CaseFileContext(caseFile));
        // No outputs to sync back — produces() is empty
    }
}
