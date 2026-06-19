package io.casehub.core.spi;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.core.CaseStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for creating and retrieving CaseFiles.
 *
 * Implementations:
 * - casehub-persistence-memory: InMemoryCaseFileRepository (no external deps, fast tests)
 * - casehub-persistence-hibernate: HibernateCaseFileRepository (JPA/Panache, production)
 */
public interface CaseFileRepository {

    /** Create a root CaseFile (no parent). */
    CaseFile create(String caseType, Map<String, Object> initialState,
                    PropagationContext propagationContext);

    /** Create a child CaseFile attached to a parent. */
    CaseFile createChild(String caseType, Map<String, Object> initialState, CaseFile parent);

    Optional<CaseFile> findById(Long id);

    List<CaseFile> findByStatus(CaseStatus status);

    /** Persist any state changes to the CaseFile (no-op for in-memory). */
    void save(CaseFile caseFile);

    void delete(Long id);
}
