package io.quarkmind.sc2.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IEM10CommandExtractorSelectionDeltaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // (3L << 18) | 7 = 786439 → decodes to "j-3-7"
    private static final long TAG_3_7 = (3L << 18) | 7;

    private static ObjectNode event(String maskVariant, Integer maskValue, long... addTags) {
        ObjectNode removeMask = MAPPER.createObjectNode();
        if (maskValue == null) {
            removeMask.putNull(maskVariant);
        } else {
            removeMask.put(maskVariant, maskValue);
        }
        ArrayNode addUnitTags = MAPPER.createArrayNode();
        for (long tag : addTags) addUnitTags.add(tag);
        ObjectNode delta = MAPPER.createObjectNode();
        delta.set("removeMask", removeMask);
        delta.set("addUnitTags", addUnitTags);
        ObjectNode evt = MAPPER.createObjectNode();
        evt.set("delta", delta);
        return evt;
    }

    @Test
    void maskVariantRemovesCorrectBitPositions() {
        List<String> sel = new ArrayList<>(List.of("a", "b", "c", "d"));
        IEM10CommandExtractor.applySelectionDelta(event("Mask", 5), sel);
        assertThat(sel).containsExactly("b", "d");
    }

    @Test
    void maskVariantWithZeroMaskRemovesNothing() {
        List<String> sel = new ArrayList<>(List.of("a", "b"));
        IEM10CommandExtractor.applySelectionDelta(event("Mask", 0), sel);
        assertThat(sel).containsExactly("a", "b");
    }

    @Test
    void sweepToEndTruncatesFromIndex() {
        List<String> sel = new ArrayList<>(List.of("a", "b", "c"));
        IEM10CommandExtractor.applySelectionDelta(event("SweepToEnd", 1), sel);
        assertThat(sel).containsExactly("a");
    }

    @Test
    void sweepToEndZeroRemovesAll() {
        List<String> sel = new ArrayList<>(List.of("a", "b"));
        IEM10CommandExtractor.applySelectionDelta(event("SweepToEnd", 0), sel);
        assertThat(sel).isEmpty();
    }

    @Test
    void oneIndiceRemovesSingleItem() {
        List<String> sel = new ArrayList<>(List.of("a", "b", "c"));
        IEM10CommandExtractor.applySelectionDelta(event("OneIndice", 1), sel);
        assertThat(sel).containsExactly("a", "c");
    }

    @Test
    void oneIndiceOutOfBoundsIsNoOp() {
        List<String> sel = new ArrayList<>(List.of("a", "b"));
        IEM10CommandExtractor.applySelectionDelta(event("OneIndice", 5), sel);
        assertThat(sel).containsExactly("a", "b");
    }

    @Test
    void noneVariantPreservesExistingSelection() {
        List<String> sel = new ArrayList<>(List.of("a", "b"));
        IEM10CommandExtractor.applySelectionDelta(event("None", null), sel);
        assertThat(sel).containsExactly("a", "b");
    }

    @Test
    void addUnitTagsDecodesPackedTag() {
        List<String> sel = new ArrayList<>();
        IEM10CommandExtractor.applySelectionDelta(event("None", null, TAG_3_7), sel);
        assertThat(sel).containsExactly("j-3-7");
    }

    @Test
    void noneVariantAppendsTagsToExisting() {
        List<String> sel = new ArrayList<>(List.of("a"));
        IEM10CommandExtractor.applySelectionDelta(event("None", null, TAG_3_7), sel);
        assertThat(sel).containsExactly("a", "j-3-7");
    }

    @Test
    void duplicateTagNotAdded() {
        List<String> sel = new ArrayList<>();
        IEM10CommandExtractor.applySelectionDelta(event("None", null, TAG_3_7, TAG_3_7), sel);
        assertThat(sel).hasSize(1).containsExactly("j-3-7");
    }
}
