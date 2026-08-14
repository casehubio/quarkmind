package io.quarkmind.qa;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.agent.cbr.SC2GameCbrCase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@UnlessBuildProfile("prod")
@jakarta.ws.rs.Path("/qa/cbr")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CbrLearningCurveEndpoint {

    private static final MemoryDomain DOMAIN = new MemoryDomain("quarkmind");

    @Inject CbrCaseMemoryStore cbrStore;

    @GET @jakarta.ws.rs.Path("/learning-curve")
    public Response learningCurve() {
        var cases = retrieveAllCases();
        if (cases.isEmpty()) {
            return Response.ok(Map.of("totalGames", 0, "overallWinRate", 0.0,
                    "last10WinRate", 0.0, "last20WinRate", 0.0,
                    "trend", "STABLE", "perMatchup", Map.of())).build();
        }
        cases.sort(Comparator.comparing(sc -> sc.storedAt() != null ? sc.storedAt() : java.time.Instant.EPOCH));

        double overallWinRate = winRate(cases);
        double last10 = winRate(tail(cases, 10));
        double last20 = winRate(tail(cases, 20));
        double first10 = winRate(head(cases, 10));
        String trend = last10 - first10 > 0.1 ? "IMPROVING"
                : first10 - last10 > 0.1 ? "DECLINING" : "STABLE";

        var perMatchup = cases.stream()
                .collect(Collectors.groupingBy(c -> featureString(c, "matchup")))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> Map.of("games", e.getValue().size(),
                                "winRate", winRate(e.getValue()))));

        var result = new LinkedHashMap<String, Object>();
        result.put("totalGames", cases.size());
        result.put("overallWinRate", overallWinRate);
        result.put("last10WinRate", last10);
        result.put("last20WinRate", last20);
        result.put("trend", trend);
        result.put("perMatchup", perMatchup);
        return Response.ok(result).build();
    }

    @GET @jakarta.ws.rs.Path("/strategy-evolution")
    public Response strategyEvolution() {
        var cases = retrieveAllCases();
        var byStrategy = cases.stream()
                .collect(Collectors.groupingBy(c -> c.cbrCase().solution()));

        var strategies = byStrategy.entrySet().stream().map(e -> {
            var strategyCases = e.getValue();
            var perArchetype = strategyCases.stream()
                    .collect(Collectors.groupingBy(c -> featureString(c, "enemy_archetype")))
                    .entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey,
                            ae -> Map.of("games", ae.getValue().size(),
                                    "winRate", winRate(ae.getValue()))));
            var m = new LinkedHashMap<String, Object>();
            m.put("strategyId", e.getKey());
            m.put("selectionRate", cases.isEmpty() ? 0.0 : (double) strategyCases.size() / cases.size());
            m.put("winRate", winRate(strategyCases));
            m.put("games", strategyCases.size());
            m.put("perArchetype", perArchetype);
            return m;
        }).toList();

        return Response.ok(Map.of("strategies", strategies)).build();
    }

    @GET @jakarta.ws.rs.Path("/case-stats")
    public Response caseStats() {
        var strategyCases = retrieveAllCases();
        int tier2Count = (int) strategyCases.stream()
                .filter(c -> c.cbrCase().features().containsKey("moment_count"))
                .count();
        double tier2Coverage = strategyCases.isEmpty() ? 0.0
                : (double) tier2Count / strategyCases.size();

        long influencedCount = strategyCases.stream()
                .filter(c -> {
                    var v = c.cbrCase().features().get("cbr_influenced");
                    return v != null && "true".equals(String.valueOf(v.toRawValue()));
                }).count();
        double influenceRate = strategyCases.isEmpty() ? 0.0
                : (double) influencedCount / strategyCases.size();

        var perOpponent = strategyCases.stream()
                .filter(c -> c.cbrCase().features().containsKey("opponent_id"))
                .collect(Collectors.groupingBy(c -> featureString(c, "opponent_id"),
                        Collectors.counting()));

        var result = new LinkedHashMap<String, Object>();
        result.put("totalCases", strategyCases.size());
        result.put("tier2Coverage", tier2Coverage);
        result.put("retrievalInfluenceRate", influenceRate);
        result.put("perOpponent", perOpponent);
        return Response.ok(result).build();
    }

    private List<ScoredCbrCase<SC2GameCbrCase>> retrieveAllCases() {
        var query = CbrQuery.of("default", DOMAIN,
                Path.of("quarkmind", "strategy", "cases"),
                SC2GameCbrCase.CBR_TYPE, Map.of(), 1000)
                .withMinSimilarity(0.0)
                .withRetrievalMode(RetrievalMode.FEATURE_ONLY);
        return new ArrayList<>(cbrStore.retrieveSimilar(query, SC2GameCbrCase.class));
    }

    private static double winRate(List<? extends ScoredCbrCase<?>> cases) {
        if (cases.isEmpty()) return 0.0;
        long wins = cases.stream().filter(c -> "WIN".equals(c.cbrCase().outcome())).count();
        return (double) wins / cases.size();
    }

    private static <T> List<T> tail(List<T> list, int n) {
        return list.subList(Math.max(0, list.size() - n), list.size());
    }

    private static <T> List<T> head(List<T> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    private static String featureString(ScoredCbrCase<?> sc, String key) {
        var v = sc.cbrCase().features().get(key);
        return v != null ? String.valueOf(v.toRawValue()) : "unknown";
    }
}
