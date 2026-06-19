package io.casehub.error;

/**
 * Thrown by WorkerRegistry when a worker operation fails API key validation.
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) { super(message); }
}
