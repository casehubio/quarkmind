package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agent.AdvisoryInvocationCounter;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.StrategyArchetype;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class SC2AdvisoryCbrRetentionObserver implements CaseOutcomeObserver {

    private static final Logger log = Logger.getLogger(SC2AdvisoryCbrRetentionObserver.class);
    private static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");

    private final CbrCaseMemoryStore cbrStore;
    private final AdvisoryInvocationCounter invocationCounter;

    @Inject
    public SC2AdvisoryCbrRetentionObserver(CbrCaseMemoryStore cbrStore, AdvisoryInvocationCounter invocationCounter) {
        this.cbrStore = cbrStore;
        this.invocationCounter = invocationCounter;
    }

    @Override
    public void onOutcome(CaseOutcomeEvent event) {
        if ("UNKNOWN".equals(event.outcomeLabel())) return;

        Set<String> invokedAdvisors = invocationCounter.snapshot();
        if (invokedAdvisors.isEmpty()) return;

        Map<String, Object> snapshot = event.caseFileSnapshot();
        String archetype = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
        if (archetype == null) return;

        String strategyId = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_SELECTED_ID);
        String gamePhase = (String) snapshot.get(QuarkMindCaseFile.GAME_PHASE);
        String raceName = StrategyArchetype.valueOf(archetype).race().name();
        String matchup = "Pv" + raceName.charAt(0);

        double successRate = switch (event.outcomeLabel()) {
            case "WIN"  -> 1.0;
            case "LOSS" -> 0.0;
            case "TIE"  -> 0.5;
            default     -> 0.5;
        };

        for (String advisorId : invokedAdvisors) {
            SC2AdvisoryCbrCase cbrCase = SC2AdvisoryCbrCase.buildForAdvisory(
                    advisorId, archetype, raceName, matchup,
                    strategyId != null ? strategyId : "unknown", gamePhase);
            cbrCase = (SC2AdvisoryCbrCase) cbrCase.withOutcome(event.outcomeLabel(), null);

            String storedCaseId = cbrStore.store(
                    cbrCase,
                    event.tenancyId(),
                    event.caseId().toString(),
                    DOMAIN,
                    "sc2-advisory-retention",
                    SC2AdvisoryCbrCase.CBR_TYPE,
                    Path.of("quarkmind", "advisory", "cases"));

            cbrStore.recordOutcome(storedCaseId, SC2AdvisoryCbrCase.CBR_TYPE,
                    CbrOutcome.of(successRate, event.outcomeLabel(), event.closedAt()));

            log.infof("[CBR-ADVISORY] Stored: advisor=%s archetype=%s strategy=%s outcome=%s",
                    advisorId, archetype, strategyId, event.outcomeLabel());
        }
    }
}
