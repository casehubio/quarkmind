package io.quarkmind.ville.protocol;

import java.util.Map;

public record CharacterSnapshot(
        String id,
        Position position,
        Map<String, Double> needs,
        String lastDialogue) {}
