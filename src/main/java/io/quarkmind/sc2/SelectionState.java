package io.quarkmind.sc2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

public final class SelectionState {

    private final ArrayList<String> tags = new ArrayList<>();

    /** Creates an instance pre-populated with {@code initial} tags, in order, deduplicating. */
    public static SelectionState of(String... initial) {
        SelectionState s = new SelectionState();
        for (String tag : initial) s.addTag(tag);
        return s;
    }

    /** Remove element at {@code index}; no-op if out of bounds. */
    public void removeAt(int index) {
        if (index >= 0 && index < tags.size()) tags.remove(index);
    }

    /**
     * Iterate descending; remove element at index {@code i} when {@code predicate.test(i)} is true.
     * Note: the predicate receives the element <em>index</em>, not the element value — unlike
     * {@link java.util.Collection#removeIf} which receives the element. The descending order
     * prevents index-shift bugs: removing at {@code i} does not affect indices 0..i-1 examined
     * in subsequent iterations.
     */
    public void removeIf(IntPredicate predicate) {
        for (int i = tags.size() - 1; i >= 0; i--) {
            if (predicate.test(i)) tags.remove(i);
        }
    }

    /** Keep first {@code size} elements; no-op if already shorter. */
    public void truncateTo(int size) {
        if (size < tags.size()) tags.subList(size, tags.size()).clear();
    }

    /**
     * Keep only elements at the given retained indices, in {@code indices} array order.
     * Out-of-bounds indices are silently ignored. Empty array clears all.
     */
    public void retainOnly(int[] indices) {
        List<String> kept = new ArrayList<>(indices.length);
        for (int idx : indices) {
            if (idx >= 0 && idx < tags.size()) {
                String tag = tags.get(idx);
                if (!kept.contains(tag)) kept.add(tag);
            }
        }
        tags.clear();
        tags.addAll(kept);
    }

    /** Remove all elements. */
    public void clear() {
        tags.clear();
    }

    /** Add {@code tag} to the end if not already present. */
    public void addTag(String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }

    public boolean isEmpty() { return tags.isEmpty(); }
    public int size()        { return tags.size(); }

    /** Returns the first element. Behaviour undefined if state is empty — caller must guard. */
    public String first()    { return tags.get(0); }

    /** Returns an immutable snapshot of the current tag list. */
    public List<String> snapshot() { return List.copyOf(tags); }
}
