package io.quarkmind.plugin.flow;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.core.CaseFile;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ResourceBudget;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.EconomicsTask;
import io.quarkmind.agent.plugin.ScoutingIntelConsumer;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.agent.plugin.ScoutingIntelPreferences;
import io.quarkmind.agent.plugin.ScoutingIntelType;
import io.quarkmind.domain.Building;
import io.quarkmind.domain.BuildingType;
import io.quarkmind.domain.Resource;
import io.quarkmind.domain.Unit;
import io.smallrye.reactive.messaging.MutinyEmitter;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * CaseHub plugin shim for the Flow-backed economics implementation.
 *
 * <p>Each CaseHub tick, this class extracts game state from the CaseContext into a
 * {@link GameStateTick} and emits it on the {@code economics-ticks} in-memory channel.
 * The long-lived {@link EconomicsFlow} instance processes the tick asynchronously
 * (one-tick lag — see ADR-0001).
 *
 * <p>The budget in the tick is a snapshot copy — independent of the CaseHub shared
 * ResourceBudget, which may already be partially consumed by other plugins before
 * the flow processes it.
 */
@ApplicationScoped
@CaseType("starcraft-game")
public class FlowEconomicsTask implements EconomicsTask, ScoutingIntelConsumer {

    private static final Logger log = Logger.getLogger(FlowEconomicsTask.class);

    @Inject
    @Channel("economics-ticks")
    MutinyEmitter<GameStateTick> emitter;

    @Inject ScoutingIntelBroker broker;
    @Inject PreferenceProvider preferenceProvider;

    // Safe default before @PostConstruct fires
    Set<ScoutingIntelType> subscribedTypes = Set.of();

    @PostConstruct
    void init() {
        refreshSubscriptions(preferenceProvider.resolve(SettingsScope.root()));
    }

    @Override
    public void refreshSubscriptions(Preferences prefs) {
        // Economics subscribes to ARMY_SIZE; default true (override — global default is false)
        boolean wantsArmySize = prefs.getOrDefault(
            ScoutingIntelPreferences.consumerKey(getId(), ScoutingIntelType.ARMY_SIZE, true)
        ).asBoolean();
        subscribedTypes = wantsArmySize ? Set.of(ScoutingIntelType.ARMY_SIZE) : Set.of();
    }

    @Override
    public Set<ScoutingIntelType> subscribedIntelTypes() { return subscribedTypes; }

    @Override public String getId()   { return "economics.flow"; }
    @Override public String getName() { return "Flow Economics"; }

    // ── New engine API ───────────────────────────────────────────────────────

    @Override
    public Set<String> requires() { return Set.of(QuarkMindCaseFile.READY); }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> ctx.contains(QuarkMindCaseFile.READY);
    }

    @Override
    public void execute(final CaseContext ctx) {
        List<Unit>     workers   = ctx.getList(QuarkMindCaseFile.WORKERS,      Unit.class);
        List<Building> buildings = ctx.getList(QuarkMindCaseFile.MY_BUILDINGS, Building.class);
        List<Resource> geysers   = ctx.getList(QuarkMindCaseFile.GEYSERS,      Resource.class);
        int supplyUsed = ctx.getOrDefault(QuarkMindCaseFile.SUPPLY_USED, 0);
        int supplyCap  = ctx.getOrDefault(QuarkMindCaseFile.SUPPLY_CAP,  0);
        int minerals   = ctx.getOrDefault(QuarkMindCaseFile.MINERALS,    0);
        int vespene    = ctx.getOrDefault(QuarkMindCaseFile.VESPENE,     0);
        String strategy        = ctx.getOrDefault(QuarkMindCaseFile.STRATEGY, "MACRO");
        ResourceBudget shared  = ctx.getOrDefault(QuarkMindCaseFile.RESOURCE_BUDGET, new ResourceBudget(0, 0));

        // Snapshot copy — flow processes this one tick later; shared budget already consumed
        ResourceBudget snapshot = new ResourceBudget(shared.minerals(), shared.vespene());
        boolean gasReady = buildings.stream().anyMatch(b -> b.type() == BuildingType.GATEWAY);

        // Read army size from broker (Stack 1) — used by EconomicsDecisionService for probe balance
        int armySize = broker.current(ScoutingIntelType.ARMY_SIZE,
                ScoutingIntelPayload.ArmySize.class)
            .map(ScoutingIntelPayload.ArmySize::count)
            .orElse(0);

        GameStateTick tick = new GameStateTick(minerals, vespene, supplyUsed, supplyCap,
            workers, buildings, geysers, snapshot, strategy, gasReady, armySize);

        emitter.sendAndForget(tick);
        log.debugf("[FLOW-ECONOMICS] Tick emitted: workers=%d supply=%d/%d gasReady=%b",
            workers.size(), supplyUsed, supplyCap, gasReady);
    }

    @Override public Set<String> produces() { return Set.of(); }

    // ── Phase 1 bridges — removed when poc CaseFile is dropped in Phase 2 ──

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
}
