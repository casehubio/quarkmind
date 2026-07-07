package io.quarkmind.plugin.commentary;

import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyTerm;

import java.util.List;

/**
 * QuarkMind-specific vocabulary for commentary agent personalities.
 * <p>
 * Four terms mapping to the existing {@code riskAppetite} and {@code ruleFollowing}
 * disposition axes from {@code ConscientiousnessTerm}:
 * <ul>
 * <li><b>ENERGETIC</b> (energy axis) — enthusiastic, exclamatory → riskAppetite:bold</li>
 * <li><b>ANALYTICAL</b> (energy axis) — calm, precise, measured → riskAppetite:conservative</li>
 * <li><b>DRAMATIC</b> (style axis) — story-driven, narrative arc → ruleFollowing:flexible</li>
 * <li><b>TACTICAL</b> (style axis) — data-heavy, tactical analysis → ruleFollowing:strict</li>
 * </ul>
 * <p>
 * These terms are vocabulary labels for human-readable personality descriptors.
 * The actual {@code AgentDisposition} axis values are set using {@code ConscientiousnessTerm}
 * constants in {@link io.quarkmind.plugin.advisory.QuarkMindAgentRegistrar}.
 */
@VocabularyMetadata(
        uri = "quarkmind:commentary-disposition",
        name = "Commentary Disposition Vocabulary",
        version = "1.0",
        description = "Personality vocabulary for QuarkMind commentary agents — maps to riskAppetite and ruleFollowing axes"
)
public enum CommentaryDispositionTerm implements VocabularyTerm {
    ENERGETIC("energetic", "Energetic", "Enthusiastic, vivid, exclamatory", List.of("enthusiastic", "vivid")),
    ANALYTICAL("analytical", "Analytical", "Calm, precise, measured", List.of("calm", "precise")),
    DRAMATIC("dramatic", "Dramatic", "Story-driven, narrative arc", List.of("story-driven", "narrative")),
    TACTICAL("tactical", "Tactical", "Data-heavy, tactical analysis", List.of("data-driven", "analytical-style"));

    public static final String URI = "quarkmind:commentary-disposition";

    private final String value;
    private final String label;
    private final String description;
    private final List<String> aliases;

    CommentaryDispositionTerm(String value, String label, String description, List<String> aliases) {
        this.value = value;
        this.label = label;
        this.description = description;
        this.aliases = aliases;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<String> aliases() {
        return aliases;
    }
}
