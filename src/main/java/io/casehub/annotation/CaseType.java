package io.casehub.annotation;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/**
 * CDI qualifier annotation that binds TaskDefinitions and PlanningStrategies to specific
 * case types. Used during auto-registration at startup to determine which TaskDefinitions
 * apply to which case types.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface CaseType {
    String value();
}
