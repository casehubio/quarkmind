package io.quarkmind.agency.spatial;

import java.util.Set;

public interface VisibilitySPI<E> {
    Set<E> visible();
    Set<E> remembered();
}
