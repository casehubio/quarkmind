package io.casehub.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Objects;

/**
 * Immutable tracing and budget context that flows from parent to child.
 * Carries a W3C-compatible trace ID (shared across entire hierarchy),
 * inherited attributes (tenantId, userId, etc.), and optional resource budget.
 *
 * NOTE: spanId/parentSpanId/lineagePath removed — the POJO graph (CaseFile.getParentCase(),
 * CaseFile.getChildCases()) carries the structural relationships.
 */
public class PropagationContext {

    private final String traceId;
    private final Map<String, String> inheritedAttributes;
    private final Instant deadline;           // null = no deadline
    private final Duration remainingBudget;   // null = no budget
    private final Instant createdAt;          // private, used for child budget calculation only

    private PropagationContext(String traceId, Map<String, String> inheritedAttributes,
                               Instant deadline, Duration remainingBudget) {
        this.traceId = traceId;
        this.inheritedAttributes = Collections.unmodifiableMap(new HashMap<>(inheritedAttributes));
        this.deadline = deadline;
        this.remainingBudget = remainingBudget;
        this.createdAt = Instant.now();
    }

    /** Creates a root context with a new random trace ID. */
    public static PropagationContext createRoot() {
        return new PropagationContext(UUID.randomUUID().toString(), Map.of(), null, null);
    }

    /** Creates a root context with inherited attributes and a time budget. */
    public static PropagationContext createRoot(Map<String, String> attributes, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        return new PropagationContext(UUID.randomUUID().toString(), attributes, deadline, budget);
    }

    /** Creates a root context with inherited attributes and no budget. */
    public static PropagationContext createRoot(Map<String, String> attributes) {
        return new PropagationContext(UUID.randomUUID().toString(), attributes, null, null);
    }

    /**
     * Reconstructs a PropagationContext from storage.
     * Used by Hibernate persistence to restore context from entity fields.
     */
    public static PropagationContext fromStorage(String traceId, Map<String, String> attributes,
                                                  Instant deadline, Duration remainingBudget) {
        Objects.requireNonNull(traceId, "traceId must not be null");
        return new PropagationContext(traceId,
                attributes != null ? attributes : Map.of(),
                deadline, remainingBudget);
    }

    /** Creates a child context inheriting traceId and adjusting budget. */
    public PropagationContext createChild(Map<String, String> additionalAttributes) {
        Map<String, String> childAttrs = new HashMap<>(this.inheritedAttributes);
        childAttrs.putAll(additionalAttributes);

        Duration childBudget = null;
        Instant childDeadline = null;
        if (this.remainingBudget != null) {
            Duration elapsed = Duration.between(this.createdAt, Instant.now());
            Duration remaining = this.remainingBudget.minus(elapsed);
            childBudget = remaining.isNegative() ? Duration.ZERO : remaining;
            childDeadline = childBudget.isZero() ? Instant.now() : Instant.now().plus(childBudget);
        }

        return new PropagationContext(this.traceId, childAttrs, childDeadline, childBudget);
    }

    public PropagationContext createChild() {
        return createChild(Map.of());
    }

    public boolean isBudgetExhausted() {
        if (deadline != null && Instant.now().isAfter(deadline)) return true;
        return remainingBudget != null && (remainingBudget.isZero() || remainingBudget.isNegative());
    }

    public Optional<String> getAttribute(String key) {
        return Optional.ofNullable(inheritedAttributes.get(key));
    }

    public String getTraceId()                           { return traceId; }
    public Map<String, String> getInheritedAttributes()  { return inheritedAttributes; }
    public Optional<Instant> getDeadline()               { return Optional.ofNullable(deadline); }
    public Optional<Duration> getRemainingBudget()       { return Optional.ofNullable(remainingBudget); }
}
