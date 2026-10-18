package io.quarkmind.sc2.detector;

import java.util.ArrayDeque;
import java.util.Deque;

public class TemporalAssessmentTracker {

    private final int windowSize;
    private final Deque<double[]> window = new ArrayDeque<>();
    private int previousDominant = -1;

    public TemporalAssessmentTracker(int windowSize) {
        this.windowSize = windowSize;
    }

    public void recordPosterior(double[] posterior) {
        window.addLast(posterior.clone());
        if (window.size() > windowSize) {
            window.removeFirst();
        }
    }

    public double[] getSmoothedPosterior() {
        if (window.isEmpty()) return null;

        int dim = window.peek().length;
        double[] avg = new double[dim];
        for (double[] p : window) {
            for (int i = 0; i < dim; i++) avg[i] += p[i];
        }
        for (int i = 0; i < dim; i++) avg[i] /= window.size();
        return avg;
    }

    public boolean detectTransition() {
        double[] smoothed = getSmoothedPosterior();
        if (smoothed == null) return false;

        int currentDominant = 0;
        for (int i = 1; i < smoothed.length; i++) {
            if (smoothed[i] > smoothed[currentDominant]) {
                currentDominant = i;
            }
        }

        boolean transitioned = previousDominant >= 0
            && currentDominant != previousDominant;
        previousDominant = currentDominant;
        return transitioned;
    }

    public void reset() {
        window.clear();
        previousDominant = -1;
    }
}
