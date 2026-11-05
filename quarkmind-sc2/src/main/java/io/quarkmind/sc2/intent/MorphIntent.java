package io.quarkmind.sc2.intent;

public record MorphIntent(String unitTag, String sourceName, String targetName,
                            long completionLoop) implements Intent {
    public static MorphIntent of(String unitTag, String sourceName, String targetName) {
        return new MorphIntent(unitTag, sourceName, targetName, -1);
    }
}
