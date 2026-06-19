package io.casehub.control;

import io.casehub.core.CaseFile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The built-in MVP {@link PlanningStrategy} that assigns equal priority to all eligible
 * {@link PlanItem}s, providing baseline behavior equivalent to a system without explicit control
 * reasoning. Users register custom PlanningStrategies to override or augment this default
 * strategy without changing the core architecture. See design document section 6.2.
 */
@ApplicationScoped
public class DefaultPlanningStrategy implements PlanningStrategy {

    @Override
    public String getId() {
        return "default-control";
    }

    @Override
    public String getName() {
        return "Default Equal-Priority Control";
    }

    @Override
    public ControlActivationCondition getActivationCondition() {
        return ControlActivationCondition.ON_NEW_PLAN_ITEMS;
    }

    @Override
    public void reason(CasePlanModel casePlanModel, CaseFile domainCaseFile) {
        // Default: all PlanItems get priority 0 (equal), no focus filtering
        // This is a no-op — PlanItems keep their default priority
    }
}
