package io.quarkmind.domain;

import java.util.List;

public record CounterInfo(
    List<CounterEntry> strongCounters,
    List<CounterEntry> weakCounters
) {}
