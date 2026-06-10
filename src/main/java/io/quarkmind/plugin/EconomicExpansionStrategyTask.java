package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.StrategySelector;
import io.quarkmind.agent.plugin.StrategyTask;
import org.jboss.logging.Logger;

import java.util.Set;

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

    @Override
    public Set<String> entryCriteria() {
        // No ENEMY_ARMY_SIZE dependency — committed strategy fires from tick 1.
        return Set.of(QuarkMindCaseFile.READY);
    }

    @Override
    public Set<String> producedKeys() { return Set.of(QuarkMindCaseFile.STRATEGY); }

    @Override
    public boolean canActivate(CaseFile caseFile) {
        return strategySelector.isSelected(getId())
            && entryCriteria().stream().allMatch(caseFile::contains);
    }

    @Override
    public void execute(CaseFile caseFile) {
        caseFile.put(QuarkMindCaseFile.STRATEGY, "EXPAND");
        log.debugf("[ECONOMIC-EXPANSION] STRATEGY=EXPAND");
    }
}
