package io.casehub.error;

/**
 * Thrown by TaskDefinitionRegistry when registering a TaskDefinition would create a circular
 * dependency among required and produced keys.
 */
public class CircularDependencyException extends RuntimeException {
    public CircularDependencyException(String message) { super(message); }
}
