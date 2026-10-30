package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.inference.bayes.NetworkSpec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LearnedTopologyLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loadLearnedTopology_whenResourceExists_deserializesCorrectly() throws Exception {
        String resourcePath = "priors/learned-topology-EARLY-vs_protoss.json";
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            System.out.println("SKIP: no learned topology resource at " + resourcePath);
            return;
        }

        @SuppressWarnings("unchecked")
        var resource = MAPPER.readValue(is, Map.class);
        assertNotNull(resource.get("network"));
        assertNotNull(resource.get("matchup"));
        assertNotNull(resource.get("phase"));
    }

    @Test
    void learnedTopologyConfig_defaultsToFalse() {
        assertFalse(Boolean.getBoolean("quarkmind.scouting.use-learned-topology"));
    }
}
