package io.quarkmind.plugin.coaching;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CoachingDispositionTermTest {

    @Test
    void uri_matchesConstant() {
        assertThat(CoachingDispositionTerm.URI).isEqualTo("quarkmind:coaching-disposition");
    }

    @Test
    void directive_hasCorrectValue() {
        assertThat(CoachingDispositionTerm.DIRECTIVE.value()).isEqualTo("directive");
    }

    @Test
    void socratic_hasCorrectValue() {
        assertThat(CoachingDispositionTerm.SOCRATIC.value()).isEqualTo("socratic");
    }

    @Test
    void twoTerms() {
        assertThat(CoachingDispositionTerm.values()).hasSize(2);
    }

    @Test
    void aliases_nonEmpty() {
        for (var term : CoachingDispositionTerm.values()) {
            assertThat(term.aliases()).isNotEmpty();
        }
    }
}
