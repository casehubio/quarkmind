package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CbrLearningCurveIT {

    @Test
    void learningCurveEndpoint_returnsJson() {
        given()
            .when().get("/qa/cbr/learning-curve")
            .then()
            .statusCode(200)
            .body("totalGames", is(0))
            .body("overallWinRate", is(0.0f));
    }

    @Test
    void strategyEvolutionEndpoint_returnsJson() {
        given()
            .when().get("/qa/cbr/strategy-evolution")
            .then()
            .statusCode(200);
    }

    @Test
    void caseStatsEndpoint_returnsJson() {
        given()
            .when().get("/qa/cbr/case-stats")
            .then()
            .statusCode(200)
            .body("totalCases", is(0));
    }
}
