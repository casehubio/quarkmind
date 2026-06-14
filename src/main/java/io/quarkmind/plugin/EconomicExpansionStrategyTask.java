package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.agent.plugin.StrategyTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Committed economic strategy — resource scaling before military (L6).
 *
 * <p>Writes {@code STRATEGY = "EXPAND"} unconditionally when selected.
 * No scouting dependency; activates on {@link QuarkMindCaseFile#READY} only.
 * Beats passive/defensive opponents; loses to early rushers.
 *
 * <p>Active only when {@link StrategySelector} has selected this implementation.
 * Competes with {@link DroolsStrategyTask} and {@link EarlyPressureStrategyTask}
 * under trust-weighted routing.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class EconomicExpansionStrategyTask implements StrategyTask {

    private static final Logger log = Logger.getLogger(EconomicExpansionStrategyTask.class);

    private final StrategySelector strategySelector;

    @Inject
    public EconomicExpansionStrategyTask(StrategySelector strategySelector) {
        this.strategySelector = strategySelector;
    }

    @Override public String getId()   { return "strategy.economic-expansion"; }
    @Override public String getName() { return "Economic Expansion Strategy"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() {
        // No ENEMY_ARMY_SIZE dependency — committed strategy fires from tick 1.
        return Set.of(QuarkMindCaseFile.READY);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        // requires() already gates on READY; only the selector check is extra
        return ctx -> strategySelector.isSelected(getId());
    }

    @Override
    public void execute(final CaseContext ctx) {
        ctx.set(QuarkMindCaseFile.STRATEGY, "EXPAND");
        log.debugf("[ECONOMIC-EXPANSION] STRATEGY=EXPAND");
    }

    @Override
    public Set<String> produces() { return Set.of(QuarkMindCaseFile.STRATEGY); }

    // ── Phase 1 bridges — removed when poc CaseFile is dropped in Phase 2 ──

    @Override
    public Set<String> entryCriteria() { return requires(); }

    @Override
    public Set<String> producedKeys() { return produces(); }

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
}
