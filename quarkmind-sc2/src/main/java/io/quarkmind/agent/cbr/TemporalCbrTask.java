package io.quarkmind.agent.cbr;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import io.quarkmind.agency.task.TaskDefinition;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.TemporalPrediction;
import io.quarkmind.domain.TimelineObservation;
import io.quarkmind.plugin.summarisation.SummarisationLifecycle;
import io.quarkmind.plugin.summarisation.TacticalPosture;
import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

@ApplicationScoped
@CaseType("starcraft-game")
public class TemporalCbrTask implements TaskDefinition {

    static final         long         QUERY_INTERVAL_FRAMES = 2688;
    private static final int          TOP_K                 = 5;
    private static final double       MIN_SIMILARITY        = 0.3;
    private static final MemoryDomain DOMAIN                = new MemoryDomain("quarkmind");

    private final CbrCaseMemoryStore     cbrStore;
    private final TimelineSampler        timelineSampler;
    private final SummarisationLifecycle summarisationLifecycle;

    private final List<TacticalPosture> phases         = new CopyOnWriteArrayList<>();
    private       long                  lastQueryFrame = -QUERY_INTERVAL_FRAMES;

    @Inject
    public TemporalCbrTask(CbrCaseMemoryStore cbrStore,
                           TimelineSampler timelineSampler,
                           SummarisationLifecycle summarisationLifecycle) {
        this.cbrStore               = cbrStore;
        this.timelineSampler        = timelineSampler;
        this.summarisationLifecycle = summarisationLifecycle;
        if (summarisationLifecycle != null && summarisationLifecycle.phaseBus() != null) {
            summarisationLifecycle.phaseBus().subscribe(e -> true, e -> phases.add(e.payload()));
        }
    }

    private static int findClosestTimestampIndex(List<Map<String, FeatureValue>> timeline, double targetMinute) {
        int    closest = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < timeline.size(); i++) {
            double minute = ((FeatureValue.NumberVal) timeline.get(i).get("minute")).value();
            double diff   = Math.abs(minute - targetMinute);
            if (diff < minDiff) {
                minDiff = diff;
                closest = i;
            }
        }
        return closest;
    }

    void onGameStarted(@Observes GameStarted event) {
        phases.clear();
        lastQueryFrame = -QUERY_INTERVAL_FRAMES;
    }

    @Override
    public String getId() {return "temporal-cbr.predict";}

    @Override
    public String getName() {return "Temporal CBR Prediction";}

    @Override
    public Set<String> requires() {
        return Set.of(QuarkMindCaseFile.GAME_STATE, QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
    }

    @Override
    public Set<String> produces() {
        return Set.of(
                QuarkMindCaseFile.TEMPORAL_PREDICTION,
                QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT,
                QuarkMindCaseFile.TEMPORAL_SIMILAR_BEST_SCORE);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> timelineSampler.getTimeline().size() >= 4;
    }

    @Override
    public void execute(CaseContext context) {
        var gameState = (GameState) context.get(QuarkMindCaseFile.GAME_STATE);
        if (gameState.gameFrame() - lastQueryFrame < QUERY_INTERVAL_FRAMES) {return;}
        lastQueryFrame = gameState.gameFrame();

        var timeline      = timelineSampler.getTimeline();
        var phaseSequence = phases.stream().map(TacticalPosture::posture).toList();
        var archetype     = (String) context.get(QuarkMindCaseFile.STRATEGY_ROUTED_ARCHETYPE);
        var enemyRace     = (String) context.get(QuarkMindCaseFile.ENEMY_RACE);
        var matchup       = enemyRace != null ? "Pv" + enemyRace.charAt(0) : null;

        var queryFeatures = new HashMap<String, FeatureValue>();
        queryFeatures.put("timeline", toStructListVal(timeline));
        if (!phaseSequence.isEmpty()) {
            queryFeatures.put("phase_sequence", FeatureValue.stringList(phaseSequence));
        }
        if (archetype != null) {queryFeatures.put("enemy_archetype", FeatureValue.string(archetype));}
        if (matchup != null) {queryFeatures.put("matchup", FeatureValue.string(matchup));}

        var query = CbrQuery.of("default", DOMAIN, Path.of("quarkmind", "strategy", "cases"),
                                SC2GameCbrCase.CBR_TYPE, Map.copyOf(queryFeatures), TOP_K)
                            .withWeights(Map.of("timeline", 0.50, "phase_sequence", 0.30,
                                                "enemy_archetype", 0.10, "matchup", 0.10))
                            .withMinSimilarity(MIN_SIMILARITY);

        var results = cbrStore.retrieveSimilar(query, SC2GameCbrCase.class);
        if (results.isEmpty()) {return;}

        var prediction = extractPrediction(timeline, results);
        if (prediction != null) {
            context.set(QuarkMindCaseFile.TEMPORAL_PREDICTION, prediction);
            context.set(QuarkMindCaseFile.TEMPORAL_SIMILAR_COUNT, results.size());
            context.set(QuarkMindCaseFile.TEMPORAL_SIMILAR_BEST_SCORE, results.getFirst().score());
        }
    }

    TemporalPrediction extractPrediction(
            List<TimelineObservation> queryTimeline,
            List<ScoredCbrCase<SC2GameCbrCase>> results) {

        double queryEndMinute = queryTimeline.getLast().minute();

        Map<String, Integer>            phaseVotes = new HashMap<>();
        List<List<TimelineObservation>> lookaheads = new ArrayList<>();

        for (var scored : results) {
            var caseFeatures = scored.cbrCase().features();
            if (!caseFeatures.containsKey("timeline")) {continue;}

            var caseTimeline = ((FeatureValue.StructListVal) caseFeatures.get("timeline")).items();

            int alignmentIdx = findClosestTimestampIndex(caseTimeline, queryEndMinute);
            if (alignmentIdx + 1 >= caseTimeline.size()) {continue;}

            int lookaheadEnd = Math.min(caseTimeline.size(), alignmentIdx + 5);
            var lookahead    = new ArrayList<TimelineObservation>();
            for (int i = alignmentIdx + 1; i < lookaheadEnd; i++) {
                var obs = caseTimeline.get(i);
                lookahead.add(new TimelineObservation(
                        ((FeatureValue.NumberVal) obs.get("minute")).value(),
                        (int) ((FeatureValue.NumberVal) obs.get("our_workers")).value(),
                        (int) ((FeatureValue.NumberVal) obs.get("our_minerals")).value(),
                        (int) ((FeatureValue.NumberVal) obs.get("our_army_supply")).value()));
            }
            lookaheads.add(lookahead);

            if (caseFeatures.containsKey("phase_sequence")) {
                var casePhases = ((FeatureValue.StringListVal) caseFeatures.get("phase_sequence")).values();
                for (int i = 0; i < casePhases.size() - 1; i++) {
                    if (!casePhases.get(i).equals(casePhases.get(i + 1))) {
                        phaseVotes.merge(casePhases.get(i + 1), 1, Integer::sum);
                        break;
                    }
                }
            }
        }

        if (lookaheads.isEmpty()) {return null;}

        var bestLookahead = lookaheads.getFirst();
        var economyTrend  = TemporalPrediction.computeEconomyTrend(bestLookahead);
        var armyTrend     = TemporalPrediction.computeArmyTrend(bestLookahead);

        var bestPhase = phaseVotes.entrySet().stream()
                                  .max(Map.Entry.comparingByValue())
                                  .map(Map.Entry::getKey)
                                  .orElse("UNKNOWN");
        int agreeCount = phaseVotes.getOrDefault(bestPhase, 0);

        double confidence = TemporalPrediction.computeConfidence(
                results.getFirst().score(), agreeCount, results.size());

        return new TemporalPrediction(
                bestPhase, economyTrend, armyTrend,
                bestLookahead.isEmpty() ? 0 :
                bestLookahead.getLast().minute() - queryTimeline.getLast().minute(),
                confidence, results.size(), results.getFirst().score());
    }

    private FeatureValue toStructListVal(List<TimelineObservation> timeline) {
        return FeatureValue.structList(timeline.stream()
                                               .map(t -> Map.<String, FeatureValue>of(
                                                       "minute", FeatureValue.number(t.minute()),
                                                       "our_workers", FeatureValue.number(t.ourWorkers()),
                                                       "our_minerals", FeatureValue.number(t.ourMinerals()),
                                                       "our_army_supply", FeatureValue.number(t.ourArmySupply())))
                                               .toList());
    }

}
