package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.List;

public record WorkerCadenceBaseline(
        String playbook,
        String race,
        String sc2Build,
        String recordedAt,
        int tickCount,
        List<WorkerCadenceSnapshot> cadence
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonCreator
    public WorkerCadenceBaseline(
            @JsonProperty("playbook") String playbook,
            @JsonProperty("race") String race,
            @JsonProperty("sc2Build") String sc2Build,
            @JsonProperty("recordedAt") String recordedAt,
            @JsonProperty("tickCount") int tickCount,
            @JsonProperty("cadence") List<WorkerCadenceSnapshot> cadence
    ) {
        this.playbook = playbook;
        this.race = race;
        this.sc2Build = sc2Build;
        this.recordedAt = recordedAt;
        this.tickCount = tickCount;
        this.cadence = cadence;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialise worker cadence baseline", e);
        }
    }

    public static WorkerCadenceBaseline fromJson(String json) {
        try {
            return MAPPER.readValue(json, WorkerCadenceBaseline.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialise worker cadence baseline", e);
        }
    }
}
