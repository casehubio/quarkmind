package io.quarkmind.domain;

/**
 * Per-unit defensive properties: hit points, shield points, and base armour.
 * Complements {@link UnitCombatStats} for the offensive side.
 */
public record UnitDefenses(int maxHealth, int maxShields, int armour) {}
