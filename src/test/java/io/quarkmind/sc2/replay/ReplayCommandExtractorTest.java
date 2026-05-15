package io.quarkmind.sc2.replay;

import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.intent.BuildIntent;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayCommandExtractorTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    ReplayCommandStream stream;

    @BeforeAll
    void extract() {
        stream = ReplayCommandExtractor.extract(REPLAY, 1);
    }

    @Test
    void extractsNonEmptyIntentListForProtossPlayer() {
        assertThat(stream.intents()).isNotEmpty();
    }

    @Test
    void extractsNonEmptyMovementOrdersForProtossPlayer() {
        assertThat(stream.movementOrders()).isNotEmpty();
    }

    @Test
    void intentsAreOrderedByLoop() {
        var intents = stream.intents();
        for (int i = 1; i < intents.size(); i++) {
            assertThat(intents.get(i).loop())
                .isGreaterThanOrEqualTo(intents.get(i - 1).loop());
        }
    }

    @Test
    void movementOrdersAreOrderedByLoop() {
        var orders = stream.movementOrders();
        for (int i = 1; i < orders.size(); i++) {
            assertThat(orders.get(i).loop())
                .isGreaterThanOrEqualTo(orders.get(i - 1).loop());
        }
    }

    @Test
    void allIntentLoopsAreNonNegative() {
        assertThat(stream.intents()).allMatch(ti -> ti.loop() >= 0);
    }

    @Test
    void trainIntentsReferenceKnownUnitTypes() {
        stream.intents().stream()
            .filter(ti -> ti.intent() instanceof TrainIntent)
            .map(ti -> (TrainIntent) ti.intent())
            .forEach(t -> assertThat(SC2Data.trainTimeInTicks(t.unitType()))
                .as("trainTimeInTicks must be > 0 for %s", t.unitType())
                .isGreaterThan(0));
    }

    @Test
    void allMovementOrderTagsMatchTrackerFormat() {
        assertThat(stream.movementOrders()).allMatch(o -> o.unitTag().startsWith("r-"));
    }

    @Test
    void listsAreUnmodifiable() {
        assertThat(stream.movementOrders()).isUnmodifiable();
        assertThat(stream.intents()).isUnmodifiable();
    }
}
