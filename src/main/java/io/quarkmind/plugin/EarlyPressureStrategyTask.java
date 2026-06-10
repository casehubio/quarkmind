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
 * Committed aggressive strategy — early military build from tick 1 (L6).
 *
 * <p>Writes {@code STRATEGY = "ATTACK"} unconditionally when selected.
 * No scouting dependency; activates on {@link QuarkMindCaseFile#READY} only.
 * Beats greedy/economic opponents; loses to defensive turtles.
 *
 * <p>Active only when {@link StrategySelector} has selected this implementation.
 * Competes with {@link DroolsStrategyTask} and {@link EconomicExpansionStrategyTask}
 * under trust-weighted routing.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class EarlyPressureStrategyTask implements StrategyTask {

    private static final Logger log = Logger.getLogger(EarlyPressureStrategyTask.class);

    private final StrategySelector strategySelector;

    @Inject
    public EarlyPressureStrategyTask(StrategySelector strategySelector) {
        this.strategySelector = strategySelector;
    }

    @Override public String getId()   { return "strategy.early-pressure"; }
    @Override public String getName() { return "Early Pressure Strategy"; }

    @Override
    public Set<String> entryCriteria() {
        // No ENEMY_ARMY_SIZE dependency — committed strategy fires from tick 1.
        // May execute before scouting in the same CaseEngine tick; intentional.
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
        caseFile.put(QuarkMindCaseFile.STRATEGY, "ATTACK");
        log.debugf("[EARLY-PRESSURE] STRATEGY=ATTACK");
    }
}
