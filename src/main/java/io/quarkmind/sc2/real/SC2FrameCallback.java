package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import io.quarkmind.sc2.GameResult;

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

    /**
     * Called each game frame with the current observation. May dispatch actions via transport.
     * Implementations may throw {@link InterruptedException} — the game loop catches it on the
     * quit path and terminates cleanly without logging a spurious error.
     */
    void onStep(Observation obs) throws InterruptedException;

    /**
     * Called from the game loop finally block — fires unconditionally on all exit paths.
     * {@code result} is {@link GameResult#UNKNOWN} when the game was interrupted or crashed;
     * otherwise reflects the player result from the final SC2 observation response.
     */
    void onGameEnd(GameResult result);
}
