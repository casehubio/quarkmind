package io.quarkmind.plugin;

import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.plugin.EconomicsTask;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.IntentQueue;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Basic Protoss economics: probe production and pylon supply management.
 *
 * <p><b>Status:</b> superseded by {@link FlowEconomicsTask} as the active CaseHub plugin.
 * Retained as a plain class for direct-instantiation tests and as a reference implementation.
 */
public class BasicEconomicsTask implements EconomicsTask {

    static final int PROBE_CAP       = 22;
    static final int SUPPLY_HEADROOM = 4;
    static final int MAX_SUPPLY      = 200;
    static final int PROBE_COST      = 50;
    static final int PYLON_COST      = 100;

    private static final Logger log = Logger.getLogger(BasicEconomicsTask.class);

    private final IntentQueue intentQueue;

    public BasicEconomicsTask(IntentQueue intentQueue) {
        this.intentQueue = intentQueue;
    }

    @Override public String getId()   { return "economics.basic"; }
    @Override public String getName() { return "Basic Economics"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> ctx.contains(QuarkMindCaseFile.READY);
    }

    @Override
    public void execute(final CaseContext ctx) {
        int supplyUsed = ctx.getOrDefault(QuarkMindCaseFile.SUPPLY_USED, 0);
        int supplyCap  = ctx.getOrDefault(QuarkMindCaseFile.SUPPLY_CAP, 0);
        List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        ResourceBudget budget    = ctx.getOrDefault(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(0, 0));

        maybeBuildPylon(budget, supplyUsed, supplyCap, workers, buildings);
        maybeTrainProbe(budget, workers, buildings);

        log.debugf("[ECONOMICS] workers=%d/%d supply=%d/%d budget=%s",
            workers.size(), PROBE_CAP, supplyUsed, supplyCap, budget);
    }

    @Override public Set<String> produces() { return Set.of(); }

    // ── Phase 1 bridges ──────────────────────────────────────────────────────

    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(final CaseFile caseFile) {
        return activateIf().test(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(final CaseFile caseFile) {
        execute(new CaseFileContext(caseFile));
        // No outputs to sync back — produces() is empty
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void maybeBuildPylon(ResourceBudget budget, int supplyUsed, int supplyCap,
                                 List<Unit> workers, List<Building> buildings) {
        if (supplyCap >= MAX_SUPPLY) return;
        if (supplyUsed < supplyCap - SUPPLY_HEADROOM) return;
        boolean pylonPending = buildings.stream().anyMatch(b -> b.type() == BuildingType.PYLON && !b.isComplete());
        if (pylonPending) return;
        if (!budget.spendMinerals(PYLON_COST)) return;
        workers.stream().findFirst().ifPresent(probe -> {
            Point2d pos = pylonPosition(buildings.size());
            intentQueue.add(new BuildIntent(probe.tag(), BuildingType.PYLON, pos));
            log.debugf("[ECONOMICS] Queuing Pylon at %s (supply %d/%d)", pos, supplyUsed, supplyCap);
        });
    }

    private void maybeTrainProbe(ResourceBudget budget, List<Unit> workers, List<Building> buildings) {
        if (workers.size() >= PROBE_CAP) return;
        if (!budget.spendMinerals(PROBE_COST)) return;
        buildings.stream()
            .filter(b -> b.type() == BuildingType.NEXUS && b.isComplete()).findFirst()
            .ifPresent(nexus -> {
                intentQueue.add(new TrainIntent(nexus.tag(), UnitType.PROBE));
                log.debugf("[ECONOMICS] Queuing Probe (workers=%d/%d)", workers.size(), PROBE_CAP);
            });
    }

    /**
     * Returns a Pylon placement position. Cycles through a 4×4 grid starting at tile (15,15).
     * Slot index wraps modulo 16 — never generates out-of-bounds coordinates regardless of
     * how many buildings have been built.
     */
    public static Point2d pylonPosition(int buildingCount) {
        int slot = buildingCount % 16;
        return new Point2d(15 + (slot % 4) * 3, 15 + (slot / 4) * 3);
    }
}
