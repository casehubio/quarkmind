package io.quarkmind.plugin.advisory;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.platform.api.identity.TenancyConstants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for QuarkMindAgentRegistrar.
 * <p>
 * Verifies 10 agent configurations (6 advisory + 4 commentary) with correct:
 * - Agent ID format: {model-family}:{persona}@{major}
 * - Capability names and tags
 * - Disposition traits mapped to ConscientiousnessTerm values
 * - Latency hints matching role profile
 * - Slot, provider, and tenancy fields
 */
class QuarkMindAgentRegistrarTest {

    @Test
    void registrar_returns_ten_agent_descriptors() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();

        assertThat(descriptors).hasSize(10);
    }

    @Test
    void registrar_returns_six_advisory_descriptors() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();
        long advisoryCount = descriptors.stream()
                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().startsWith("advisory-")))
                .count();

        assertThat(advisoryCount).isEqualTo(6);
    }

    @Test
    void registrar_returns_four_commentary_descriptors() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();
        long commentaryCount = descriptors.stream()
                .filter(d -> d.capabilities().stream().anyMatch(c -> c.name().startsWith("commentary-")))
                .count();

        assertThat(commentaryCount).isEqualTo(4);
    }

    @Test
    void crisis_advisors_have_correct_configurations() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();
        List<AgentDescriptor> crisisAdvisors = descriptors.stream()
                .filter(d -> d.slot().equals("crisis-responder"))
                .toList();

        assertThat(crisisAdvisors).hasSize(2);

        // Aggressive configuration
        AgentDescriptor aggressive = crisisAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:crisis-aggressive@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(aggressive.name()).isEqualTo("Aggressive Crisis Responder");
        assertThat(aggressive.provider()).isEqualTo("anthropic");
        assertThat(aggressive.modelFamily()).isEqualTo("claude");
        assertThat(aggressive.modelVersion()).isEqualTo("sonnet-4");
        assertThat(aggressive.slot()).isEqualTo("crisis-responder");
        assertThat(aggressive.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(aggressive.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition aggressiveDisp = aggressive.disposition();
        assertThat(aggressiveDisp.socialOrient()).isEqualTo("collaborative");
        assertThat(aggressiveDisp.ruleFollowing()).isEqualTo("flexible");
        assertThat(aggressiveDisp.riskAppetite()).isEqualTo("bold");
        assertThat(aggressiveDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(aggressiveDisp.conflictMode()).isEqualTo("compete");
        assertThat(aggressiveDisp.delegation()).isFalse();

        List<AgentCapability> aggressiveCaps = aggressive.capabilities();
        assertThat(aggressiveCaps).hasSize(1);
        AgentCapability cap = aggressiveCaps.get(0);
        assertThat(cap.name()).isEqualTo("advisory-crisis");
        assertThat(cap.latencyHintP50Ms()).isEqualTo(1500L);
        assertThat(cap.qualityHint()).isEqualTo(0.7);
        assertThat(cap.tags()).containsExactly("starcraft.advisory.crisis");

        // Conservative configuration
        AgentDescriptor conservative = crisisAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:crisis-conservative@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(conservative.name()).isEqualTo("Conservative Crisis Responder");
        assertThat(conservative.provider()).isEqualTo("anthropic");
        assertThat(conservative.modelFamily()).isEqualTo("claude");
        assertThat(conservative.modelVersion()).isEqualTo("sonnet-4");
        assertThat(conservative.slot()).isEqualTo("crisis-responder");
        assertThat(conservative.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(conservative.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition conservativeDisp = conservative.disposition();
        assertThat(conservativeDisp.socialOrient()).isEqualTo("collaborative");
        assertThat(conservativeDisp.ruleFollowing()).isEqualTo("strict");
        assertThat(conservativeDisp.riskAppetite()).isEqualTo("conservative");
        assertThat(conservativeDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(conservativeDisp.conflictMode()).isEqualTo("compete");
        assertThat(conservativeDisp.delegation()).isFalse();

        List<AgentCapability> conservativeCaps = conservative.capabilities();
        assertThat(conservativeCaps).hasSize(1);
        AgentCapability conservativeCap = conservativeCaps.get(0);
        assertThat(conservativeCap.name()).isEqualTo("advisory-crisis");
        assertThat(conservativeCap.latencyHintP50Ms()).isEqualTo(1500L);
        assertThat(conservativeCap.qualityHint()).isEqualTo(0.7);
        assertThat(conservativeCap.tags()).containsExactly("starcraft.advisory.crisis");
    }

    @Test
    void strategic_advisors_have_correct_configurations() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();
        List<AgentDescriptor> strategicAdvisors = descriptors.stream()
                .filter(d -> d.slot().equals("strategic-advisor"))
                .toList();

        assertThat(strategicAdvisors).hasSize(2);

        // Bold configuration
        AgentDescriptor bold = strategicAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:strategic-bold@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(bold.name()).isEqualTo("Bold Strategic Advisor");
        assertThat(bold.provider()).isEqualTo("anthropic");
        assertThat(bold.modelFamily()).isEqualTo("claude");
        assertThat(bold.modelVersion()).isEqualTo("sonnet-4");
        assertThat(bold.slot()).isEqualTo("strategic-advisor");
        assertThat(bold.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(bold.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition boldDisp = bold.disposition();
        assertThat(boldDisp.socialOrient()).isEqualTo("collaborative");
        assertThat(boldDisp.ruleFollowing()).isEqualTo("flexible");
        assertThat(boldDisp.riskAppetite()).isEqualTo("bold");
        assertThat(boldDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(boldDisp.conflictMode()).isEqualTo("collaborate");
        assertThat(boldDisp.delegation()).isFalse();

        List<AgentCapability> boldCaps = bold.capabilities();
        assertThat(boldCaps).hasSize(1);
        AgentCapability cap = boldCaps.get(0);
        assertThat(cap.name()).isEqualTo("advisory-strategic");
        assertThat(cap.latencyHintP50Ms()).isEqualTo(3000L);
        assertThat(cap.qualityHint()).isEqualTo(0.7);
        assertThat(cap.tags()).containsExactly("starcraft.advisory.strategic");

        // Measured configuration
        AgentDescriptor measured = strategicAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:strategic-measured@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(measured.name()).isEqualTo("Measured Strategic Advisor");
        assertThat(measured.provider()).isEqualTo("anthropic");
        assertThat(measured.modelFamily()).isEqualTo("claude");
        assertThat(measured.modelVersion()).isEqualTo("sonnet-4");
        assertThat(measured.slot()).isEqualTo("strategic-advisor");
        assertThat(measured.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(measured.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition measuredDisp = measured.disposition();
        assertThat(measuredDisp.socialOrient()).isEqualTo("collaborative");
        assertThat(measuredDisp.ruleFollowing()).isEqualTo("principled");
        assertThat(measuredDisp.riskAppetite()).isEqualTo("measured");
        assertThat(measuredDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(measuredDisp.conflictMode()).isEqualTo("collaborate");
        assertThat(measuredDisp.delegation()).isFalse();

        List<AgentCapability> measuredCaps = measured.capabilities();
        assertThat(measuredCaps).hasSize(1);
        AgentCapability measuredCap = measuredCaps.get(0);
        assertThat(measuredCap.name()).isEqualTo("advisory-strategic");
        assertThat(measuredCap.latencyHintP50Ms()).isEqualTo(3000L);
        assertThat(measuredCap.qualityHint()).isEqualTo(0.7);
        assertThat(measuredCap.tags()).containsExactly("starcraft.advisory.strategic");
    }

    @Test
    void economic_advisors_have_correct_configurations() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();
        List<AgentDescriptor> economicAdvisors = descriptors.stream()
                .filter(d -> d.slot().equals("economic-optimizer"))
                .toList();

        assertThat(economicAdvisors).hasSize(2);

        // Expansion configuration
        AgentDescriptor expansion = economicAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:economic-expansion@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(expansion.name()).isEqualTo("Expansion Economic Optimizer");
        assertThat(expansion.provider()).isEqualTo("anthropic");
        assertThat(expansion.modelFamily()).isEqualTo("claude");
        assertThat(expansion.modelVersion()).isEqualTo("sonnet-4");
        assertThat(expansion.slot()).isEqualTo("economic-optimizer");
        assertThat(expansion.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(expansion.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition expansionDisp = expansion.disposition();
        assertThat(expansionDisp.socialOrient()).isEqualTo("collaborative");
        assertThat(expansionDisp.ruleFollowing()).isEqualTo("flexible");
        assertThat(expansionDisp.riskAppetite()).isEqualTo("bold");
        assertThat(expansionDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(expansionDisp.conflictMode()).isEqualTo("collaborate");
        assertThat(expansionDisp.delegation()).isFalse();

        List<AgentCapability> expansionCaps = expansion.capabilities();
        assertThat(expansionCaps).hasSize(1);
        AgentCapability cap = expansionCaps.get(0);
        assertThat(cap.name()).isEqualTo("advisory-economic");
        assertThat(cap.latencyHintP50Ms()).isEqualTo(2500L);
        assertThat(cap.qualityHint()).isEqualTo(0.7);
        assertThat(cap.tags()).containsExactly("starcraft.advisory.economic");

        // Defensive configuration
        AgentDescriptor defensive = economicAdvisors.stream()
                .filter(d -> d.agentId().equals("claude:economic-defensive@v1"))
                .findFirst()
                .orElseThrow();

        assertThat(defensive.name()).isEqualTo("Defensive Economic Optimizer");
        assertThat(defensive.provider()).isEqualTo("anthropic");
        assertThat(defensive.modelFamily()).isEqualTo("claude");
        assertThat(defensive.modelVersion()).isEqualTo("sonnet-4");
        assertThat(defensive.slot()).isEqualTo("economic-optimizer");
        assertThat(defensive.slotVocabulary()).isEqualTo("urn:casehub:vocab:conscientiousness");
        assertThat(defensive.tenancyId()).isEqualTo(TenancyConstants.DEFAULT_TENANT_ID);

        AgentDisposition defensiveDisp = defensive.disposition();
        assertThat(defensiveDisp.socialOrient()).isEqualTo("independent");
        assertThat(defensiveDisp.ruleFollowing()).isEqualTo("principled");
        assertThat(defensiveDisp.riskAppetite()).isEqualTo("conservative");
        assertThat(defensiveDisp.autonomy()).isEqualTo("semi-autonomous");
        assertThat(defensiveDisp.conflictMode()).isEqualTo("avoid");
        assertThat(defensiveDisp.delegation()).isFalse();

        List<AgentCapability> defensiveCaps = defensive.capabilities();
        assertThat(defensiveCaps).hasSize(1);
        AgentCapability defensiveCap = defensiveCaps.get(0);
        assertThat(defensiveCap.name()).isEqualTo("advisory-economic");
        assertThat(defensiveCap.latencyHintP50Ms()).isEqualTo(2500L);
        assertThat(defensiveCap.qualityHint()).isEqualTo(0.7);
        assertThat(defensiveCap.tags()).containsExactly("starcraft.advisory.economic");
    }

    @Test
    void all_agent_ids_follow_platform_format() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();

        assertThat(descriptors).allMatch(d ->
                d.agentId().matches("claude:[a-z-]+@v\\d+"),
                "Agent ID should follow {model-family}:{persona}@{major} format"
        );
    }

    @Test
    void all_descriptors_use_default_tenant() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();

        assertThat(descriptors).allMatch(d ->
                d.tenancyId().equals(TenancyConstants.DEFAULT_TENANT_ID)
        );
    }

    @Test
    void capabilities_map_to_advisory_and_commentary_role_names() {
        QuarkMindAgentRegistrar registrar = new QuarkMindAgentRegistrar();

        List<AgentDescriptor> descriptors = registrar.descriptors();

        // Extract all capability names
        Map<String, Long> capabilityCounts = descriptors.stream()
                .flatMap(d -> d.capabilities().stream())
                .map(AgentCapability::name)
                .collect(Collectors.groupingBy(name -> name, Collectors.counting()));

        // Should have 5 capability types: 3 advisory (2 agents each) + 2 commentary (2 agents each)
        assertThat(capabilityCounts).hasSize(5);
        assertThat(capabilityCounts.get("advisory-crisis")).isEqualTo(2L);
        assertThat(capabilityCounts.get("advisory-strategic")).isEqualTo(2L);
        assertThat(capabilityCounts.get("advisory-economic")).isEqualTo(2L);
        assertThat(capabilityCounts.get("commentary-reactive")).isEqualTo(2L);
        assertThat(capabilityCounts.get("commentary-narrative")).isEqualTo(2L);
    }
}
