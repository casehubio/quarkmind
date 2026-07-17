package io.quarkmind.agent.cbr;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.api.spi.routing.ImplementationCandidate;
import io.casehub.api.spi.routing.ImplementationRoutingContext;
import io.casehub.api.spi.routing.ImplementationSelection;
import io.casehub.api.spi.routing.RetrievedExperience;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustCandidateClassifier;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agent.GameSession;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.TaskDefinition;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.agent.plugin.StrategyTask;
import io.quarkmind.domain.EnemyArchetype;
import io.quarkmind.domain.EnemyPatternAssessment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

@ApplicationScoped
@CaseType("starcraft-game")
public class SC2StrategyRouterTask implements TaskDefinition {

    static final String FALLBACK = "strategy.drools";
    private static final Logger log = Logger.getLogger(SC2StrategyRouterTask.class);
    private static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");
    private static final String CAPABILITY = "strategy";

    private final ScoutingIntelBroker broker;
    private final CbrCaseMemoryStore cbrStore;
    private final GameSession gameSession;
    private final List<StrategyTask> strategies;
    private final double confidenceThreshold;
    private final int maxPivots;
    private final SC2ImplementationRoutingStrategy routingStrategy;

    private volatile String lastSelectedId = FALLBACK;

    @Inject
    public SC2StrategyRouterTask(
            ScoutingIntelBroker broker,
            CbrCaseMemoryStore cbrStore,
            GameSession gameSession,
            @Any Instance<StrategyTask> strategyTasks,
            TrustCandidateClassifier classifier,
            TrustScoreSource scoreSource,
            TrustRoutingPolicyProvider policyProvider,
            @ConfigProperty(name = "quarkmind.strategy.routing.confidence-threshold",
                    defaultValue = "0.6") double confidenceThreshold,
            @ConfigProperty(name = "quarkmind.strategy.routing.max-pivots",
                    defaultValue = "1") int maxPivots) {
        this(broker, cbrStore, gameSession,
                strategyTasks.stream().toList(),
                classifier, scoreSource, policyProvider,
                confidenceThreshold, maxPivots);
    }

    SC2StrategyRouterTask(
            ScoutingIntelBroker broker, CbrCaseMemoryStore cbrStore,
            GameSession gameSession, List<StrategyTask> strategies,
            TrustCandidateClassifier classifier, TrustScoreSource scoreSource,
            TrustRoutingPolicyProvider policyProvider,
            double confidenceThreshold, int maxPivots) {
        this.broker = broker;
        this.cbrStore = cbrStore;
        this.gameSession = gameSession;
        this.strategies = strategies;
        this.confidenceThreshold = confidenceThreshold;
        this.maxPivots = maxPivots;
        this.routingStrategy = new SC2ImplementationRoutingStrategy(classifier, scoreSource, policyProvider);
    }

    @Override
    public String getId() { return "strategy-routing.cbr"; }

    @Override
    public String getName() { return "SC2 CBR Strategy Router"; }

    @Override
    public Set<String> produces() {
        return Set.of(QuarkMindCaseFile.STRATEGY_SELECTED_ID, QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT);
    }

    @Override
    public Predicate<CaseContext> activateIf() { return ctx -> true; }

    public String lastSelectedId() { return lastSelectedId; }

    @Override
    public void execute(CaseContext ctx) {
        Optional<ScoutingIntelPayload> raw = broker.current(ScoutingIntelType.PATTERN_ASSESSMENT);
        if (raw.isEmpty()) {
            setFallbackIfAbsent(ctx);
            return;
        }

        ScoutingIntelPayload.PatternAssessment pa = (ScoutingIntelPayload.PatternAssessment) raw.get();
        if (pa.assessments().isEmpty()) {
            setFallbackIfAbsent(ctx);
            return;
        }

        EnemyPatternAssessment best = pa.assessments().getFirst();
        EnemyArchetype archetype = best.archetype();
        double confidence = best.confidence();

        if (confidence < confidenceThreshold) {
            setFallbackIfAbsent(ctx);
            return;
        }

        String raceName = archetype.race().name();
        String matchup = "Pv" + raceName.charAt(0);
        String contextKey = archetype.name() + "-" + raceName + "-" + matchup;

        String existingContext = ctx.getOrDefault(QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT, "");
        if (contextKey.equals(existingContext)) return;

        int pivotCount = ctx.getOrDefault(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT, -1);
        if (pivotCount >= maxPivots) return;

        List<ScoredCbrCase<SC2GameCbrCase>> retrieved = cbrStore.retrieveSimilar(
                CbrQuery.of("default", DOMAIN, Path.root(), SC2GameCbrCase.CBR_TYPE,
                                Map.of(
                                        "enemy_archetype", FeatureValue.string(archetype.name()),
                                        "enemy_race", FeatureValue.string(raceName),
                                        "matchup", FeatureValue.string(matchup),
                                        "assessment_confidence", FeatureValue.number(confidence)),
                                5)
                        .withWeights(Map.of(
                                "enemy_archetype", 0.5,
                                "enemy_race", 0.15,
                                "matchup", 0.15,
                                "assessment_confidence", 0.2))
                        .withMinSimilarity(0.3),
                SC2GameCbrCase.class);

        List<RetrievedExperience> experiences = retrieved.stream()
                .map(sc -> new RetrievedExperience(
                        sc.cbrCase().problem(), sc.cbrCase().solution(),
                        sc.cbrCase().outcome(), sc.cbrCase().confidence(),
                        sc.score(),
                        FeatureValue.toRawMap(sc.cbrCase().features()),
                        List.of(), sc.featureSimilarities()))
                .toList();

        List<ImplementationCandidate> candidates = strategies.stream()
                .map(s -> new ImplementationCandidate(s.getId(), s.getId(), CAPABILITY))
                .toList();

        ImplementationRoutingContext routingCtx = new ImplementationRoutingContext(
                gameSession.id(), CAPABILITY, null, "default", experiences);

        ImplementationSelection selection = routingStrategy.select(routingCtx, candidates)
                .await().indefinitely();

        String winner = switch (selection) {
            case ImplementationSelection.Selected s -> s.bindingNames().getFirst();
            case ImplementationSelection.RunAll ignored -> FALLBACK;
            case ImplementationSelection.RunNone ignored -> FALLBACK;
        };

        ctx.set(QuarkMindCaseFile.STRATEGY_SELECTED_ID, winner);
        ctx.set(QuarkMindCaseFile.STRATEGY_ROUTED_CONTEXT, contextKey);
        ctx.set(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE, archetype.name());
        ctx.set(QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE, confidence);
        ctx.set(QuarkMindCaseFile.STRATEGY_PIVOT_COUNT, pivotCount + 1);
        lastSelectedId = winner;

        log.infof("[CBR-ROUTE] %s -> %s (archetype=%s confidence=%.2f experiences=%d pivot=%d)",
                existingContext.isEmpty() ? "initial" : "pivot",
                winner, archetype, confidence, experiences.size(), pivotCount + 1);
    }

    private void setFallbackIfAbsent(CaseContext ctx) {
        if (ctx.getString(QuarkMindCaseFile.STRATEGY_SELECTED_ID) == null) {
            ctx.set(QuarkMindCaseFile.STRATEGY_SELECTED_ID, FALLBACK);
            lastSelectedId = FALLBACK;
        }
    }
}
