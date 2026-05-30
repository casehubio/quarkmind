package io.quarkmind.sc2.emulated;

/**
 * Result of {@link RaceModel#canProduce} — controls handleTrain flow without boolean inversion.
 *
 * <p>PROCEED: production resource available (or not applicable); handleTrain should continue
 * with the resource deduction and queue/training path.
 * <p>HANDLED: the model fully handled this intent (e.g. MULE calldown); handleTrain exits
 * immediately with no further resource deduction or queuing.
 * <p>BLOCKED: production resource unavailable (e.g. no larva); handleTrain exits without
 * touching resources or the queue.
 */
enum ProductionResult { PROCEED, HANDLED, BLOCKED }
