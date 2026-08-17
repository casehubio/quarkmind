package io.quarkmind.ville.server;

import io.quarkmind.agency.needs.NeedDefinition;
import java.util.Map;

public class VilleNeedDefinition implements NeedDefinition {

    private final String need;
    private final double baseDecayRate;

    public VilleNeedDefinition(String need, double baseDecayRate) {
        this.need = need;
        this.baseDecayRate = baseDecayRate;
    }

    @Override
    public String need() { return need; }

    @Override
    public double baseDecayRate() { return baseDecayRate; }

    @Override
    public Map<String, Double> dispositionModifiers() { return Map.of(); }
}
