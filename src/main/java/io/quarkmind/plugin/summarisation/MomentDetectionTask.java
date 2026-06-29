package io.quarkmind.plugin.summarisation;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.core.CaseFile;
import io.quarkmind.agent.CaseFileContext;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.MomentDetectionSeam;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.sc2.GameStarted;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.drools.ruleunits.api.RuleUnit;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@ApplicationScoped
@CaseType("starcraft-game")
public class MomentDetectionTask implements MomentDetectionSeam {

    static final EventLevel LEVEL_2 = new EventLevel("moment", 2);
    private static final Logger log = Logger.getLogger(MomentDetectionTask.class);

    private final RuleUnit<MomentDetectionRuleUnit> ruleUnit;
    private final List<ScoutingIntelPayload> pendingIntel = new ArrayList<>();

    private EventStreamBus<ScoutingIntelPayload> level1Bus;
    private EventStreamBus<GameMoment> momentBus;
    private boolean firstContactFired = false;

    @Inject
    public MomentDetectionTask(RuleUnit<MomentDetectionRuleUnit> ruleUnit) {
        this.ruleUnit = ruleUnit;
    }

    @Inject ScoutingIntelBroker scoutingBroker;

    @PostConstruct
    void init() {
        if (level1Bus == null && scoutingBroker != null) {
            level1Bus = scoutingBroker.level1Bus();
        }
        if (level1Bus != null) {
            level1Bus.subscribe(p -> true, e -> pendingIntel.add(e.payload()));
        }
    }

    void setLevel1Bus(EventStreamBus<ScoutingIntelPayload> bus) { this.level1Bus = bus; }
    void setMomentBus(EventStreamBus<GameMoment> bus) { this.momentBus = bus; }

    @Override public String getId()   { return "summarisation.moment-detection"; }
    @Override public String getName() { return "Moment Detection (L1→L2)"; }

    @Override
    public Set<String> requires() {
        return Set.of(
            QuarkMindCaseFile.ENEMY_UNITS,
            QuarkMindCaseFile.ENEMY_POSTURE,
            QuarkMindCaseFile.TIMING_ATTACK_INCOMING);
    }

    @Override
    public Predicate<CaseContext> activateIf() {
        return ctx -> true;
    }

    @Override
    public Set<String> produces() {
        return Set.of(QuarkMindCaseFile.MOMENTS_LATEST);
    }

    @Override
    public void execute(CaseContext ctx) {
        Long frameL = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
        long frame = frameL != null ? frameL : 0L;
        List<GameMoment> moments = fireRules(frame);

        // Write detected moments to CaseFile for downstream plugins
        if (!moments.isEmpty()) {
            ctx.set(QuarkMindCaseFile.MOMENTS_LATEST, moments);
        }
    }

    List<GameMoment> fireRules(long frame) {
        if (pendingIntel.isEmpty()) return List.of();

        var data = new MomentDetectionRuleUnit();
        data.setCurrentFrame(frame);
        for (var payload : pendingIntel) {
            data.getIntelEvents().add(payload);
        }
        pendingIntel.clear();

        try (RuleUnitInstance<MomentDetectionRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        // Apply deduplication after Drools fires
        List<GameMoment> deduplicated = new ArrayList<>();
        for (var moment : data.getDetectedMoments()) {
            // FIRST_CONTACT: only fire once per game
            if (moment.type() == GameMomentType.FIRST_CONTACT) {
                if (firstContactFired) continue;
                firstContactFired = true;
            }

            deduplicated.add(moment);
            if (momentBus != null) {
                momentBus.publish(new LevelEvent<>(moment, frame, LEVEL_2));
            }
            log.debugf("[MOMENT] %s at frame %d", moment.type(), frame);
        }

        return deduplicated;
    }

    void onGameStarted(@Observes GameStarted event) {
        pendingIntel.clear();
        firstContactFired = false;
    }

    // Phase 1 bridges
    @Override public Set<String> entryCriteria() { return requires(); }
    @Override public Set<String> producedKeys()  { return produces(); }

    @Override
    public boolean canActivate(CaseFile caseFile) {
        return testActivation(new CaseFileContext(caseFile));
    }

    @Override
    public void execute(CaseFile caseFile) {
        var ctx = new CaseFileContext(caseFile);
        execute(ctx);
        produces().forEach(k -> { Object v = ctx.get(k); if (v != null) caseFile.put(k, v); });
    }
}
