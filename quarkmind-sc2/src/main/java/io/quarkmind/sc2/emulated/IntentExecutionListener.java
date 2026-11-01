package io.quarkmind.sc2.emulated;

import io.quarkmind.sc2.intent.TrainIntent;

public interface IntentExecutionListener {
    void onTrainOutcome(int tick, TrainIntent intent, String resolvedBuildingTag,
                        boolean success, String rejectionReason);
}
