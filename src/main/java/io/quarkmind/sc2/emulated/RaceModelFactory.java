package io.quarkmind.sc2.emulated;

import io.quarkmind.domain.Race;

/**
 * Maps Race to a fresh RaceModel instance. Single point of knowledge about all three
 * SC2 race implementations. EmulatedEngine calls this; EmulatedGame holds the result.
 */
public class RaceModelFactory {

    private RaceModelFactory() {}

    static RaceModel forRace(final Race race) {
        return switch (race) {
            case PROTOSS -> new ProtossRaceModel();
            case TERRAN  -> new TerranRaceModel();
            case ZERG    -> new ZergRaceModel();
        };
    }
}
