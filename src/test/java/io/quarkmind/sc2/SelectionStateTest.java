package io.quarkmind.sc2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionStateTest {

    // --- removeAt ---

    @Test
    void removeAtInBoundsRemovesElement() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.removeAt(1);
        assertThat(s.snapshot()).containsExactly("a", "c");
    }

    @Test
    void removeAtOutOfBoundsIsNoOp() {
        SelectionState s = SelectionState.of("a", "b");
        s.removeAt(5);
        assertThat(s.snapshot()).containsExactly("a", "b");
    }

    @Test
    void removeAtNegativeIsNoOp() {
        SelectionState s = SelectionState.of("a", "b");
        s.removeAt(-1);
        assertThat(s.snapshot()).containsExactly("a", "b");
    }

    // --- removeIf ---

    @Test
    void removeIfRemovesMatchingIndices() {
        SelectionState s = SelectionState.of("a", "b", "c", "d");
        s.removeIf(i -> i == 0 || i == 2);
        assertThat(s.snapshot()).containsExactly("b", "d");
    }

    @Test
    void removeIfNoMatchIsNoOp() {
        SelectionState s = SelectionState.of("a", "b");
        s.removeIf(i -> false);
        assertThat(s.snapshot()).containsExactly("a", "b");
    }

    @Test
    void removeIfOnEmptyIsNoOp() {
        SelectionState s = new SelectionState();
        s.removeIf(i -> true);
        assertThat(s.isEmpty()).isTrue();
    }

    // --- truncateTo ---

    @Test
    void truncateToLargerThanSizeIsNoOp() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.truncateTo(5);
        assertThat(s.snapshot()).containsExactly("a", "b", "c");
    }

    @Test
    void truncateToTruncatesCorrectly() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.truncateTo(1);
        assertThat(s.snapshot()).containsExactly("a");
    }

    @Test
    void truncateToZeroClearsAll() {
        SelectionState s = SelectionState.of("a", "b");
        s.truncateTo(0);
        assertThat(s.isEmpty()).isTrue();
    }

    // --- retainOnly ---

    @Test
    void retainOnlyKeepsElementsAtGivenIndicesInIndicesArrayOrder() {
        SelectionState s = SelectionState.of("a", "b", "c", "d");
        s.retainOnly(new int[]{3, 1});
        assertThat(s.snapshot()).containsExactly("d", "b");
    }

    @Test
    void retainOnlyWithAscendingIndices() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.retainOnly(new int[]{0, 2});
        assertThat(s.snapshot()).containsExactly("a", "c");
    }

    @Test
    void retainOnlyEmptyArrayClearsAll() {
        SelectionState s = SelectionState.of("a", "b");
        s.retainOnly(new int[]{});
        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void retainOnlyOutOfBoundsIndexSilentlyIgnored() {
        SelectionState s = SelectionState.of("a", "b");
        s.retainOnly(new int[]{0, 99});
        assertThat(s.snapshot()).containsExactly("a");
    }

    // --- clear ---

    @Test
    void clearRemovesAllElements() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.clear();
        assertThat(s.isEmpty()).isTrue();
        assertThat(s.size()).isZero();
    }

    // --- addTag ---

    @Test
    void addTagAppendsNewTag() {
        SelectionState s = new SelectionState();
        s.addTag("x");
        assertThat(s.snapshot()).containsExactly("x");
    }

    @Test
    void addTagIgnoresDuplicate() {
        SelectionState s = SelectionState.of("x");
        s.addTag("x");
        assertThat(s.snapshot()).hasSize(1).containsExactly("x");
    }

    // --- accessors ---

    @Test
    void firstReturnsFirstElement() {
        SelectionState s = SelectionState.of("a", "b", "c");
        assertThat(s.first()).isEqualTo("a");
    }

    @Test
    void sizeReflectsCurrentCount() {
        SelectionState s = SelectionState.of("a", "b", "c");
        assertThat(s.size()).isEqualTo(3);
        s.removeAt(0);
        assertThat(s.size()).isEqualTo(2);
    }

    @Test
    void ofFactoryProducesCorrectInitialState() {
        SelectionState s = SelectionState.of("x", "y", "z");
        assertThat(s.snapshot()).containsExactly("x", "y", "z");
        assertThat(s.size()).isEqualTo(3);
        assertThat(s.isEmpty()).isFalse();
    }

    // --- combinations ---

    @Test
    void removeAfterAddMaintainsOrder() {
        SelectionState s = SelectionState.of("a", "b");
        s.addTag("c");
        s.removeAt(0);
        assertThat(s.snapshot()).containsExactly("b", "c");
    }

    @Test
    void retainThenAddProducesCorrectResult() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.retainOnly(new int[]{2});
        s.addTag("d");
        assertThat(s.snapshot()).containsExactly("c", "d");
    }

    // --- edge cases ---

    @Test
    void removeAtOnSingleElementLeavesEmptyState() {
        SelectionState s = SelectionState.of("only");
        s.removeAt(0);
        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void addTagAfterRemoveCanReAddSameTag() {
        SelectionState s = SelectionState.of("x");
        s.removeAt(0);
        s.addTag("x");
        assertThat(s.snapshot()).containsExactly("x");
    }

    @Test
    void removeIfRemovingAllElementsLeavesEmptyState() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.removeIf(i -> true);
        assertThat(s.isEmpty()).isTrue();
    }

    @Test
    void retainOnlyWithDuplicateIndexDoesNotProduceDuplicateTags() {
        SelectionState s = SelectionState.of("a", "b", "c");
        s.retainOnly(new int[]{1, 1});
        assertThat(s.snapshot()).containsExactly("b");
    }
}
