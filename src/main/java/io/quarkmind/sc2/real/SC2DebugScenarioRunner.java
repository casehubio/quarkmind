package io.quarkmind.sc2.real;

import SC2APIProtocol.Debug;
import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkmind.sc2.ScenarioRunner;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Triggers named scenarios against a live SC2 game via the SC2 Debug API.
 * Same scenario names as MockScenarioRunner — integration parity by design.
 *
 * <p>Each scenario builds a {@code RequestDebug} proto and enqueues it on
 * {@link SC2BotAgent}. The game loop virtual thread batches all pending debug
 * commands into a single RequestDebug and dispatches via
 * {@link QuarkusSC2Transport#sendDebug} after action dispatch each frame.
 * There is no threading constraint on when enqueueDebugCommand() may be called —
 * the queue is thread-safe.
 */
@IfBuildProfile("sc2")
@ApplicationScoped
public class SC2DebugScenarioRunner implements ScenarioRunner {
    private static final Logger log = Logger.getLogger(SC2DebugScenarioRunner.class);

    // Spawn positions (map-relative, typical two-player map layout)
    private static final Point2d NEAR_SELF_BASE   = Point2d.of(30f, 30f);
    private static final Point2d ENEMY_EXPANSION  = Point2d.of(100f, 100f);
    private static final Point2d SELF_SUPPLY_AREA = Point2d.of(35f, 35f);

    private static final int PLAYER_SELF  = 1;
    private static final int PLAYER_ENEMY = 2;

    private static final Set<String> AVAILABLE = Set.of(
        "spawn-enemy-attack", "set-resources-500", "supply-almost-capped", "enemy-expands"
    );

    @Inject RealSC2Engine engine;

    @Override
    public void run(String scenarioName) {
        SC2BotAgent agent = engine.getBotAgent();
        if (agent == null) {
            throw new IllegalStateException(
                "SC2 not connected — cannot run scenario: " + scenarioName);
        }
        log.infof("[SC2-DEBUG] Running scenario: %s", scenarioName);
        switch (scenarioName) {
            case "spawn-enemy-attack"   -> spawnEnemyAttack(agent);
            case "set-resources-500"    -> setResources(agent);
            case "supply-almost-capped" -> supplyAlmostCapped(agent);
            case "enemy-expands"        -> enemyExpands(agent);
            default -> throw new IllegalArgumentException(
                "Unknown scenario: " + scenarioName + ". Available: " + AVAILABLE);
        }
    }

    @Override
    public Set<String> availableScenarios() {
        return AVAILABLE;
    }

    private void spawnEnemyAttack(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing spawn-enemy-attack: 2x Zealot + 1x Stalker for player %d", PLAYER_ENEMY);
        agent.enqueueDebugCommand(
            Sc2Api.RequestDebug.newBuilder()
                .addDebug(createUnit(Units.PROTOSS_ZEALOT,  NEAR_SELF_BASE, PLAYER_ENEMY, 2))
                .addDebug(createUnit(Units.PROTOSS_STALKER, NEAR_SELF_BASE, PLAYER_ENEMY, 1))
                .build());
    }

    /**
     * KNOWN_LIMITATION: The SC2 debug proto has no exact-value resource setter.
     * {@code DebugSetUnitValue} sets per-unit life/shields/energy only — not player minerals/vespene.
     * {@code DebugGameState} flags ({@code minerals}, {@code gas}) are boolean cheat toggles.
     * {@code DebugGameState.all_resources} maximises both minerals and vespene.
     * Exact 500/200 values are not achievable via the SC2 debug API.
     */
    private void setResources(SC2BotAgent agent) {
        log.warnf("[SC2-DEBUG] set-resources-500: SC2 debug API has no exact resource setter. " +
                  "Using DebugGameState.all_resources (maximises both). " +
                  "See KNOWN_LIMITATION in SC2DebugScenarioRunner.setResources().");
        agent.enqueueDebugCommand(
            Sc2Api.RequestDebug.newBuilder()
                .addDebug(Debug.DebugCommand.newBuilder()
                    .setGameState(Debug.DebugGameState.all_resources)
                    .build())
                .build());
    }

    private void supplyAlmostCapped(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing supply-almost-capped: 8x Probe for player %d", PLAYER_SELF);
        agent.enqueueDebugCommand(
            Sc2Api.RequestDebug.newBuilder()
                .addDebug(createUnit(Units.PROTOSS_PROBE, SELF_SUPPLY_AREA, PLAYER_SELF, 8))
                .build());
    }

    private void enemyExpands(SC2BotAgent agent) {
        log.infof("[SC2-DEBUG] Enqueueing enemy-expands: 1x Probe for player %d", PLAYER_ENEMY);
        agent.enqueueDebugCommand(
            Sc2Api.RequestDebug.newBuilder()
                .addDebug(createUnit(Units.PROTOSS_PROBE, ENEMY_EXPANSION, PLAYER_ENEMY, 1))
                .build());
    }

    private static Debug.DebugCommand createUnit(Units unit, Point2d pos, int owner, int quantity) {
        return Debug.DebugCommand.newBuilder()
            .setCreateUnit(Debug.DebugCreateUnit.newBuilder()
                .setUnitType(unit.getUnitTypeId())
                .setOwner(owner)
                .setPos(pos.toSc2Api())
                .setQuantity(quantity)
                .build())
            .build();
    }
}
