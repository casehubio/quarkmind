package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.List;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record CalibrationBaseline(
        String playbook,
        String race,
        String sc2Build,
        String recordedAt,
        int tickCount,
        List<CalibrationSnapshot> snapshots,
        List<PatchPosition> mineralPatches,
        List<GeyserPosition> geyserPositions,
        float[] basePosition
) {
    private static final ObjectMapper MAPPER = new ObjectMapper()
                                                       .enable(SerializationFeature.INDENT_OUTPUT);

    public record PatchPosition(float x, float y, int amount, boolean rich) {
        public PatchPosition(float x, float y, int amount) {
            this(x, y, amount, false);
        }
    }

    public record GeyserPosition(float x, float y, int amount) {}

    @JsonCreator
    public CalibrationBaseline(
            @JsonProperty("playbook") String playbook,
            @JsonProperty("race") String race,
            @JsonProperty("sc2Build") String sc2Build,
            @JsonProperty("recordedAt") String recordedAt,
            @JsonProperty("tickCount") int tickCount,
            @JsonProperty("snapshots") List<CalibrationSnapshot> snapshots,
            @JsonProperty("mineralPatches") List<PatchPosition> mineralPatches,
            @JsonProperty("geyserPositions") List<GeyserPosition> geyserPositions,
            @JsonProperty("basePosition") float[] basePosition
                              ) {
        this.playbook        = playbook;
        this.race            = race;
        this.sc2Build        = sc2Build;
        this.recordedAt      = recordedAt;
        this.tickCount       = tickCount;
        this.snapshots       = snapshots;
        this.mineralPatches  = mineralPatches;
        this.geyserPositions = geyserPositions;
        this.basePosition    = basePosition;
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
