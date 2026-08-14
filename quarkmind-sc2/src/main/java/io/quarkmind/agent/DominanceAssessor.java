package io.quarkmind.agent;

import io.quarkmind.domain.DominanceScore;
import io.quarkmind.domain.GameState;

public interface DominanceAssessor {
    DominanceScore assess(GameState state);
}
