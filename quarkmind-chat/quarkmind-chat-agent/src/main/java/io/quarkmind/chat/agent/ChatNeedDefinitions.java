package io.quarkmind.chat.agent;

import io.quarkmind.agency.needs.NeedState;

public class ChatNeedDefinitions {

    private final double socialDecayRate;
    private final double curiosityDecayRate;
    private final double expressionBuildRate;

    public ChatNeedDefinitions(double socialDecayRate, double curiosityDecayRate,
                               double expressionBuildRate) {
        this.socialDecayRate = socialDecayRate;
        this.curiosityDecayRate = curiosityDecayRate;
        this.expressionBuildRate = expressionBuildRate;
    }

    public void decayAll(NeedState needs) {
        needs.decay("SOCIAL", socialDecayRate);
        needs.decay("CURIOSITY", curiosityDecayRate);
    }

    public void buildExpression(NeedState needs) {
        needs.satisfy("EXPRESSION", expressionBuildRate);
    }
}
