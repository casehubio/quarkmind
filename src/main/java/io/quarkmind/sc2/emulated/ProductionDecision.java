package io.quarkmind.sc2.emulated;

/**
 * Result of {@link RaceModel#canProduce} — controls the training flow in EmulatedGame.
 *
 * <p>PROCEED: race-specific production resource available (or not applicable); handleTrain continues.
 * <p>BLOCKED: race-specific resource unavailable (e.g. no Zerg larva); handleTrain exits immediately.
 */
public enum ProductionDecision { PROCEED, BLOCKED }
