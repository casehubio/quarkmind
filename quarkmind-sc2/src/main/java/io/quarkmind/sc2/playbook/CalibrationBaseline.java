package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.List;

public record CalibrationBaseline(
    String playbook,
    String race,
    String sc2Build,
    String recordedAt,
    int tickCount,
    List<CalibrationSnapshot> snapshots
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonCreator
    public CalibrationBaseline(
        @JsonProperty("playbook") String playbook,
        @JsonProperty("race") String race,
        @JsonProperty("sc2Build") String sc2Build,
        @JsonProperty("recordedAt") String recordedAt,
        @JsonProperty("tickCount") int tickCount,
        @JsonProperty("snapshots") List<CalibrationSnapshot> snapshots
    ) {
        this.playbook = playbook;
        this.race = race;
        this.sc2Build = sc2Build;
        this.recordedAt = recordedAt;
        this.tickCount = tickCount;
        this.snapshots = snapshots;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialise baseline", e);
        }
    }

    public static CalibrationBaseline fromJson(String json) {
        try {
            return MAPPER.readValue(json, CalibrationBaseline.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialise baseline", e);
        }
    }
}
