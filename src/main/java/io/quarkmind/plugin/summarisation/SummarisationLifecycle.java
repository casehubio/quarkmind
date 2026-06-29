package io.quarkmind.plugin.summarisation;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.SummarisationRunner;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.quarkmind.agent.plugin.SummarisationTickable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Manages the L2-L3 and L3-L4 summarisation runners.
 *
 * <p>Subscribes to {@link MomentBroker}'s moment bus (L2 events) and feeds
 * them through a two-stage pipeline:
 * <ul>
 *   <li>L2-L3: {@link GamePhaseSummariser} -- accumulates moments, emits phases</li>
 *   <li>L3-L4: {@link GameArcSummariser} -- accumulates phases, emits arcs</li>
 * </ul>
 *
 * <p>Called on every game tick from {@link io.quarkmind.agent.GameTickExecutor} after
 * the CaseEngine solve cycle and before dispatch.
 *
 * <p>Does NOT observe {@code GameStarted} directly. {@link MomentBroker} owns the
 * reset lifecycle: it calls {@link #reset()} to clear accumulators.
 *
 * <p>Refs #182
 */
@ApplicationScoped
public class SummarisationLifecycle implements SummarisationTickable {

    private static final Logger log = Logger.getLogger(SummarisationLifecycle.class);

    static final EventLevel LEVEL_3 = new EventLevel("phase", 3);
    static final EventLevel LEVEL_4 = new EventLevel("arc", 4);

    /** 30 seconds at 22.4 frames/sec */
    static final long PHASE_WINDOW_FRAMES = (long) (30 * 22.4);   // 672
    static final int  PHASE_WINDOW_COUNT  = 5;

    /** 60 seconds at 22.4 frames/sec */
    static final long ARC_WINDOW_FRAMES   = (long) (60 * 22.4);   // 1344
    static final int  ARC_WINDOW_COUNT    = 3;

    @Inject MomentBroker momentBroker;

    private final EventStreamBus<GamePhase> phaseBus = new EventStreamBus<>();
    private final EventStreamBus<GameArc>   arcBus   = new EventStreamBus<>();

    private SummarisationRunner<GameMoment, GamePhase> phaseRunner;
    private SummarisationRunner<GamePhase, GameArc>    arcRunner;

    @PostConstruct
    void init() {
        wireRunners();
        log.infof("[SUMMARISATION] Lifecycle initialised — phase window: %d frames / %d count, arc window: %d frames / %d count",
            PHASE_WINDOW_FRAMES, PHASE_WINDOW_COUNT, ARC_WINDOW_FRAMES, ARC_WINDOW_COUNT);
    }

    /**
     * Tick both runners. Called from {@link io.quarkmind.agent.GameTickExecutor}
     * after {@code createAndSolve()} and before {@code engine.dispatch()}.
     *
     * <p>Implements {@link SummarisationTickable} to decouple {@code GameTickExecutor}
     * from concrete plugin classes.
     */
    @Override
    public void tick(long gameFrame) {
        phaseRunner.tick(gameFrame);
        arcRunner.tick(gameFrame);
    }

    public EventStreamBus<GamePhase> phaseBus() { return phaseBus; }
    public EventStreamBus<GameArc>   arcBus()   { return arcBus; }

    /**
     * Clears accumulators (buffered events) on game restart.
     * Called by {@link MomentBroker#onGameStarted}.
     *
     * <p>NOTE: Does NOT clear bus subscriptions or re-call {@link #wireRunners()} —
     * subscriptions are application-scoped and persist across games. Clearing them
     * would orphan the L2→L3 and L3→L4 pipelines.
     */
    void reset() {
        phaseRunner.clear();  // clears EventAccumulator buffer
        arcRunner.clear();    // clears EventAccumulator buffer
        log.debugf("[SUMMARISATION] Accumulators cleared for new game");
    }

    private void wireRunners() {
        var phaseAccumulator = new EventAccumulator<GameMoment>(
            new WindowPolicy(PHASE_WINDOW_FRAMES, PHASE_WINDOW_COUNT));
        phaseRunner = new SummarisationRunner<>(
            phaseAccumulator, new GamePhaseSummariser(), phaseBus, LEVEL_3);

        var arcAccumulator = new EventAccumulator<GamePhase>(
            new WindowPolicy(ARC_WINDOW_FRAMES, ARC_WINDOW_COUNT));
        arcRunner = new SummarisationRunner<>(
            arcAccumulator, new GameArcSummariser(), arcBus, LEVEL_4);

        // L2 moments feed the phase accumulator
        momentBroker.momentBus().subscribe(m -> true,
            e -> phaseAccumulator.collect(e));

        // L3 phases feed the arc accumulator
        phaseBus.subscribe(p -> true,
            e -> arcAccumulator.collect(e));
    }
}
