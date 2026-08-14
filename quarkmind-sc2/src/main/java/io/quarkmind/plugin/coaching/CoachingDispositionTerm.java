package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.VocabularyMetadata;
import io.casehub.eidos.api.VocabularyTerm;
import java.util.List;

@VocabularyMetadata(
    uri = "quarkmind:coaching-disposition",
    name = "Coaching Disposition Vocabulary",
    version = "1.0",
    description = "Personality vocabulary for QuarkMind coaching agents — directive vs Socratic"
)
public enum CoachingDispositionTerm implements VocabularyTerm {
    DIRECTIVE("directive", "Directive", "Explicit commands, imperative voice",
        List.of("commanding", "imperative")),
    SOCRATIC("socratic", "Socratic", "Guiding questions, discovery-oriented",
        List.of("questioning", "guided"));

    public static final String URI = "quarkmind:coaching-disposition";

    private final String value;
    private final String label;
    private final String description;
    private final List<String> aliases;

    CoachingDispositionTerm(String value, String label, String description, List<String> aliases) {
        this.value = value;
        this.label = label;
        this.description = description;
        this.aliases = aliases;
    }

    @Override public String value() { return value; }
    @Override public String label() { return label; }
    @Override public String description() { return description; }
    @Override public List<String> aliases() { return aliases; }
}
