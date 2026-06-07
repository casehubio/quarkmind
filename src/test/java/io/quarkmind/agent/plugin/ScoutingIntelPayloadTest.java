package io.quarkmind.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkmind.domain.Point2d;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
