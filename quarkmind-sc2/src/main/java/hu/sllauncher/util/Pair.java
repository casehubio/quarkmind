package hu.sllauncher.util;

import java.util.Objects;

/**
 * A pair of generic-type objects. Minimal standalone copy extracted from Scelight.
 */
public class Pair<T1, T2> {

    public final T1 value1;
    public final T2 value2;

    public Pair(final T1 value1, final T2 value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) return true;
        if (!(o instanceof Pair)) return false;
        final Pair<?, ?> p = (Pair<?, ?>) o;
        return Objects.equals(value1, p.value1) && Objects.equals(value2, p.value2);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value1, value2);
    }
}
