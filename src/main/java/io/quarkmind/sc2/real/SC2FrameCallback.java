package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;

/**
 * Callback interface between QuarkusSC2Transport and SC2BotAgent.
 * Transport-level — lives in sc2/real/, not part of the SC2Engine seam.
 *
 * Called on the game loop virtual thread only. Implementations must not block
 * longer than a single frame budget (~45ms at 22Hz).
 */
public interface SC2FrameCallback {

    /** Called once after RequestGameInfo succeeds — before the observation loop starts. */
    void onGameStart(ResponseGameInfo gameInfo);

    /** Called each game frame with the current observation. May dispatch actions via transport. */
    void onStep(Observation obs);

    /** Called from the game loop finally block — fires unconditionally on all exit paths. */
    void onGameEnd();
}
