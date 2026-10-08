package io.quarkmind.sc2.intent;

public record MorphIntent(String unitTag, String sourceName, String targetName) implements Intent {}
