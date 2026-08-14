package io.casehub.annotation;

import java.lang.annotation.*;

/**
 * Plain metadata annotation that marks TaskDefinition implementations as belonging to
 * a specific case type. Used by {@code QuarkMindCaseHub} for plugin discovery.
 *
 * <p>Not a CDI qualifier — all annotated beans are {@code @Default} and can be injected
 * without specifying this annotation at the injection point.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface CaseType {
    String value();
}
