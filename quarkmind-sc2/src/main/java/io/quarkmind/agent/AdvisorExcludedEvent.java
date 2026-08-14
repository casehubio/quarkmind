package io.quarkmind.agent;

/**
 * CDI event fired when an advisor transitions to EXCLUDED trust phase.
 *
 * <p>Used for observability and monitoring — allows logging, metrics collection,
 * or automated alerts when an advisor's trust score falls below the exclusion threshold.
 *
 * <p>Refs #180
 */
public record AdvisorExcludedEvent(String advisorId, String capability) {}
