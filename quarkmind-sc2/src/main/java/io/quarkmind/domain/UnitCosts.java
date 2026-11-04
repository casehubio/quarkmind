package io.quarkmind.domain;

/**
 * Per-individual-unit costs. Supply is per individual unit (Zergling: 0.5).
 *
 * <p>Consumers that deduct costs per training command must multiply mineral/gas
 * by {@link SC2Data#trainCount} — see #234.
 */
public record UnitCosts(int mineral, int gas, double supply) {}
