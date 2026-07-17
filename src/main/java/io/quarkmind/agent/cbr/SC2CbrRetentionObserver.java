package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.EnemyArchetype;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

@ApplicationScoped
public class SC2CbrRetentionObserver implements CaseOutcomeObserver {

    private static final Logger log = Logger.getLogger(SC2CbrRetentionObserver.class);
    private static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");

    private final CbrCaseMemoryStore cbrStore;

    @Inject
    public SC2CbrRetentionObserver(CbrCaseMemoryStore cbrStore) {
        this.cbrStore = cbrStore;
    }

    @Override
    public void onOutcome(CaseOutcomeEvent event) {
        if ("UNKNOWN".equals(event.outcomeLabel())) {
            log.infof("[CBR-RETAIN] Game ended with unknown result — skipped");
            return;
        }

        Map<String, Object> snapshot = event.caseFileSnapshot();
        String archetype = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
        if (archetype == null) {
            log.infof("[CBR-RETAIN] No archetype in snapshot — skipped (no routing occurred)");
            return;
        }

        String strategyId = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_SELECTED_ID);
        Double confidence = (Double) snapshot.get(QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE);
        String raceName = EnemyArchetype.valueOf(archetype).race().name();
        String matchup = "Pv" + raceName.charAt(0);

        SC2GameCbrCase cbrCase = SC2GameCbrCase.buildForGame(
                archetype, raceName, matchup,
                confidence != null ? confidence : 0.0,
                strategyId);
        cbrCase = (SC2GameCbrCase) cbrCase.withOutcome(event.outcomeLabel(), null);

        double successRate = switch (event.outcomeLabel()) {
            case "WIN"  -> 1.0;
            case "LOSS" -> 0.0;
            case "TIE"  -> 0.5;
            default     -> 0.5;
        };

        String storedCaseId = cbrStore.store(
                cbrCase,
                event.tenancyId(),
                event.caseId().toString(),
                DOMAIN,
                "sc2-cbr-retention",
                SC2GameCbrCase.CBR_TYPE,
                Path.of("quarkmind", "strategy", "cases"));

        cbrStore.recordOutcome(storedCaseId, SC2GameCbrCase.CBR_TYPE,
                CbrOutcome.of(successRate, event.outcomeLabel(), event.closedAt()));

        log.infof("[CBR-RETAIN] Stored: archetype=%s strategy=%s outcome=%s caseId=%s",
                archetype, strategyId, event.outcomeLabel(), storedCaseId);
    }
}
