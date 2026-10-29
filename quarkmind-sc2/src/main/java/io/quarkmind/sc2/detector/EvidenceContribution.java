package io.quarkmind.sc2.detector;

public record EvidenceContribution(String variable, double influence, String observedState) {

    public EvidenceContribution(String variable, double influence) {
        this(variable, influence, null);
    }
}
