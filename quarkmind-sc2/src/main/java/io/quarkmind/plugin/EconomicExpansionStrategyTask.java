package io.quarkmind.plugin;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.plugin.StrategyTask;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.function.Predicate;

@ApplicationScoped
@CaseType("starcraft-game")
public class EconomicExpansionStrategyTask implements StrategyTask {

    private static final Logger log = Logger.getLogger(EconomicExpansionStrategyTask.class);

    @Override
    public String getId()   {return "strategy.economic-expansion";}

    @Override
    public String getName() {return "Economic Expansion Strategy";}

    @Override
    public Set<String> requires() {
        return Set.of(QuarkMindCaseFile.READY);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> !"coach".equals(ctx.getString(QuarkMindCaseFile.GAME_MODE))
                      && getId().equals(ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID));
    }

    @Override
    public void execute(final CaseContext ctx) {
        ctx.set(QuarkMindCaseFile.STRATEGY, "EXPAND");
        log.debugf("[ECONOMIC-EXPANSION] STRATEGY=EXPAND");
    }

    @Override
    public Set<String> produces() {return Set.of(QuarkMindCaseFile.STRATEGY);}

}
