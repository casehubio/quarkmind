package io.casehub.control;

import io.casehub.core.CaseFile;

/**
 * A specialist that reasons about <em>control</em> rather than domain content, embodying Hayes-Roth's
 * insight that control is itself a problem-solving activity. Reads the domain {@link CaseFile} and
 * {@link CasePlanModel} state, then writes control decisions (focus, priority, strategy) back to the
 * CasePlanModel. Activation conditions ({@link ControlActivationCondition}) determine when the
 * specialist is invoked. See design document section 6.2.
 */
public interface PlanningStrategy {
    String getId();
    String getName();

    ControlActivationCondition getActivationCondition();

    void reason(CasePlanModel casePlanModel, CaseFile domainCaseFile);

    /**
     * Determines when a {@link PlanningStrategy} is invoked during the control cycle.
     */
    enum ControlActivationCondition {
        ON_NEW_PLAN_ITEMS,
        ON_CASE_FILE_CHANGE,
        ON_TASK_COMPLETION,
        ALWAYS
    }
}
