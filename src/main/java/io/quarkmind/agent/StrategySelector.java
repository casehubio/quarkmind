package io.quarkmind.agent;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-game strategy selection state (L6).
 *
 * <p>Written by {@link StrategyTrustObserver} on CDI events (synchronous — single-threaded
 * at event time). Read by each {@link io.quarkmind.agent.plugin.StrategyTask} implementation's
 * {@code canActivate()} on the CaseEngine thread — {@code volatile} guarantees visibility.
 *
 * <p>{@code checkpointFired} uses {@link AtomicBoolean#compareAndSet} so the mid-game
 * pivot claim is race-free even if observers ever run concurrently.
 */
@ApplicationScoped
public class StrategySelector {

    private volatile String selectedId      = "strategy.drools";
    private volatile String opponentContext = QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
    private final AtomicBoolean checkpointFired = new AtomicBoolean(false);

    public void selectForGame(String strategyId, String context) {
        this.selectedId      = strategyId;
        this.opponentContext = context;
    }

    public boolean isSelected(String strategyId)  { return selectedId.equals(strategyId); }
    public String  getSelectedId()                { return selectedId; }
    public String  getOpponentContext()           { return opponentContext; }

    /** True after the first mid-game pivot; prevents further pivots this game. */
    public boolean isCheckpointFired()            { return checkpointFired.get(); }

    /** Claims the checkpoint — returns true only for the first caller per game. */
    public boolean claimCheckpoint()              { return checkpointFired.compareAndSet(false, true); }

    public void reset() {
        selectedId      = "strategy.drools";
        opponentContext = QuarkMindCapabilityTag.STRATEGY_VS_UNKNOWN;
        checkpointFired.set(false);
    }
}
