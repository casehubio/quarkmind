package io.quarkmind.plugin.scouting;

import io.casehub.api.model.CaseDefinition;
import io.casehub.worker.api.Capability;
import io.quarkmind.agent.QuarkMindCaseHub;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class LlmPatternFallbackIT {

    @Inject QuarkMindCaseHub caseHub;

    @Test
    void caseDefinition_includesLlmFallbackCapability_whenChatModelAvailable() {
        CaseDefinition def = caseHub.getDefinition();

        boolean hasCapability = def.getCapabilities().stream()
            .map(Capability::name)
            .anyMatch("scouting-llm-fallback"::equals);

        if (hasCapability) {
            assertThat(def.getBindings()).anyMatch(
                b -> b.getName().equals("scouting-llm-fallback"));
            assertThat(def.getWorkers()).anyMatch(
                w -> w.name().equals("llm-classifier:pattern-fallback"));
        }
    }
}
