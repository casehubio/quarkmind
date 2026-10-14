package io.quarkmind.qa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class SpriteProxyResourceTest {

    @Test
    void unknownSpriteReturns404() {
        given()
            .when().get("/qa/sprites/unknown.jpg")
            .then()
            .statusCode(404);
    }

    @Test
    void knownSpriteReturns200WithJpegContentType() {
        // Requires internet access to Liquipedia — may timeout in CI
        try {
            given()
                .config(io.restassured.RestAssured.config()
                    .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", 15000)
                        .setParam("http.connection.timeout", 15000)))
                .when().get("/qa/sprites/SC2Probe.jpg")
                .then()
                .statusCode(200)
                .contentType("image/jpeg");
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Sprite proxy test skipped — external network unavailable: " + e.getMessage());
        }
    }
}
