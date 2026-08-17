package io.quarkmind.ville.server;

import io.quarkmind.agency.needs.NeedDefinition;
import io.quarkmind.ville.protocol.Position;
import io.quarkmind.ville.protocol.VilleIntent;
import java.util.List;

public class GameTick {

    private static final double ENERGY_RECOVERY_RATE = 1.5;
    private static final double SOCIAL_SATISFY_AMOUNT = 5.0;

    private final List<NeedDefinition> needDefinitions;

    public GameTick(List<NeedDefinition> needDefinitions) {
        this.needDefinitions = needDefinitions;
    }

    public void execute(WorldState world, double conversationRange, double movementSpeed) {
        world.incrementTick();

        for (var character : world.characters()) {
            if (!character.isConnected()) continue;

            boolean isMoving = character.movementTarget() != null;
            boolean hasNearby = hasCharacterWithinRange(character, world, conversationRange);

            for (var nd : needDefinitions) {
                double rate = nd.baseDecayRate();
                if (character.dispositionModifier() != null) {
                    rate = character.dispositionModifier().modifyDecayRate(nd.need(), rate);
                }

                switch (nd.need()) {
                    case "SOCIAL" -> {
                        if (!hasNearby) character.needState().decay("SOCIAL", rate);
                    }
                    case "ENERGY" -> {
                        if (isMoving) {
                            character.needState().decay("ENERGY", rate);
                        } else if (!hasNearby) {
                            character.needState().satisfy("ENERGY", ENERGY_RECOVERY_RATE);
                        }
                    }
                }
            }

            var intents = character.drainIntents();
            for (var intent : intents) {
                switch (intent) {
                    case VilleIntent.Move m -> character.setMovementTarget(m.target());
                    case VilleIntent.Talk t -> {
                        character.setLastDialogue(t.text());
                        character.needState().satisfy("SOCIAL", SOCIAL_SATISFY_AMOUNT);
                    }
                    case VilleIntent.Rest r -> character.setMovementTarget(null);
                    case VilleIntent.Emote e -> {}
                }
            }

            if (character.movementTarget() != null) {
                moveToward(character, character.movementTarget(), movementSpeed);
            }
        }
    }

    private void moveToward(CharacterState character, Position target, double speed) {
        var pos = character.position();
        double dx = target.x() - pos.x();
        double dy = target.y() - pos.y();
        double dz = target.z() - pos.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= speed) {
            character.setPosition(target);
            character.setMovementTarget(null);
        } else {
            double ratio = speed / dist;
            character.setPosition(new Position(
                    pos.x() + dx * ratio,
                    pos.y() + dy * ratio,
                    pos.z() + dz * ratio));
        }
    }

    private boolean hasCharacterWithinRange(CharacterState character, WorldState world, double range) {
        for (var other : world.characters()) {
            if (other == character || !other.isConnected()) continue;
            if (character.position().distanceTo(other.position()) <= range) return true;
        }
        return false;
    }
}
