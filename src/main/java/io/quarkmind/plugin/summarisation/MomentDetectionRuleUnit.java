package io.quarkmind.plugin.summarisation;

import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

import java.util.ArrayList;
import java.util.List;

public class MomentDetectionRuleUnit implements RuleUnitData {

    private final DataStore<ScoutingIntelPayload> intelEvents = DataSource.createStore();
    private final List<GameMoment> detectedMoments = new ArrayList<>();
    private long currentFrame;

    public DataStore<ScoutingIntelPayload> getIntelEvents() { return intelEvents; }
    public List<GameMoment> getDetectedMoments() { return detectedMoments; }
    public long getCurrentFrame() { return currentFrame; }
    public void setCurrentFrame(long currentFrame) { this.currentFrame = currentFrame; }
}
