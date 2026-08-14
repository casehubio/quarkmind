package io.quarkmind.qa.workbench;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.agent.StrategyTaxonomy;
import io.quarkmind.agent.cbr.StrategySelectionPublished;
import io.quarkmind.agent.plugin.PatternAssessmentPublished;
import io.quarkmind.domain.CounterInfo;
import io.quarkmind.plugin.coaching.CoachingAdvicePublished;
import io.quarkmind.plugin.coaching.CoachingComplianceResolved;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@UnlessBuildProfile("prod")
@ApplicationScoped
public class WorkbenchEnricher {

    private static final Logger log = Logger.getLogger(WorkbenchEnricher.class);

    private final StrategyTaxonomy taxonomy;
    private final WorkbenchBroadcaster broadcaster;

    @Inject
    WorkbenchEnricher(StrategyTaxonomy taxonomy, WorkbenchBroadcaster broadcaster) {
        this.taxonomy = taxonomy;
        this.broadcaster = broadcaster;
    }

    void onPatternAssessment(@Observes PatternAssessmentPublished event) {
        var enriched = event.assessments().stream()
            .map(a -> {
                CounterInfo counters = null;
                try { counters = taxonomy.countersFor(a.archetype()); }
                catch (Exception e) { log.debugf("No counters for %s: %s", a.archetype(), e.getMessage()); }
                return new EnrichedAssessment(a, counters);
            })
            .toList();
        broadcaster.broadcast(new WorkbenchEvent("pattern", new PatternPayload(enriched)));
    }

    void onCoachingAdvice(@Observes CoachingAdvicePublished event) {
        broadcaster.broadcast(new WorkbenchEvent("coaching",
            new CoachingPayload(event.advice().advice(), event.advice().domainTag(), event.urgencyTier(), event.gameFrame(), event.correlationId())));
    }

    void onCoachingCompliance(@Observes CoachingComplianceResolved event) {
        broadcaster.broadcast(new WorkbenchEvent("coaching_compliance",
            new CoachingCompliancePayload(event.gameFrame(), event.domain(), event.status(), event.correlationId())));
    }

    void onStrategySelection(@Observes StrategySelectionPublished event) {
        broadcaster.broadcast(new WorkbenchEvent("strategy",
            new StrategyPayload(event.strategyId(), event.archetype(), event.confidence(), event.pivotCount())));
    }
}
