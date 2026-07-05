package io.quarkmind.agent;

import io.quarkmind.domain.GameState;

public interface DominanceAssessor {
    double assess(GameState state);
}
