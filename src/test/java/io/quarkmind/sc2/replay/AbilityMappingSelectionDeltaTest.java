package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.belicza.andras.util.type.BitArray;
import hu.sllauncher.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AbilityMapping#onSelection(SelectionDeltaEvent)}.
 *
 * Mirrors {@code IEM10CommandExtractorSelectionDeltaTest} — constructs synthetic
 * Scelight events from struct maps so no replay files are required.
 *
 * Struct layout (confirmed from bytecode inspection of Delta.class):
 * - Delta struct: {"removeMask": Pair<String,Object>, "addUnitTags": Integer[]}
 * - SelectionDeltaEvent constructor param 4 is userId (0-indexed)
 */
class AbilityMappingSelectionDeltaTest {

    private AbilityMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new AbilityMapping(1); // player 1 → userId 0
        mapping.setSelectionForTest(0, List.of("a", "b", "c", "d"));
    }

    // --- null removeMask (absent from struct) → carry forward ---

    @Test
    void nullRemoveMask_carryForward() {
        mapping.onSelection(event(null, null, new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    // --- "None" → carry forward ---

    @Test
    void noneVariant_carryForward() {
        mapping.onSelection(event("None", null, new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    // --- "ZeroIndices" → retain only at specified indices ---

    @Test
    void zeroIndices_retainsAtSpecifiedIndices() {
        // Retain only indices 0 ("a") and 2 ("c")
        mapping.onSelection(event("ZeroIndices", new Integer[]{0, 2}, new Integer[0]));
        assertSelection("a", "c");
    }

    @Test
    void zeroIndices_emptyIndices_clearsSelection() {
        mapping.onSelection(event("ZeroIndices", new Integer[0], new Integer[0]));
        assertSelection();
    }

    @Test
    void zeroIndices_wrongPayloadType_clearsSelection() {
        // value2 not Integer[] → falls through to clear
        mapping.onSelection(event("ZeroIndices", "unexpected", new Integer[0]));
        assertSelection();
    }

    // --- "Mask" → remove bit-set positions ---

    @Test
    void maskVariant_removesBitSetPositions() {
        // byte 0b00000101 = bits 0 and 2 set (LSB-first) → remove "a" (idx 0) and "c" (idx 2) → "b", "d"
        final BitArray mask = new BitArray(4, new byte[]{0b00000101});
        mapping.onSelection(event("Mask", mask, new Integer[0]));
        assertSelection("b", "d");
    }

    @Test
    void maskVariant_zeroBitArray_removesNothing() {
        final BitArray mask = new BitArray(4, new byte[]{0}); // all bits 0
        mapping.onSelection(event("Mask", mask, new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    @Test
    void maskVariant_wrongPayloadType_noChange() {
        // value2 not BitArray → logs warning, selection unchanged
        mapping.onSelection(event("Mask", "unexpected", new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    // --- "OneIndices" → remove at indices descending ---

    @Test
    void oneIndices_removesAtDescendingIndices() {
        // Remove [1, 3] descending → remove index 3 ("d") then index 1 ("b") → "a", "c"
        mapping.onSelection(event("OneIndices", new Integer[]{1, 3}, new Integer[0]));
        assertSelection("a", "c");
    }

    @Test
    void oneIndices_wrongPayloadType_noChange() {
        // value2 not Integer[] → logs warning, selection unchanged
        mapping.onSelection(event("OneIndices", "unexpected", new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    // --- Unknown variant → clear ---

    @Test
    void unknownVariant_clearsSelection() {
        mapping.onSelection(event("FutureVariant", null, new Integer[0]));
        assertSelection();
    }

    // --- addUnitTags appended after removal ---

    @Test
    void addTags_appendedAfterRemoval() {
        // ZeroIndices retains index 0 ("a"), then appends two tags decoded from raw ints.
        // GameEventStream.decodeTag(rawTag): "r-" + (rawTag >> 18) + "-" + (rawTag & 0x3FFF)
        // rawTag (1<<18)|1 = 262145 → "r-1-1"
        // rawTag (2<<18)|1 = 524289 → "r-2-1"
        mapping.setSelectionForTest(0, List.of("a", "b", "c"));
        mapping.onSelection(event("ZeroIndices", new Integer[]{0}, new Integer[]{262145, 524289}));
        assertThat(mapping.selectionSnapshotForTest()).containsExactly("a", "r-1-1", "r-2-1");
    }

    @Test
    void addTags_onCarryForward_appendsToExisting() {
        mapping.setSelectionForTest(0, List.of("a"));
        mapping.onSelection(event("None", null, new Integer[]{262145}));
        assertThat(mapping.selectionSnapshotForTest()).containsExactly("a", "r-1-1");
    }

    // --- Wrong userId → ignored ---

    @Test
    void wrongUserId_selectionUnchanged() {
        // userId=1 corresponds to player 2; mapping is for player 1 (userId=0)
        mapping.onSelection(eventForUser(1, "None", null, new Integer[0]));
        assertSelection("a", "b", "c", "d");
    }

    // --- Helpers ---

    /** Build event for userId=0 (the default player 1 mapping). */
    private SelectionDeltaEvent event(final String variant, final Object value2,
                                      final Integer[] addUnitTags) {
        return eventForUser(0, variant, value2, addUnitTags);
    }

    private SelectionDeltaEvent eventForUser(final int userId, final String variant,
                                             final Object value2, final Integer[] addUnitTags) {
        // Delta.getRemoveMask() returns get("removeMask") directly as Pair<String,Object>.
        // Must pass baseBuild >= 16561 to skip old-format compatibility code that would
        // overwrite the removeMask with a path-navigated value.
        final Pair<String, Object> removeMask = variant != null
            ? new Pair<>(variant, value2)
            : null;
        final Map<String, Object> deltaMap = new HashMap<>();
        deltaMap.put("removeMask", removeMask);
        deltaMap.put("addUnitTags", addUnitTags);
        deltaMap.put("addSubgroups", new Map[0]);
        deltaMap.put("subgroupIndex", 0);

        final Map<String, Object> struct = new HashMap<>();
        struct.put("delta", deltaMap);
        // baseBuild=16561 skips the baseBuild<16561 old-format compatibility block in
        // SelectionDeltaEvent constructor that would rewrite our synthetic removeMask.
        return new SelectionDeltaEvent(struct, 0, "SelectionDeltaEvent", 0, userId, 16561);
    }

    private void assertSelection(final String... expected) {
        assertThat(mapping.selectionSnapshotForTest()).containsExactly(expected);
    }
}
