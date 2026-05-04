package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.bot.S2Agent;
import com.github.ocraft.s2client.protocol.observation.spatial.ImageData;
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
 * ocraft S2Agent bridge. CDI-managed — Quarkus creates the instance; ocraft calls the callbacks.
 *
 * @ApplicationScoped is always-active in Quarkus: no ContextNotActiveException risk from
 * ocraft's background game-loop thread. onGameStart() is guaranteed to fire before onStep().
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class SC2BotAgent extends S2Agent {

    private static final Logger log = Logger.getLogger(SC2BotAgent.class);

    @Inject IntentQueue     intentQueue;
    @Inject TerrainProvider terrainProvider;

    private final AtomicReference<GameState> latestGameState = new AtomicReference<>(null);
    private final Queue<Runnable> pendingDebugCommands = new ConcurrentLinkedQueue<>();

    @Override
    public void onGameStart() {
        observation().getGameInfo().getStartRaw().ifPresent(raw -> {
            ImageData pg = raw.getPathingGrid();
            TerrainGrid terrain = TerrainGrid.fromPathingGrid(
                pg.getData(), pg.getSize().getX(), pg.getSize().getY());
            terrainProvider.setTerrain(terrain);
            log.infof("[SC2] Terrain extracted — %dx%d pathing grid",
                pg.getSize().getX(), pg.getSize().getY());
        });
        log.info("[SC2] Game started");
    }

    @Override
    public void onStep() {
        try {
            GameState state = ObservationTranslator.translate(observation());
            latestGameState.set(state);
        } catch (Exception e) {
            log.warnf("[SC2] Observation translation failed: %s", e.getMessage());
        }

        List<ResolvedCommand> commands = ActionTranslator.translate(intentQueue.drainAll());
        commands.forEach(cmd ->
            cmd.target().ifPresentOrElse(
                pos -> actions().unitCommand(cmd.tag(), cmd.ability(), pos, false),
                ()  -> actions().unitCommand(cmd.tag(), cmd.ability(), false)
            )
        );

        if (!pendingDebugCommands.isEmpty()) {
            Runnable cmd;
            while ((cmd = pendingDebugCommands.poll()) != null) cmd.run();
            debug().sendDebug();
        }
    }

    @Override
    public void onGameEnd() {
        log.info("[SC2] Game ended");
    }

    public GameState getLatestGameState() { return latestGameState.get(); }

    public void enqueueDebugCommand(Runnable command) { pendingDebugCommands.add(command); }
}
