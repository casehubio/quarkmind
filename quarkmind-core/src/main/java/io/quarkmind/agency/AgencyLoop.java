package io.quarkmind.agency;

/**
 * Thin wrapper over CaseEngine — maps agency phases (perceive, need, goal,
 * plan, act, reflect) to CaseEngine TaskDefinitions internally.
 * World implementors see agency vocabulary, not engine internals.
 */
public interface AgencyLoop {

    void tick(AgencyContext context);
}
