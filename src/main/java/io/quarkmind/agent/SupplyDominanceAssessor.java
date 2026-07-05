package io.quarkmind.agent;

import io.quarkmind.domain.GameState;
import io.quarkmind.domain.SC2Data;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SupplyDominanceAssessor implements DominanceAssessor {

    private final int maxExpectedDelta;

    @Inject
    SupplyDominanceAssessor(MilestoneConfig config) {
        this.maxExpectedDelta = config.dominance().maxExpectedDelta();
    }

    SupplyDominanceAssessor(int maxExpectedDelta) {
        this.maxExpectedDelta = maxExpectedDelta;
    }

    @Override
    public double assess(GameState state) {
        if (state.enemyUnits().isEmpty()) {
            return 0.0;
        }
        int enemySupply = state.enemyUnits().stream()
            .mapToInt(u -> SC2Data.supplyCost(u.type()))
            .sum();
        double delta = state.supplyUsed() - enemySupply;
        return Math.max(-1.0, Math.min(1.0, delta / maxExpectedDelta));
    }
}
