package io.quarkmind.agency.needs;

import java.util.Map;

public interface NeedDefinition {

    String need();

    double baseDecayRate();

    Map<String, Double> dispositionModifiers();
}
