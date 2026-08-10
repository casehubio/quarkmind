package io.quarkmind.plugin.summarisation;

import io.casehub.annotation.CaseType;
import io.casehub.api.context.CaseContext;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.EventStreamBus;
import io.casehub.blocks.summarisation.LevelEvent;
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
    private int previousArmyValue = 0;
    private String previousPosture = null;
    private long   lastSupplyBlockFrame = -1;


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
        Long    frameL     = ctx.getAs(QuarkMindCaseFile.GAME_FRAME, Long.class);
        long    frame      = frameL != null ? frameL : 0L;
        Integer supplyUsed = ctx.getAs(QuarkMindCaseFile.SUPPLY_USED, Integer.class);
        Integer supplyCap  = ctx.getAs(QuarkMindCaseFile.SUPPLY_CAP, Integer.class);
        List<GameMoment> moments = fireRules(frame,
                                             supplyUsed != null ? supplyUsed : 0,
                                             supplyCap != null ? supplyCap : 0);

        if (!moments.isEmpty()) {
            ctx.set(QuarkMindCaseFile.MOMENTS_LATEST, moments);
        }}

    List<GameMoment> fireRules(long frame, int supplyUsed, int supplyCap) {
        if (pendingIntel.isEmpty()) {return List.of();}

        var data = new MomentDetectionRuleUnit();
        data.setCurrentFrame(frame);
        data.setPreviousArmyValue(previousArmyValue);
        data.setPreviousPosture(previousPosture);
        data.setSupplyUsed(supplyUsed);
        data.setSupplyCap(supplyCap);
        for (var payload : pendingIntel) {
            data.getIntelEvents().add(payload);
        }

        try (RuleUnitInstance<MomentDetectionRuleUnit> instance = ruleUnit.createInstance(data)) {
            instance.fire();
        }

        for (var payload : pendingIntel) {
            if (payload instanceof ScoutingIntelPayload.ArmySize armySize) {
                previousArmyValue = armySize.count();
            } else if (payload instanceof ScoutingIntelPayload.PostureUpdate postureUpdate) {
                previousPosture = postureUpdate.posture();
            }
        }
        pendingIntel.clear();

        List<GameMoment> deduplicated = new ArrayList<>();
        for (var moment : data.getDetectedMoments()) {
            if (moment.type() == GameMomentType.FIRST_CONTACT) {
                if (firstContactFired) {continue;}
                firstContactFired = true;
            }
            if (moment.type() == GameMomentType.SUPPLY_BLOCK) {
                if (lastSupplyBlockFrame >= 0 && frame - lastSupplyBlockFrame < 224) {continue;}
                lastSupplyBlockFrame = frame;
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
        firstContactFired    = false;
        previousArmyValue    = 0;
        previousPosture      = null;
        lastSupplyBlockFrame = -1;}

}
