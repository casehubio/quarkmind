package io.casehub.persistence.memory;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.core.CaseStatus;
import io.casehub.core.spi.CaseFileRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link CaseFileRepository}. Thread-safe, no external dependencies.
 * ID sequence is per-instance (reset between test instances).
 */
@ApplicationScoped
public class InMemoryCaseFileRepository implements CaseFileRepository {

    private final Map<Long, InMemoryCaseFile> store = new ConcurrentHashMap<>();

    @Override
    public CaseFile create(String caseType, Map<String, Object> initialState,
                            PropagationContext propagationContext) {
        InMemoryCaseFile cf = new InMemoryCaseFile(caseType, initialState, propagationContext);
        store.put(cf.getId(), cf);
        return cf;
    }

    @Override
    public CaseFile createChild(String caseType, Map<String, Object> initialState, CaseFile parent) {
        PropagationContext childCtx = parent.getPropagationContext().createChild();
        InMemoryCaseFile child = new InMemoryCaseFile(caseType, initialState, childCtx);
        child.setParentCase(parent);
        if (parent instanceof InMemoryCaseFile p) p.addChildCase(child);
        store.put(child.getId(), child);
        return child;
    }

    @Override
    public Optional<CaseFile> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<CaseFile> findByStatus(CaseStatus status) {
        return store.values().stream()
                .filter(cf -> cf.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public void save(CaseFile caseFile) {
        // No-op: in-memory objects are mutated in place
    }

    @Override
    public void delete(Long id) {
        store.remove(id);
    }
}
