package io.quarkmind.domain;

import java.util.Map;

/**
 * Per-unit combat offense properties: damage dealt per attack event, attack
 * cooldown period, weapon range, and bonus damage against specific unit
 * attributes. Complements {@link UnitDefenses} for the defensive side.
 *
 * @param bonusDamageVs extra damage per target attribute (empty if none)
 */
public record UnitCombatStats(
    int damagePerAttack,
    int attackCooldownInTicks,
    float attackRange,
    Map<UnitAttribute, Integer> bonusDamageVs
) {
    public UnitCombatStats(int damagePerAttack, int attackCooldownInTicks, float attackRange) {
        this(damagePerAttack, attackCooldownInTicks, attackRange, Map.of());
    }
}
