package io.quarkmind.sc2.emulated;

public interface WorkerPhaseListener {
    void onPhaseChange(String workerTag, WorkerPhase from, WorkerPhase to, double gameLoop);

    void onDelivery(String workerTag, double minerals, double gameLoop);
}