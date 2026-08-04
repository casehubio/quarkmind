package io.quarkmind.plugin.advisory;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.spi.AgentDescriptorRegistrar;
import io.casehub.eidos.vocab.ConscientiousnessTerm;
import io.casehub.platform.api.identity.TenancyConstants;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * Registers QuarkMind's 10 LLM agent configurations as eidos AgentDescriptors.
 * <p>
 * <b>Advisory agents (6):</b> Three advisory roles (crisis, strategic, economic), each with two disposition variants:
 * <ul>
 * <li><b>Crisis:</b> aggressive (bold, flexible) + conservative (conservative, strict)</li>
 * <li><b>Strategic:</b> bold (bold, flexible) + measured (measured, principled)</li>
 * <li><b>Economic:</b> expansion (bold, collaborative) + defensive (conservative, independent)</li>
 * </ul>
 * <p>
 * <b>Commentary agents (4):</b> Two commentary modes (reactive, narrative), each with two disposition variants:
 * <ul>
 * <li><b>Reactive:</b> energetic (bold, flexible) + analytical (conservative, strict)</li>
 * <li><b>Narrative:</b> dramatic (bold, flexible) + tactical (conservative, strict)</li>
 * </ul>
 * <p>
 * Agent identity format: {@code {model-family}:{persona}@{major}} per PLATFORM.md line 591.
 * <p>
 * Disposition traits use {@link ConscientiousnessTerm} values. Capability names match role types
 * ({@code advisory-*}, {@code commentary-reactive}, {@code commentary-narrative}).
 */
@ApplicationScoped
public class QuarkMindAgentRegistrar implements AgentDescriptorRegistrar {

    private static final String PROVIDER = "anthropic";
    private static final String MODEL_FAMILY = "claude";
    private static final String MODEL_VERSION = "sonnet-4";
    private static final String SLOT_VOCABULARY = ConscientiousnessTerm.URI;
    private static final String TENANT_ID = TenancyConstants.DEFAULT_TENANT_ID;

    @Override
    public List<AgentDescriptor> descriptors() {
        return List.of(
                buildCrisisAggressive(),
                buildCrisisConservative(),
                buildStrategicBold(),
                buildStrategicMeasured(),
                buildEconomicExpansion(),
                buildEconomicDefensive(),
                buildCommentatorEnergetic(),
                buildCommentatorAnalytical(),
                buildNarratorDramatic(),
                buildNarratorTactical(),
                buildCoachDirective(),
                buildCoachSocratic()
                      );
    }

    // Crisis advisors

    private AgentDescriptor buildCrisisAggressive() {
        return AgentDescriptor.builder()
                .agentId("claude:crisis-aggressive@v1")
                .name("Aggressive Crisis Responder")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("crisis-responder")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                        .riskAppetite(ConscientiousnessTerm.BOLD.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("compete")  // Thomas-Kilmann, not in ConscientiousnessTerm
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-crisis")
                                .latencyHintP50Ms(1500L)  // Fast — sub-2s preferred per design
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.crisis"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildCrisisConservative() {
        return AgentDescriptor.builder()
                .agentId("claude:crisis-conservative@v1")
                .name("Conservative Crisis Responder")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("crisis-responder")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                        .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("compete")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-crisis")
                                .latencyHintP50Ms(1500L)
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.crisis"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    // Strategic advisors

    private AgentDescriptor buildStrategicBold() {
        return AgentDescriptor.builder()
                .agentId("claude:strategic-bold@v1")
                .name("Bold Strategic Advisor")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("strategic-advisor")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                        .riskAppetite(ConscientiousnessTerm.BOLD.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-strategic")
                                .latencyHintP50Ms(3000L)  // Moderate — 2-5s acceptable per design
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.strategic"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildStrategicMeasured() {
        return AgentDescriptor.builder()
                .agentId("claude:strategic-measured@v1")
                .name("Measured Strategic Advisor")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("strategic-advisor")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.PRINCIPLED.value())
                        .riskAppetite(ConscientiousnessTerm.MEASURED.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-strategic")
                                .latencyHintP50Ms(3000L)
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.strategic"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    // Economic advisors

    private AgentDescriptor buildEconomicExpansion() {
        return AgentDescriptor.builder()
                .agentId("claude:economic-expansion@v1")
                .name("Expansion Economic Optimizer")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("economic-optimizer")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                        .riskAppetite(ConscientiousnessTerm.BOLD.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-economic")
                                .latencyHintP50Ms(2500L)  // Moderate — 2-5s acceptable per design
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.economic"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildEconomicDefensive() {
        return AgentDescriptor.builder()
                .agentId("claude:economic-defensive@v1")
                .name("Defensive Economic Optimizer")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("economic-optimizer")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.INDEPENDENT.value())
                        .ruleFollowing(ConscientiousnessTerm.PRINCIPLED.value())
                        .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("avoid")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("advisory-economic")
                                .latencyHintP50Ms(2500L)
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.advisory.economic"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    // Commentary agents — reactive

    private AgentDescriptor buildCommentatorEnergetic() {
        return AgentDescriptor.builder()
                .agentId("claude:commentator-energetic@v1")
                .name("Energetic Commentator")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("commentator")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                        .riskAppetite(ConscientiousnessTerm.BOLD.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("commentary-reactive")
                                .latencyHintP50Ms(1500L)  // Fast — reactive commentary
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.commentary.reactive"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildCommentatorAnalytical() {
        return AgentDescriptor.builder()
                .agentId("claude:commentator-analytical@v1")
                .name("Analytical Commentator")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("commentator")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                        .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("commentary-reactive")
                                .latencyHintP50Ms(1500L)  // Fast — reactive commentary
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.commentary.reactive"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    // Commentary agents — narrative

    private AgentDescriptor buildNarratorDramatic() {
        return AgentDescriptor.builder()
                .agentId("claude:narrator-dramatic@v1")
                .name("Dramatic Narrator")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("narrator")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                        .riskAppetite(ConscientiousnessTerm.BOLD.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("commentary-narrative")
                                .latencyHintP50Ms(3000L)  // Moderate — narrative commentary
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.commentary.narrative"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildNarratorTactical() {
        return AgentDescriptor.builder()
                .agentId("claude:narrator-tactical@v1")
                .name("Tactical Narrator")
                .provider(PROVIDER)
                .modelFamily(MODEL_FAMILY)
                .modelVersion(MODEL_VERSION)
                .slot("narrator")
                .slotVocabulary(SLOT_VOCABULARY)
                .disposition(AgentDisposition.builder()
                        .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                        .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                        .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                        .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                        .conflictMode("collaborate")
                        .delegation(false)
                        .build())
                .capabilities(List.of(
                        AgentCapability.builder()
                                .name("commentary-narrative")
                                .latencyHintP50Ms(3000L)  // Moderate — narrative commentary
                                .qualityHint(0.7)
                                .tags(List.of("starcraft.commentary.narrative"))
                                .build()))
                .tenancyId(TENANT_ID)
                .build();
    }

    private AgentDescriptor buildCoachDirective() {
        return AgentDescriptor.builder()
                              .agentId("claude:coach-directive@v1")
                              .name("Directive Coach")
                              .provider(PROVIDER)
                              .modelFamily(MODEL_FAMILY)
                              .modelVersion(MODEL_VERSION)
                              .slot("coach")
                              .slotVocabulary(SLOT_VOCABULARY)
                              .disposition(AgentDisposition.builder()
                                                           .socialOrient(ConscientiousnessTerm.INDEPENDENT.value())
                                                           .ruleFollowing(ConscientiousnessTerm.FLEXIBLE.value())
                                                           .riskAppetite(ConscientiousnessTerm.BOLD.value())
                                                           .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                                                           .conflictMode("collaborate")
                                                           .delegation(false)
                                                           .build())
                              .capabilities(List.of(
                                      AgentCapability.builder()
                                                     .name("coaching")
                                                     .latencyHintP50Ms(2000L)
                                                     .qualityHint(0.7)
                                                     .tags(List.of("starcraft.coaching"))
                                                     .build()))
                              .tenancyId(TENANT_ID)
                              .build();
    }

    private AgentDescriptor buildCoachSocratic() {
        return AgentDescriptor.builder()
                              .agentId("claude:coach-socratic@v1")
                              .name("Socratic Coach")
                              .provider(PROVIDER)
                              .modelFamily(MODEL_FAMILY)
                              .modelVersion(MODEL_VERSION)
                              .slot("coach")
                              .slotVocabulary(SLOT_VOCABULARY)
                              .disposition(AgentDisposition.builder()
                                                           .socialOrient(ConscientiousnessTerm.COLLABORATIVE.value())
                                                           .ruleFollowing(ConscientiousnessTerm.STRICT.value())
                                                           .riskAppetite(ConscientiousnessTerm.CONSERVATIVE.value())
                                                           .autonomy(ConscientiousnessTerm.SEMI_AUTONOMOUS.value())
                                                           .conflictMode("collaborate")
                                                           .delegation(false)
                                                           .build())
                              .capabilities(List.of(
                                      AgentCapability.builder()
                                                     .name("coaching")
                                                     .latencyHintP50Ms(2000L)
                                                     .qualityHint(0.7)
                                                     .tags(List.of("starcraft.coaching"))
                                                     .build()))
                              .tenancyId(TENANT_ID)
                              .build();
    }

}
