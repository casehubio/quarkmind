package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearnedTopologyLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void loadLearnedTopology_whenResourceExists_deserializesCorrectly() throws Exception {
        String resourcePath = "classifier/learned-topology-early-vs-protoss.json";
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            System.out.println("SKIP: no learned topology resource at " + resourcePath);
            return;
        }

        var resource = MAPPER.readValue(is, LearnedTopologyResource.class);
        assertNotNull(resource.network());
        assertNotNull(resource.matchup());
        assertNotNull(resource.phase());
        assertNotNull(resource.topology());
        if (resource.learnedFrom() != null) {
            assertTrue(resource.learnedFrom().replayCount() > 0);
            assertNotNull(resource.learnedFrom().winningVariant());
        }
    }

    @Test
    void learnedTopologyConfig_defaultsToFalse() {
        assertFalse(Boolean.getBoolean("quarkmind.scouting.use-learned-topology"));
    }
}
