package io.quarkmind.sc2.real;

import com.github.ocraft.s2client.protocol.observation.Observation;
import io.quarkmind.domain.GameState;
import org.junit.jupiter.api.Test;

import static com.github.ocraft.s2client.protocol.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ObservationTranslator.translate(Observation obs) — the new signature
 * replacing translate(ObservationInterface obs). Uses ocraft Fixtures to build realistic
 * proto objects, exercising the full path from proto → ocraft wrapper → GameState.
 */
class ObservationTranslatorIntegrationTest {

    @Test
    void translate_extractsMinerals() {
        Observation obs = Observation.from(sc2ApiObservation());

        GameState state = ObservationTranslator.translate(obs);

        assertThat(state.minerals()).as("minerals").isEqualTo(MINERALS);
    }

    @Test
    void translate_extractsVespene() {
        Observation obs = Observation.from(sc2ApiObservation());

        GameState state = ObservationTranslator.translate(obs);

        assertThat(state.vespene()).as("vespene").isEqualTo(VESPENE);
    }

    @Test
    void translate_extractsSupplyCapAndUsed() {
        // PlayerCommon.foodCap → GameState.supply; PlayerCommon.foodUsed → GameState.supplyUsed
        Observation obs = Observation.from(sc2ApiObservation());

        GameState state = ObservationTranslator.translate(obs);

        assertThat(state.supply()).as("supply (foodCap)").isEqualTo(FOOD_CAP);
        assertThat(state.supplyUsed()).as("supplyUsed (foodUsed)").isEqualTo(FOOD_USED);
    }

    @Test
    void translate_gameFrameIsWidenedFromIntToLong() {
        // Observation.getGameLoop() returns int; GameState.gameFrame is long — widening implicit
        Observation obs = Observation.from(sc2ApiObservation());

        GameState state = ObservationTranslator.translate(obs);

        assertThat(state.gameFrame()).as("gameFrame widened from int").isEqualTo((long) GAME_LOOP);
    }

    @Test
    void translate_doesNotThrowOnNullUnitGuardRemoval() {
        // ObservationRaw.getUnits() built with .filter(Raw.Unit::hasTag).map(Unit::from) — never null.
        Observation obs = Observation.from(sc2ApiObservation());
        assertThat(ObservationTranslator.translate(obs)).isNotNull();
    }

    @Test
    void translate_selfAllianceUnitIsClassifiedCorrectly() {
        // sc2ApiObservationRaw() adds exactly one unit: Alliance=Self, type=59.
        // UnitTest.java (ocraft) confirms type 59 maps to Units.PROTOSS_NEXUS — a building.
        // So the unit must land in myBuildings, not myUnits.
        // This exercises the Alliance filter + building classifier path.
        Observation obs = Observation.from(sc2ApiObservation());

        GameState state = ObservationTranslator.translate(obs);

        // Only one unit total in the fixture, it's SELF alliance → classified by building set
        int totalSelfEntities = state.myUnits().size() + state.myBuildings().size();
        assertThat(totalSelfEntities)
            .as("Exactly one SELF entity must appear in myUnits or myBuildings")
            .isEqualTo(1);
        assertThat(state.enemyUnits())
            .as("SELF unit must not appear in enemyUnits")
            .isEmpty();
        // The unit is PROTOSS_NEXUS (type 59) which IS a building — it lands in myBuildings
        assertThat(state.myBuildings())
            .as("PROTOSS_NEXUS (type 59) is a building — must be in myBuildings")
            .hasSize(1);
    }
}
