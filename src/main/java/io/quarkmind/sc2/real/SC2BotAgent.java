package io.quarkmind.sc2.real;

import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkmind.domain.GameState;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.TerrainProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SC2 frame callback implementation. CDI-managed; called from the game loop virtual thread.
 *
 * <p>No longer extends S2Agent (ocraft-s2client-bot removed). Implements
 * {@link SC2FrameCallback} and injects {@link QuarkusSC2Transport} to dispatch
 * actions and debug commands from within {@link #onStep}.
 *
 * <p>{@link #pendingDebugCommands}: only {@link SC2DebugScenarioRunner} (any thread)
 * enqueues, and the game loop virtual thread drains. ConcurrentLinkedQueue is safe
 * for this single-producer / single-consumer pattern.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class SC2BotAgent implements SC2FrameCallback {

    private static final Logger log = Logger.getLogger(SC2BotAgent.class);

    @Inject IntentQueue         intentQueue;
    @Inject TerrainProvider     terrainProvider;
    @Inject QuarkusSC2Transport transport;

    private final AtomicReference<GameState> latestGameState = new AtomicReference<>(null);
    private final Queue<Sc2Api.RequestDebug> pendingDebugCommands = new ConcurrentLinkedQueue<>();

    @Override
    public void onGameStart(ResponseGameInfo gameInfo) {
        gameInfo.getStartRaw().ifPresent(startRaw -> {
            ImageData pg = startRaw.getPathingGrid();
            TerrainGrid terrain = TerrainGrid.fromPathingGrid(
                pg.getData(), pg.getSize().getX(), pg.getSize().getY());
            terrainProvider.setTerrain(terrain);
            log.infof("[SC2] Terrain extracted — %dx%d pathing grid",
                pg.getSize().getX(), pg.getSize().getY());
        });
        log.info("[SC2] Game started");
    }

    @Override
    public void onStep(Observation obs) {
        try {
            GameState state = ObservationTranslator.translate(obs);
            latestGameState.set(state);
        } catch (Exception e) {
            log.warnf("[SC2] Observation translation failed: %s", e.getMessage());
        }

        List<ResolvedCommand> commands = ActionTranslator.translate(intentQueue.drainAll());
        if (!commands.isEmpty()) {
            transport.sendActions(commands);
        }

        if (!pendingDebugCommands.isEmpty()) {
            // Batch all pending debug commands into a single RequestDebug (one round-trip)
            Sc2Api.RequestDebug.Builder batch = Sc2Api.RequestDebug.newBuilder();
            Sc2Api.RequestDebug req;
            while ((req = pendingDebugCommands.poll()) != null) {
                batch.addAllDebug(req.getDebugList());
            }
            transport.sendDebug(batch.build());
        }
    }

    @Override
    public void onGameEnd() {
        log.info("[SC2] Game ended");
    }

    public GameState getLatestGameState() { return latestGameState.get(); }

    /** Enqueue a debug command to be sent on the next game step. Thread-safe. */
    public void enqueueDebugCommand(Sc2Api.RequestDebug request) {
        pendingDebugCommands.add(request);
    }
}
