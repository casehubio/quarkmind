package io.quarkmind.agent.cbr;

import io.casehub.api.spi.CaseOutcomeEvent;
import io.casehub.api.spi.CaseOutcomeObserver;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agent.MultiFactorDominanceAssessor;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingConvergenceEvaluator;
import io.quarkmind.domain.PatternAssessment;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.DominanceScore;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.StrategyArchetype;
import io.quarkmind.plugin.summarisation.EngagementOutcome;
import io.quarkmind.plugin.summarisation.GameArc;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.GameMomentType;
import io.quarkmind.plugin.summarisation.MomentBroker;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class SC2CbrRetentionObserver implements CaseOutcomeObserver {

    private static final Logger       log    = Logger.getLogger(SC2CbrRetentionObserver.class);
    private static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");

    private final CbrCaseMemoryStore           cbrStore;
    private final SummarisationLifecycle       summarisationLifecycle;
    private final MomentBroker                 momentBroker;
    private final MultiFactorDominanceAssessor dominanceAssessor;
    private final TimelineSampler              timelineSampler;

    private final List<GameMoment>         moments   = new CopyOnWriteArrayList<>();
    private final List<TacticalPosture>    phases    = new CopyOnWriteArrayList<>();
    private final AtomicReference<GameArc> latestArc = new AtomicReference<>();

    @Inject
    public SC2CbrRetentionObserver(CbrCaseMemoryStore cbrStore,
                                   SummarisationLifecycle summarisationLifecycle,
                                   MomentBroker momentBroker,
                                   MultiFactorDominanceAssessor dominanceAssessor,
                                   TimelineSampler timelineSampler) {
        this.cbrStore               = cbrStore;
        this.summarisationLifecycle = summarisationLifecycle;
        this.momentBroker           = momentBroker;
        this.dominanceAssessor      = dominanceAssessor;
        this.timelineSampler        = timelineSampler;
    }

    @PostConstruct
    void subscribeToBuses() {
        if (momentBroker != null && momentBroker.momentBus() != null) {
            momentBroker.momentBus().subscribe(e -> true, this::collectMoment);
        }
        if (summarisationLifecycle != null) {
            if (summarisationLifecycle.phaseBus() != null) {
                summarisationLifecycle.phaseBus().subscribe(e -> true, this::collectPhase);
            }
            if (summarisationLifecycle.arcBus() != null) {
                summarisationLifecycle.arcBus().subscribe(e -> true, this::collectArc);
            }
        }
    }

    void collectMoment(LevelEvent<GameMoment> event) {
        moments.add(event.payload());
    }

    void collectPhase(LevelEvent<TacticalPosture> event) {
        phases.add(event.payload());
    }

    void collectArc(LevelEvent<GameArc> event) {
        latestArc.set(event.payload());
    }

    List<GameMoment> moments()     {return List.copyOf(moments);}

    List<TacticalPosture> phases() {return List.copyOf(phases);}

    GameArc latestArc()            {return latestArc.get();}

    void onGameStarted(@Observes GameStarted event) {
        moments.clear();
        phases.clear();
        latestArc.set(null);
    }

    @Override
    public void onOutcome(CaseOutcomeEvent event) {
        if ("UNKNOWN".equals(event.outcomeLabel())) {
            log.infof("[CBR-RETAIN] Game ended with unknown result — skipped");
            return;
        }

        Map<String, Object> snapshot  = event.caseFileSnapshot();
        String              archetype = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
        if (archetype == null) {
            log.infof("[CBR-RETAIN] No archetype in snapshot — skipped (no routing occurred)");
            return;
        }

        String strategyId = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_SELECTED_ID);
        Double confidence = (Double) snapshot.get(QuarkMindCaseFile.STRATEGY_ROUTED_CONFIDENCE);
        String raceName   = StrategyArchetype.valueOf(archetype).race().name();
        String matchup    = "Pv" + raceName.charAt(0);

        GameState gameState = (GameState) snapshot.get(QuarkMindCaseFile.GAME_STATE);
        DominanceScore dominance = gameState != null && dominanceAssessor != null
                                   ? dominanceAssessor.assess(gameState)
                                   : new DominanceScore(0.0, Map.of());

        List<String> phaseSequence = phases.stream()
                                           .map(TacticalPosture::posture).toList();
        int momentCount = moments.size();
        int battleCount = (int) moments.stream()
                                       .filter(m -> m.type() == GameMomentType.BATTLE_STARTED).count();
        int supplyBlockCount = (int) moments.stream()
                                            .filter(m -> m.type() == GameMomentType.SUPPLY_BLOCK).count();

        OptionalDouble firstContactMinute = moments.stream()
                                                   .filter(m -> m.type() == GameMomentType.FIRST_CONTACT)
                                                   .mapToDouble(m -> m.gameFrame() / SC2Data.GAME_LOOPS_PER_SECOND / 60.0)
                                                   .findFirst();

        Long scoutFrame = (Long) snapshot.get(QuarkMindCaseFile.SCOUTING_DISPATCH_FRAME);
        OptionalDouble scoutDispatchMinute = scoutFrame != null
                                             ? OptionalDouble.of(scoutFrame / SC2Data.GAME_LOOPS_PER_SECOND / 60.0)
                                             : OptionalDouble.empty();

        int expansionCount = gameState != null
                             ? (int) gameState.myBuildings().stream().filter(b -> isBase(b.type())).count() : 0;
        int workerCountFinal = gameState != null
                               ? (int) gameState.myUnits().stream().filter(u -> u.type().isWorker()).count() : 0;

        GameArc arc                 = latestArc.get();
        String  arcNarrative        = arc != null ? arc.narrative() : "";
        double  gameDurationMinutes = gameState != null ? gameState.gameTimeMinutes() : 0.0;
        String  opponentId          = (String) snapshot.getOrDefault(QuarkMindCaseFile.OPPONENT_ID, "unknown");

        List<EngagementOutcome> engagements = moments.stream()
                .filter(m -> m.type() == GameMomentType.BATTLE_ENDED)
                .map(m -> (EngagementOutcome) m.context().get("engagement"))
                .filter(java.util.Objects::nonNull)
                .toList();
        int engagementsWon = (int) engagements.stream()
                .filter(e -> e.outcome() == EngagementOutcome.Outcome.WON).count();
        int engagementsLost = (int) engagements.stream()
                .filter(e -> e.outcome() == EngagementOutcome.Outcome.LOST).count();
        int totalOwnValueLost = engagements.stream().mapToInt(EngagementOutcome::ownValueLost).sum();
        int totalEnemyValueLost = engagements.stream().mapToInt(EngagementOutcome::enemyValueLost).sum();
        double unitTradeRatio = totalOwnValueLost == 0
                ? (totalEnemyValueLost > 0 ? Double.MAX_VALUE : 0.0)
                : (double) totalEnemyValueLost / totalOwnValueLost;

        String initialArchetypeStr = (String) snapshot.get(QuarkMindCaseFile.STRATEGY_INITIAL_ARCHETYPE);
        @SuppressWarnings("unchecked")
        List<PatternAssessment> finalAssessments =
                (List<PatternAssessment>) snapshot.get(QuarkMindCaseFile.SCOUTING_FINAL_ASSESSMENT);

        double scoutingConvergence = 0.0;
        boolean assessmentStable = false;
        if (initialArchetypeStr != null && finalAssessments != null && !finalAssessments.isEmpty()) {
            var convergenceResult = ScoutingConvergenceEvaluator.evaluate(
                    StrategyArchetype.valueOf(initialArchetypeStr), finalAssessments);
            scoutingConvergence = convergenceResult.convergence();
            assessmentStable = convergenceResult.stable();
        }

        var enrichment = new EnrichedGameData(
                phaseSequence, momentCount, arcNarrative, gameDurationMinutes,
                battleCount, dominance.factors().getOrDefault("army", 0.0), dominance.overall(),
                expansionCount, workerCountFinal,
                dominance.factors().getOrDefault("economy", 0.0), supplyBlockCount,
                firstContactMinute, scoutDispatchMinute,
                confidence != null ? confidence : 0.0,
                opponentId,
                engagementsWon, engagementsLost, unitTradeRatio,
                scoutingConvergence, assessmentStable);

        SC2GameCbrCase cbrCase = SC2GameCbrCase.buildForGameEnriched(
                archetype, raceName, matchup,
                confidence != null ? confidence : 0.0, strategyId, enrichment,
                timelineSampler.getTimeline());

        Boolean cbrInfluenced = (Boolean) snapshot.get(QuarkMindCaseFile.CBR_INFLUENCED_SELECTION);
        if (cbrInfluenced != null) {
            var enrichedFeatures = new java.util.HashMap<>(cbrCase.features());
            enrichedFeatures.put("cbr_influenced", FeatureValue.string(cbrInfluenced.toString()));
            cbrCase = (SC2GameCbrCase) cbrCase.withFeatures(enrichedFeatures);
        }

        cbrCase = (SC2GameCbrCase) cbrCase.withOutcome(event.outcomeLabel(), null);

        double successRate = switch (event.outcomeLabel()) {
            case "WIN" -> 1.0;
            case "LOSS" -> 0.0;
            case "TIE" -> 0.5;
            default -> 0.5;
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

        log.infof("[CBR-RETAIN] Stored: archetype=%s strategy=%s outcome=%s enriched=%b caseId=%s",
                  archetype, strategyId, event.outcomeLabel(), !phaseSequence.isEmpty(), storedCaseId);
    }

    private static boolean isBase(BuildingType type) {
        return type == BuildingType.NEXUS || type == BuildingType.HATCHERY
               || type == BuildingType.LAIR || type == BuildingType.HIVE
               || type == BuildingType.COMMAND_CENTER || type == BuildingType.ORBITAL_COMMAND
               || type == BuildingType.PLANETARY_FORTRESS;
    }
}
