package io.quarkmind.plugin.coaching;

import io.casehub.eidos.api.VocabularyTerm;
import io.casehub.eidos.api.spi.VocabularyRegistrar;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CoachingDispositionRegistrar implements VocabularyRegistrar {
    @Override
    public Class<? extends Enum<? extends VocabularyTerm>> vocabulary() {
        return CoachingDispositionTerm.class;
    }
}
