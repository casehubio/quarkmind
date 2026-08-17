package io.quarkmind.ville.protocol;

import io.quarkmind.agency.spi.WorldPerception;
import java.util.List;

public record VillePerception(
        long tick,
        CharacterSnapshot self,
        List<CharacterSnapshot> nearby,
        List<VilleEvent> events) implements WorldPerception {}
