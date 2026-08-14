package io.quarkmind.plugin.commentary;

import io.casehub.eidos.api.VocabularyTerm;
import io.casehub.eidos.api.spi.VocabularyRegistrar;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registers {@link CommentaryDispositionTerm} vocabulary with the eidos vocabulary registry.
 */
@ApplicationScoped
public class CommentaryDispositionRegistrar implements VocabularyRegistrar {

    @Override
    public Class<? extends Enum<? extends VocabularyTerm>> vocabulary() {
        return CommentaryDispositionTerm.class;
    }
}
