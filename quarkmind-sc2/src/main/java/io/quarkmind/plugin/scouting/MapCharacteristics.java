package io.quarkmind.plugin.scouting;

import java.util.Map;

public record MapCharacteristics(
    float rushDistance,
    float expansions,
    float size,
    float choke
) {

    public static final MapCharacteristics DEFAULT =
        new MapCharacteristics(0.5f, 0.5f, 0.5f, 0.5f);

    public float[] toArray(boolean hasPlayer, boolean hasOpponent) {
        return new float[] {
            rushDistance, expansions, size, choke,
            hasPlayer ? 1.0f : 0.0f,
            hasOpponent ? 1.0f : 0.0f
        };
    }

    private static final Map<String, MapCharacteristics> CATALOG = Map.ofEntries(
        Map.entry("Abyssal Reef", new MapCharacteristics(0.5f, 0.8f, 1.0f, 1.0f)),
        Map.entry("Acolyte", new MapCharacteristics(0.0f, 0.6f, 0.0f, 1.0f)),
        Map.entry("Ascension to Aiur", new MapCharacteristics(0.5f, 0.8f, 1.0f, 1.0f)),
        Map.entry("Catalyst", new MapCharacteristics(0.0f, 0.6f, 0.5f, 0.0f)),
        Map.entry("Dusk Towers", new MapCharacteristics(0.5f, 0.6f, 0.5f, 1.0f)),
        Map.entry("Frost", new MapCharacteristics(0.5f, 0.8f, 1.0f, 1.0f)),
        Map.entry("Habitation Station", new MapCharacteristics(0.0f, 0.4f, 0.0f, 1.0f)),
        Map.entry("King Sejong Station", new MapCharacteristics(0.5f, 0.6f, 0.5f, 1.0f)),
        Map.entry("Newkirk Precinct", new MapCharacteristics(0.5f, 0.6f, 0.5f, 1.0f)),
        Map.entry("Odyssey", new MapCharacteristics(1.0f, 0.8f, 1.0f, 0.0f)),
        Map.entry("Paladino Terminal", new MapCharacteristics(0.0f, 0.4f, 0.0f, 1.0f)),
        Map.entry("Proxima Station", new MapCharacteristics(0.5f, 0.6f, 0.5f, 0.0f)),
        Map.entry("Sequencer", new MapCharacteristics(0.0f, 0.6f, 0.5f, 1.0f)),
        Map.entry("Whirlwind", new MapCharacteristics(1.0f, 0.8f, 1.0f, 0.0f)),
        Map.entry("Worldship", new MapCharacteristics(0.5f, 0.8f, 1.0f, 1.0f))
    );

    public static MapCharacteristics forMap(String mapName) {
        return CATALOG.getOrDefault(mapName, DEFAULT);
    }
}
