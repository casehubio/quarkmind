package io.quarkmind.agency.intent;

/**
 * Marker interface for world-specific intents.
 * Each world defines its own intent types — SC2 uses a sealed hierarchy,
 * text-based worlds might use strings.
 */
public interface Intent {}
