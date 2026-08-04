package io.quarkmind.plugin.scouting;

import io.quarkmind.domain.Race;
import io.quarkmind.domain.StrategyArchetype;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPatternClassifierWorkerFactoryTest {

    @Test
    void systemPrompt_filtersArchetypesByRace() {
        Map<String, Double> confidences = Map.of(
            "PROTOSS_GATEWAY_RUSH", 0.3,
            "PROTOSS_MACRO", 0.2
        );
        String prompt = LlmPatternClassifierWorkerFactory.buildSystemPrompt(Race.PROTOSS, confidences);

        assertThat(prompt).contains("PROTOSS_GATEWAY_RUSH");
        assertThat(prompt).contains("PROTOSS_MACRO");
        assertThat(prompt).doesNotContain("TERRAN_MARINE_RUSH");
        assertThat(prompt).doesNotContain("ZERG_ZERGLING_RUSH");
        assertThat(prompt).contains("0.30");
        assertThat(prompt).contains("0.20");
    }

    @Test
    void systemPrompt_includesAllArchetypesForRace() {
        String prompt = LlmPatternClassifierWorkerFactory.buildSystemPrompt(Race.TERRAN, Map.of());

        for (StrategyArchetype a : StrategyArchetype.values()) {
            if (a.race() == Race.TERRAN) {
                assertThat(prompt).contains(a.name());
            }
        }
    }

    @Test
    void systemPrompt_excludesOtherRaces() {
        String prompt = LlmPatternClassifierWorkerFactory.buildSystemPrompt(Race.ZERG, Map.of());

        for (StrategyArchetype a : StrategyArchetype.values()) {
            if (a.race() != Race.ZERG) {
                assertThat(prompt).doesNotContain("- " + a.name());
            }
        }
    }

    @Test
    void userMessage_formatsTimelineChronologically() {
        List<Map<String, Object>> timeline = List.of(
            Map.of("unitType", "ZEALOT", "gameTimeMs", 60000L),
            Map.of("unitType", "STALKER", "gameTimeMs", 120000L)
        );
        String msg = LlmPatternClassifierWorkerFactory.buildUserMessage(timeline, 3000L);

        assertThat(msg).contains("60.0s — ZEALOT");
        assertThat(msg).contains("120.0s — STALKER");
        int zealotIdx = msg.indexOf("ZEALOT");
        int stalkerIdx = msg.indexOf("STALKER");
        assertThat(zealotIdx).isLessThan(stalkerIdx);
    }

    @Test
    void userMessage_includesGameTime() {
        String msg = LlmPatternClassifierWorkerFactory.buildUserMessage(List.of(), 2160L);
        assertThat(msg).contains("3:00");
    }

    @Test
    void extractSection_validResponse() {
        String response = "ARCHETYPE: PROTOSS_GATEWAY_RUSH\nCONFIDENCE: 0.75\nRATIONALE: Early gateway with zealots";
        assertThat(LlmPatternClassifierWorkerFactory.extractSection(response, "ARCHETYPE"))
            .isEqualTo("PROTOSS_GATEWAY_RUSH");
        assertThat(LlmPatternClassifierWorkerFactory.extractSection(response, "CONFIDENCE"))
            .isEqualTo("0.75");
        assertThat(LlmPatternClassifierWorkerFactory.extractSection(response, "RATIONALE"))
            .isEqualTo("Early gateway with zealots");
    }

    @Test
    void extractSection_missingLabel_returnsEmpty() {
        assertThat(LlmPatternClassifierWorkerFactory.extractSection("some text", "ARCHETYPE"))
            .isEmpty();
    }

    @Test
    void extractSection_nullInput_returnsEmpty() {
        assertThat(LlmPatternClassifierWorkerFactory.extractSection(null, "ARCHETYPE"))
            .isEmpty();
    }
}
