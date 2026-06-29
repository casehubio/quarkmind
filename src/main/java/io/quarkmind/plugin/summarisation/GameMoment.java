package io.quarkmind.plugin.summarisation;

import java.util.Map;

public record GameMoment(GameMomentType type, long gameFrame, Map<String, Object> context) {}
