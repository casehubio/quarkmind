package io.quarkmind.domain;

/**
 * Per-individual-unit costs. For batch-trained units (Zergling, trainCount=2),
 * mineral and gas are per individual (Zergling: 25m = half of 50m batch cost).
 * Supply is per training command (Zergling: 1 = 0.5 per individual, rounded to int).
 * For all units with trainCount=1, the distinction is moot.
 *
 * <p>Consumers that deduct costs per training command must multiply mineral/gas
 * by {@link SC2Data#trainCount} — see #234.
 */
public record UnitCosts(int mineral, int gas, int supply) {}
