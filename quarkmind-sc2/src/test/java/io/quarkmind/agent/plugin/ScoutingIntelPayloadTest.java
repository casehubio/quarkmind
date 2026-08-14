package io.quarkmind.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static io.quarkmind.agent.plugin.ScoutingIntelType.*;

class ScoutingIntelPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void threatPosition_serialisesWithTypeDiscriminator() throws Exception {
        var payload = new ScoutingIntelPayload.ThreatPosition(new Point2d(10f, 20f));
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("ThreatPosition");
        assertThat(node.get("data").get("position").get("x").floatValue()).isEqualTo(10f);
        assertThat(node.get("data").get("position").get("y").floatValue()).isEqualTo(20f);
    }

    @Test
    void threatPosition_deserialisesViaManualSwitch() throws Exception {
        String json = """
            {"type":"ThreatPosition","data":{"position":{"x":45.0,"y":120.0}}}
            """;
        JsonNode node = mapper.readTree(json);
        String type = node.get("type").asText();
        JsonNode data = node.get("data");

        ScoutingIntelPayload payload = switch (type) {
            case "ThreatPosition" -> mapper.treeToValue(data, ScoutingIntelPayload.ThreatPosition.class);
            case "PostureUpdate"  -> mapper.treeToValue(data, ScoutingIntelPayload.PostureUpdate.class);
            case "TimingAlert"    -> mapper.treeToValue(data, ScoutingIntelPayload.TimingAlert.class);
            case "ArmySize"       -> mapper.treeToValue(data, ScoutingIntelPayload.ArmySize.class);
            case "BuildOrder"     -> mapper.treeToValue(data, ScoutingIntelPayload.BuildOrder.class);
            default -> throw new IllegalArgumentException("Unknown: " + type);
        };

        assertThat(payload).isInstanceOf(ScoutingIntelPayload.ThreatPosition.class);
        var tp = (ScoutingIntelPayload.ThreatPosition) payload;
        assertThat(tp.position().x()).isEqualTo(45f);
        assertThat(tp.position().y()).isEqualTo(120f);
    }

    @Test
    void postureUpdate_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.PostureUpdate("AGGRESSIVE");
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("PostureUpdate");
        assertThat(node.get("data").get("posture").asText()).isEqualTo("AGGRESSIVE");
    }

    @Test
    void timingAlert_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.TimingAlert(true);
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("data").get("incoming").asBoolean()).isTrue();
    }

    @Test
    void armySize_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.ArmySize(12);
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("ArmySize");
        assertThat(node.get("data").get("count").asInt()).isEqualTo(12);
    }

    @Test
    void buildOrder_roundTrips() throws Exception {
        var payload = new ScoutingIntelPayload.BuildOrder("4-GATE");
        String json = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("BuildOrder");
        assertThat(node.get("data").get("detected").asText()).isEqualTo("4-GATE");
    }

    // ---- type() — each record returns the matching ScoutingIntelType ----

    @Test
    void threatPosition_type_returnsThreatPosition() {
        assertThat(new ScoutingIntelPayload.ThreatPosition(new Point2d(0, 0)).type())
            .isEqualTo(ScoutingIntelType.THREAT_POSITION);
    }

    @Test
    void postureUpdate_type_returnsPosture() {
        assertThat(new ScoutingIntelPayload.PostureUpdate("X").type())
            .isEqualTo(ScoutingIntelType.POSTURE);
    }

    @Test
    void timingAlert_type_returnsTimingAlert() {
        assertThat(new ScoutingIntelPayload.TimingAlert(false).type())
            .isEqualTo(ScoutingIntelType.TIMING_ALERT);
    }

    @Test
    void armySize_type_returnsArmySize() {
        assertThat(new ScoutingIntelPayload.ArmySize(0).type())
            .isEqualTo(ScoutingIntelType.ARMY_SIZE);
    }

    @Test
    void buildOrder_type_returnsBuildOrder() {
        assertThat(new ScoutingIntelPayload.BuildOrder("X").type())
            .isEqualTo(ScoutingIntelType.BUILD_ORDER);
    }
}
