package io.quarkmind.sc2.replay;

import io.quarkmind.domain.SC2Data;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.intent.TrainIntent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerranReplayCommandExtractorTest {

    // Nothing_4720935 = PvT, 18m48s; Nothing=Protoss(player1,userId0), RustyNikolaj=Terran(player2,userId1,wins)
    // Note: _4720936 is the PvZ replay used by ReplayCommandExtractorTest — different file.
    // Marine train observed 177 times in discovery — intentsIncludeMarineTrain is not fragile.
    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720935.SC2Replay");

    ReplayCommandStream stream;

    @BeforeAll
    void extract() {
        stream = ReplayCommandExtractor.extract(REPLAY, 2); // player 2 → Terran
    }

    @Test
    void extractsNonEmptyIntentListForTerranPlayer() {
        assertThat(stream.intents()).isNotEmpty();
    }

    @Test
    void extractsNonEmptyMovementOrdersForTerranPlayer() {
        assertThat(stream.movementOrders()).isNotEmpty();
    }

    @Test
    void intentsIncludeScvTrain() {
        assertThat(stream.intents()).anyMatch(ti ->
            ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.SCV);
    }

    @Test
    void intentsIncludeMarineTrain() {
        assertThat(stream.intents()).anyMatch(ti ->
            ti.intent() instanceof TrainIntent t && t.unitType() == UnitType.MARINE);
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
    void allIntentLoopsAreNonNegative() {
        assertThat(stream.intents()).allMatch(ti -> ti.loop() >= 0);
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
    void allMovementOrderTagsMatchTrackerFormat() {
        assertThat(stream.movementOrders())
            .allMatch(o -> o.unitTag().startsWith("r-"));
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
    void listsAreUnmodifiable() {
        assertThat(stream.movementOrders()).isUnmodifiable();
        assertThat(stream.intents()).isUnmodifiable();
    }
}
