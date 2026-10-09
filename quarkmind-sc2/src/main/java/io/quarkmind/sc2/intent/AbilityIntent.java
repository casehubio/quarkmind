package io.quarkmind.sc2.intent;

public record AbilityIntent(String casterTag, String ability, String targetTag) implements Intent {}
