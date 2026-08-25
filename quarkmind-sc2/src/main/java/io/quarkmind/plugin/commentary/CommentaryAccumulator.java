package io.quarkmind.plugin.commentary;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.plugin.summarisation.GameMoment;
import io.quarkmind.plugin.summarisation.MomentBroker;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages windowed accumulation of L2 moments for narrative commentary dispatch.
 *
 * <p>Pattern B: accumulates game moments over a window (~45s or 4+ moments),
 * snapshots current L3/L4 strategic context from {@link NarrativeContextHolder},
 * and returns a trigger map for async Worker dispatch.
 *
 * <p>Window policy: 1000 frames (~45s) OR 4 moments, whichever comes first.
 * Minimum time floor: 672 frames (~30s) between narrative emits, enforced even
 * when count threshold is met (prevents narrative spam during intense battles).
 *
 * <p>Called from {@link io.quarkmind.agent.GameTickExecutor} at step 4b. Returns
 * trigger map (in-memory only); the executor passes it to {@code caseHub.signal()},
 * which writes to the CaseFile and dispatches the Worker.
 *
 * <p>Package-private constructor enables testing without CDI.
 *
 * <p>Refs #181 Task 6
 */
@ApplicationScoped
public class CommentaryAccumulator {

    /** 45 seconds at 22.4 frames/sec */
    static final long NARRATIVE_WINDOW_FRAMES = (long) (45 * 22.4);  // ~1000
    static final int  NARRATIVE_WINDOW_COUNT  = 4;

    /** Minimum time floor: 30 seconds at 22.4 frames/sec (same as phase window) */
    static final long MIN_TIME_FLOOR_FRAMES = (long) (30 * 22.4);  // 672

    @Inject MomentBroker momentBroker;
    @Inject NarrativeContextHolder contextHolder;

    private final EventAccumulator<GameMoment> accumulator;
    private long lastEmitFrame = 0;

    /** CDI constructor */
    public CommentaryAccumulator() {
        this.accumulator = new EventAccumulator<>(
            new WindowPolicy(NARRATIVE_WINDOW_FRAMES, NARRATIVE_WINDOW_COUNT));
    }

    /** Package-private constructor for testing without CDI */
    CommentaryAccumulator(EventStreamBus<GameMoment> momentBus,
                          NarrativeContextHolder contextHolder) {
        this.accumulator = new EventAccumulator<>(
            new WindowPolicy(NARRATIVE_WINDOW_FRAMES, NARRATIVE_WINDOW_COUNT));
        this.momentBus = momentBus;
        this.contextHolder = contextHolder;
    }

    private EventStreamBus<GameMoment> momentBus;

    @PostConstruct
    void init() {
        if (momentBus == null) {
            momentBus = momentBroker.momentBus();
        }

        // Subscribe to L2 moment bus — feeds accumulator
        momentBus.subscribe(m -> true, accumulator::collect);
    }

    /**
     * Tick accumulator — returns trigger map if window emits AND minimum time floor met.
     *
     * <p>Called from {@link io.quarkmind.agent.GameTickExecutor} at step 4b after
     * {@link io.quarkmind.plugin.summarisation.SummarisationLifecycle#tick(long)}.
     *
     * <p>If window emits AND (now - lastEmitFrame >= 672), drains batch, snapshots
     * context from {@link NarrativeContextHolder}, and returns trigger map with:
     * <ul>
     *   <li>{@code batch} — List of {@link LevelEvent<GameMoment>}</li>
     *   <li>{@code context} — Map with phase/rationale/arc (from {@link NarrativeContextHolder#snapshot()})</li>
     * </ul>
     *
     * <p>Minimum time floor prevents narrative spam during intense battles — Pattern A
     * (reactive) handles play-by-play; Pattern B waits for a lull to summarize.
     *
     * @param now current game frame
     * @return trigger map (key: {@link QuarkMindCaseFile#COMMENTARY_NARRATIVE_TRIGGER}),
     *         or empty map if window has not emitted or time floor not met
     */
    public Map<String, Object> tick(long now) {
        if (!accumulator.shouldEmit(now)) {
            return Map.of();
        }

        // Enforce minimum time floor — even if count threshold met, block if <672 frames
        if (now - lastEmitFrame < MIN_TIME_FLOOR_FRAMES) {
            return Map.of();
        }

        // Drain batch + snapshot context
        List<LevelEvent<GameMoment>> batch = accumulator.drain();
        Map<String, String> context = contextHolder.snapshot();

        // Update last emit frame
        lastEmitFrame = now;

        // Build trigger map
        var triggerPayload = new HashMap<String, Object>();
        triggerPayload.put("batch", batch);
        triggerPayload.put("context", context);

        // Promote CBR fields from context snapshot to cbrContext map at root level
        // so appendCbrContext() finds them (same structure as reactive triggers)
        String cbrCount = context.get("cbr_similar_count");
        String cbrPred  = context.get("cbr_prediction");
        String cbrInfl  = context.get("cbr_influenced");
        if (cbrCount != null || cbrPred != null) {
            var cbrCtx = new HashMap<String, Object>();
            if (cbrCount != null) cbrCtx.put("similarCount", Integer.parseInt(cbrCount));
            if (cbrPred != null)  cbrCtx.put("prediction", cbrPred);
            if ("true".equals(cbrInfl)) cbrCtx.put("influenced", true);
            triggerPayload.put("cbrContext", cbrCtx);
        }

        return Map.of(QuarkMindCaseFile.COMMENTARY_NARRATIVE_TRIGGER, triggerPayload);
    }

    /**
     * Clears accumulator and resets last emit frame on game restart.
     *
     * <p>NOTE: Does NOT clear bus subscriptions — they persist across games.
     */
    void onGameStarted(@Observes GameStarted event) {
        accumulator.clear();
        lastEmitFrame = 0;
    }
}
