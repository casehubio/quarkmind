package io.quarkmind.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchetypeCategoryTest {

    @Test
    void sixCategories() {
        assertThat(ArchetypeCategory.values()).containsExactly(
            ArchetypeCategory.RUSH, ArchetypeCategory.TIMING,
            ArchetypeCategory.HARASS, ArchetypeCategory.MACRO,
            ArchetypeCategory.TECH, ArchetypeCategory.COMPOSITION);
    }
}
