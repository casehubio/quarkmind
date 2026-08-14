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
    private int previousArmyValue;
    private String previousPosture;

    public DataStore<ScoutingIntelPayload> getIntelEvents() { return intelEvents; }
    public List<GameMoment> getDetectedMoments() { return detectedMoments; }
    public long getCurrentFrame() { return currentFrame; }
    public void setCurrentFrame(long currentFrame) { this.currentFrame = currentFrame; }
    public int getPreviousArmyValue() { return previousArmyValue; }
    public void setPreviousArmyValue(int previousArmyValue) { this.previousArmyValue = previousArmyValue; }
    public String getPreviousPosture() { return previousPosture; }
    public void setPreviousPosture(String previousPosture) { this.previousPosture = previousPosture; }

    private int supplyUsed;
    private int supplyCap;

    public int getSupplyUsed()                {return supplyUsed;}

    public void setSupplyUsed(int supplyUsed) {this.supplyUsed = supplyUsed;}

    public int getSupplyCap()                 {return supplyCap;}

    public void setSupplyCap(int supplyCap)   {this.supplyCap = supplyCap;}

}
