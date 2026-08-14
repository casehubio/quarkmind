package io.quarkmind.domain;

import java.util.List;

public record CounterEntry(
    List<UnitType> units,
    String action
) {}
