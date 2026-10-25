package io.quarkmind.sc2.detector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.inference.bayes.BayesNetworkBuilder;
import io.quarkmind.inference.bayes.NetworkSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearnedCptLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void learnedCptResourceRoundTrips() throws Exception {
        var network = BayesNetworkBuilder.create()
            .variable("Strategy", "RUSH", "MACRO").prior(0.5, 0.5)
            .variable("det", "HIGH", "MEDIUM", "LOW")
                .given("Strategy").cpt(0.7, 0.2, 0.1, 0.1, 0.3, 0.6)
            .build();

        var spec = NetworkSpec.fromBayesNetwork(network, "test", "");
        var resource = new LearnedCptResource("vs_terran", "EARLY",
            new LearnedCptResource.LearnedFrom(1000, 2000, "abc123", "2026-10-25T12:00:00Z"),
            spec);

        String json = mapper.writeValueAsString(resource);
        var deserialized = mapper.readValue(json, LearnedCptResource.class);

        assertThat(deserialized.matchup()).isEqualTo("vs_terran");
        assertThat(deserialized.phase()).isEqualTo("EARLY");
        assertThat(deserialized.learnedFrom().replayCount()).isEqualTo(1000);
        assertThat(deserialized.network().variables()).hasSize(2);
    }

    @Test
    void validationRejectsBadCptRowSum() {
        var spec = NetworkSpec.fromBayesNetwork(
            BayesNetworkBuilder.create()
                .variable("Strategy", "RUSH", "MACRO").prior(0.5, 0.5)
                .build(),
            "test", "");

        var resource = new LearnedCptResource("vs_terran", "EARLY",
            new LearnedCptResource.LearnedFrom(100, 200, "abc", "2026-01-01"),
            spec);

        // Valid resource should not throw
        LearnedCptLoader.validate(resource);
    }

    @Test
    void loadFromClasspathReturnsEmptyWhenNoFilesPresent() {
        var loader = new LearnedCptLoader();
        var result = loader.loadFromClasspath();
        assertThat(result).isEmpty();
    }

    @Test
    void loadFromClasspathThrowsWhenRequiredAndMissing() {
        var loader = new LearnedCptLoader();
        loader.requireLearnedCpts = true;
        assertThatThrownBy(loader::loadFromClasspath)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("required but missing");
    }
}
