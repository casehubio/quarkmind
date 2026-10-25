package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.inference.bayes.BayesNetwork;
import io.quarkmind.inference.bayes.CptSpec;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class LearnedCptLoader {

    private static final Logger log = Logger.getLogger(LearnedCptLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Race[] MATCHUP_RACES = {Race.TERRAN, Race.PROTOSS, Race.ZERG};

    @ConfigProperty(name = "quarkmind.detector.require-learned-cpts", defaultValue = "false")
    boolean requireLearnedCpts;

    public Map<String, BayesNetwork> loadFromClasspath() {
        Map<String, BayesNetwork> result = new HashMap<>();
        for (GamePhase phase : GamePhase.values()) {
            for (Race race : MATCHUP_RACES) {
                String resourcePath = "classifier/learned-cpts-"
                    + phase.name().toLowerCase() + "-vs-" + race.name().toLowerCase() + ".json";
                String key = phase.name() + "_vs_" + race.name().toLowerCase();
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        if (requireLearnedCpts) {
                            throw new IllegalStateException(
                                "Learned CPTs required but missing: " + resourcePath);
                        }
                        log.debugf("Learned CPTs not found: %s", resourcePath);
                        continue;
                    }
                    var resource = MAPPER.readValue(is, LearnedCptResource.class);
                    validate(resource);
                    result.put(key, resource.network().toBayesNetwork());
                    log.infof("Loaded learned CPTs: %s (%d replays, %d observations)",
                        key, resource.learnedFrom().replayCount(),
                        resource.learnedFrom().observationCount());
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    if (requireLearnedCpts) {
                        throw new IllegalStateException("Failed to load " + resourcePath, e);
                    }
                    log.warnf("Failed to load %s: %s", resourcePath, e.getMessage());
                }
            }
        }
        return Map.copyOf(result);
    }

    static void validate(LearnedCptResource resource) {
        var spec = resource.network();
        for (var varSpec : spec.variables()) {
            if (varSpec.cptSpec() instanceof CptSpec.DirectCpt direct) {
                for (double[] row : direct.values()) {
                    double sum = 0;
                    for (double v : row) sum += v;
                    if (Math.abs(sum - 1.0) > 0.01) {
                        throw new IllegalStateException(
                            "CPT row sums to " + sum + " for variable " + varSpec.name());
                    }
                }
            }
        }
    }
}
