package io.quarkmind.plugin.commentary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link CommentaryDispositionTerm}.
 */
class CommentaryDispositionTermTest {

    @Test
    void shouldHaveFourTerms() {
        assertThat(CommentaryDispositionTerm.values()).hasSize(4);
    }

    @Test
    void shouldHaveCorrectVocabularyUri() {
        assertThat(CommentaryDispositionTerm.URI).isEqualTo("quarkmind:commentary-disposition");
    }

    @Test
    void shouldReturnLowercaseValue() {
        assertThat(CommentaryDispositionTerm.ENERGETIC.value()).isEqualTo("energetic");
        assertThat(CommentaryDispositionTerm.ANALYTICAL.value()).isEqualTo("analytical");
        assertThat(CommentaryDispositionTerm.DRAMATIC.value()).isEqualTo("dramatic");
        assertThat(CommentaryDispositionTerm.TACTICAL.value()).isEqualTo("tactical");
    }

    @Test
    void shouldReturnLabels() {
        assertThat(CommentaryDispositionTerm.ENERGETIC.label()).isEqualTo("Energetic");
        assertThat(CommentaryDispositionTerm.ANALYTICAL.label()).isEqualTo("Analytical");
        assertThat(CommentaryDispositionTerm.DRAMATIC.label()).isEqualTo("Dramatic");
        assertThat(CommentaryDispositionTerm.TACTICAL.label()).isEqualTo("Tactical");
    }

    @Test
    void shouldReturnDescriptions() {
        assertThat(CommentaryDispositionTerm.ENERGETIC.description()).isEqualTo("Enthusiastic, vivid, exclamatory");
        assertThat(CommentaryDispositionTerm.ANALYTICAL.description()).isEqualTo("Calm, precise, measured");
        assertThat(CommentaryDispositionTerm.DRAMATIC.description()).isEqualTo("Story-driven, narrative arc");
        assertThat(CommentaryDispositionTerm.TACTICAL.description()).isEqualTo("Data-heavy, tactical analysis");
    }

    @Test
    void shouldHaveAliases() {
        assertThat(CommentaryDispositionTerm.ENERGETIC.aliases()).containsExactly("enthusiastic", "vivid");
        assertThat(CommentaryDispositionTerm.ANALYTICAL.aliases()).containsExactly("calm", "precise");
        assertThat(CommentaryDispositionTerm.DRAMATIC.aliases()).containsExactly("story-driven", "narrative");
        assertThat(CommentaryDispositionTerm.TACTICAL.aliases()).containsExactly("data-driven", "analytical-style");
    }
}
