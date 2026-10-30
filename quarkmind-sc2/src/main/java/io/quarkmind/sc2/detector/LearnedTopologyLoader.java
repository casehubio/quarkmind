package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.GamePhase;
import io.quarkmind.domain.Race;
import io.quarkmind.inference.bayes.NetworkSpec;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class LearnedTopologyLoader {

    private static final Logger log = Logger.getLogger(LearnedTopologyLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Race[] MATCHUP_RACES = {Race.TERRAN, Race.PROTOSS, Race.ZERG};

    @ConfigProperty(name = "quarkmind.scouting.use-learned-topology", defaultValue = "false")
    boolean useLearnedTopology;

    public Map<String, NetworkSpec> loadFromClasspath() {
        if (!useLearnedTopology) {
            log.debug("Learned topology disabled — skipping load");
            return Map.of();
        }
        Map<String, NetworkSpec> result = new HashMap<>();
        for (GamePhase phase : GamePhase.values()) {
            for (Race race : MATCHUP_RACES) {
                String resourcePath = "classifier/learned-topology-"
                    + phase.name().toLowerCase() + "-vs-" + race.name().toLowerCase() + ".json";
                String key = phase.name() + "_vs_" + race.name().toLowerCase();
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        log.debugf("Learned topology not found: %s", resourcePath);
                        continue;
                    }
                    var resource = MAPPER.readValue(is, LearnedTopologyResource.class);
                    result.put(key, resource.network());
                    var prov = resource.learnedFrom();
                    log.infof("Loaded learned topology: %s (variant=%s, improvement=%.4f, replays=%d)",
                        key, resource.topology(), resource.improvement(),
                        prov != null ? prov.replayCount() : -1);
                } catch (Exception e) {
                    log.warnf("Failed to load %s: %s", resourcePath, e.getMessage());
                }
            }
        }
        return Map.copyOf(result);
    }
}
