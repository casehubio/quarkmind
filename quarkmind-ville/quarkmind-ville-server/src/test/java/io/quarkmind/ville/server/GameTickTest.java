package io.quarkmind.ville.server;

import io.quarkmind.agency.needs.NeedDefinition;
import io.quarkmind.ville.protocol.Position;
import io.quarkmind.ville.protocol.VilleIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GameTickTest {

    private static final double RANGE = 5.0;
    private static final double SPEED = 2.0;

    private WorldState world;
    private GameTick gameTick;

    @BeforeEach
    void setUp() {
        world = new WorldState();
        List<NeedDefinition> needs = List.of(
                new VilleNeedDefinition("SOCIAL", 1.0),
                new VilleNeedDefinition("ENERGY", 0.5));
        gameTick = new GameTick(needs);
    }

    @Test
    void characterMovesTowardTarget() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").setMovementTarget(new Position(10, 0, 0));
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").position().x()).isGreaterThan(0.0);
        assertThat(world.character("alice").position().x()).isLessThanOrEqualTo(SPEED);
    }

    @Test
    void characterStopsAtTarget() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").setMovementTarget(new Position(1, 0, 0));
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").position()).isEqualTo(new Position(1, 0, 0));
        assertThat(world.character("alice").movementTarget()).isNull();
    }

    @Test
    void moveIntentSetsTarget() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").queueIntent(new VilleIntent.Move(new Position(20, 0, 0)));
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").movementTarget()).isEqualTo(new Position(20, 0, 0));
    }

    @Test
    void restIntentCancelsMovement() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").setMovementTarget(new Position(20, 0, 0));
        world.character("alice").queueIntent(new VilleIntent.Rest());
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").movementTarget()).isNull();
    }

    @Test
    void socialDecaysWhenAlone() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        double before = world.character("alice").needState().get("SOCIAL");
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("SOCIAL")).isLessThan(before);
    }

    @Test
    void socialDoesNotDecayWhenNearby() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(3, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(true);
        double before = world.character("alice").needState().get("SOCIAL");
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("SOCIAL")).isEqualTo(before);
    }

    @Test
    void energyDecaysWhenMoving() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").setMovementTarget(new Position(20, 0, 0));
        double before = world.character("alice").needState().get("ENERGY");
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("ENERGY")).isLessThan(before);
    }

    @Test
    void energyRecoversWhenStillAndAlone() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").needState().set("ENERGY", 50.0);
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("ENERGY")).isGreaterThan(50.0);
    }

    @Test
    void energyDoesNotRecoverWhenNearby() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.addCharacter("bob", new Position(3, 0, 0));
        world.character("alice").setConnected(true);
        world.character("bob").setConnected(true);
        world.character("alice").needState().set("ENERGY", 50.0);
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("ENERGY")).isEqualTo(50.0);
    }

    @Test
    void disconnectedCharactersSkipNeedUpdates() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(false);
        double social = world.character("alice").needState().get("SOCIAL");
        double energy = world.character("alice").needState().get("ENERGY");
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").needState().get("SOCIAL")).isEqualTo(social);
        assertThat(world.character("alice").needState().get("ENERGY")).isEqualTo(energy);
    }

    @Test
    void talkIntentSetsLastDialogue() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").queueIntent(new VilleIntent.Talk("Hello!"));
        gameTick.execute(world, RANGE, SPEED);
        assertThat(world.character("alice").lastDialogue()).isEqualTo("Hello!");
    }

    @Test
    void dispositionModifierAffectsDecayRate() {
        world.addCharacter("alice", new Position(0, 0, 0));
        world.character("alice").setConnected(true);
        world.character("alice").setDispositionModifier((need, baseRate) ->
                "SOCIAL".equals(need) ? baseRate * 2.0 : baseRate);

        world.addCharacter("bob", new Position(0, 0, 0));
        world.character("bob").setConnected(true);

        double aliceBefore = world.character("alice").needState().get("SOCIAL");
        double bobBefore = world.character("bob").needState().get("SOCIAL");

        // Move them apart so SOCIAL decays
        world.character("alice").setPosition(new Position(100, 0, 0));
        gameTick.execute(world, RANGE, SPEED);

        double aliceDelta = aliceBefore - world.character("alice").needState().get("SOCIAL");
        double bobDelta = bobBefore - world.character("bob").needState().get("SOCIAL");
        assertThat(aliceDelta).isCloseTo(bobDelta * 2.0, within(0.01));
    }
}
