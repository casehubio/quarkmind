package io.quarkmind.sc2.playbook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.SC2Data;
import io.quarkmind.sc2.emulated.EmulatedGame;
import io.quarkmind.sc2.emulated.RaceModelFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SC2PlaybookRunner {

    private final SC2DeliveryHandler handler;
    private final EmulatedGame game;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    static final int TICKS_PER_MINUTE =
        (int) (60 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);

    public SC2PlaybookRunner(SC2DeliveryHandler handler, EmulatedGame game) {
        this.handler = handler;
        this.game = game;
    }

    public void execute(String resourcePath) {
        String yaml = loadResource(resourcePath);
        JsonNode root;
        try { root = YAML.readTree(yaml); }
        catch (Exception e) { throw new RuntimeException("Failed to parse playbook YAML", e); }

        Race race = resolveRace(root);
        game.setPlayerRaceModel(RaceModelFactory.forRace(race));
        game.reset();

        List<PlaybookStep> steps = parseSteps(root);
        int durationTicks = resolveDuration(root, steps);
        boolean[] fired = new boolean[steps.size()];

        for (int tick = 0; tick < durationTicks; tick++) {
            game.tick();

            int supplyUsed = (int) game.snapshot().supplyUsed();
            for (int i = 0; i < steps.size(); i++) {
                PlaybookStep step = steps.get(i);
                boolean canFire = step.shouldFire(tick, supplyUsed) && (!fired[i] || step.loop());
                if (canFire) {
                    Map<String, Object> data = new HashMap<>(step.params());
                    data.put("action", step.action());
                    var outcome = handler.execute(step.action() + "-" + tick, data, null);
                    if (step.supplyTrigger() > 0) {
                        if (outcome.success()) fired[i] = true;
                    } else {
                        fired[i] = true;
                    }
                }
            }
        }
    }

    record PlaybookStep(String action, Map<String, Object> params,
                        int fireTick, int supplyTrigger, boolean loop) {
        boolean shouldFire(int tick, int supplyUsed) {
            if (supplyTrigger > 0) return supplyUsed >= supplyTrigger;
            return loop ? tick >= fireTick : tick == fireTick;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PlaybookStep> parseSteps(JsonNode root) {
        List<PlaybookStep> steps = new ArrayList<>();
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) return steps;

        for (JsonNode stepNode : stepsNode) {
            String action = null;
            Map<String, Object> params = new HashMap<>();
            int fireTick = 0;
            int supplyTrigger = 0;
            boolean loop = false;

            var fields = stepNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                if ("at".equals(key)) {
                    String atVal = field.getValue().asText();
                    if (atVal.endsWith("supply")) {
                        supplyTrigger = Integer.parseInt(atVal.replace("supply", "").trim());
                    } else if (atVal.endsWith("ticks")) {
                        fireTick = Integer.parseInt(atVal.replace("ticks", "").trim());
                    } else {
                        fireTick = parseTimeTicks(atVal);
                    }
                } else if ("expect".equals(key)) {
                    params.put("expect", YAML.convertValue(field.getValue(), Map.class));
                } else if ("loop".equals(key)) {
                    loop = "continuous".equals(field.getValue().asText());
                } else if (action == null) {
                    action = key;
                    params.put(key, field.getValue().asText());
                }
            }
            if (action != null) steps.add(new PlaybookStep(action, params, fireTick, supplyTrigger, loop));
        }
        return steps;
    }

    static int parseTimeTicks(String timeStr) {
        timeStr = timeStr.trim();
        if (timeStr.endsWith("m")) {
            int minutes = Integer.parseInt(timeStr.replace("m", "").trim());
            return minutes * TICKS_PER_MINUTE;
        }
        if (timeStr.endsWith("s")) {
            int seconds = Integer.parseInt(timeStr.replace("s", "").trim());
            return (int) (seconds * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
        }
        return Integer.parseInt(timeStr);
    }

    private Race resolveRace(JsonNode root) {
        JsonNode meta = root.get("meta");
        if (meta != null && meta.has("race")) {
            try { return Race.valueOf(meta.get("race").asText().toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }
        if (meta != null && meta.has("labels") && meta.get("labels").isArray()) {
            for (JsonNode label : meta.get("labels")) {
                String upper = label.asText().toUpperCase();
                if ("PROTOSS".equals(upper) || "TERRAN".equals(upper) || "ZERG".equals(upper)) {
                    return Race.valueOf(upper);
                }
            }
        }
        return Race.PROTOSS;
    }

    private int resolveDuration(JsonNode root, List<PlaybookStep> steps) {
        JsonNode meta = root.get("meta");
        if (meta != null && meta.has("duration")) {
            String dur = meta.get("duration").asText();
            if (dur.endsWith("ticks")) return Integer.parseInt(dur.replace("ticks", "").trim());
            return parseTimeTicks(dur);
        }
        int maxTick = steps.stream().mapToInt(PlaybookStep::fireTick).max().orElse(0);
        return maxTick > 0 ? maxTick + TICKS_PER_MINUTE : 5 * TICKS_PER_MINUTE;
    }

    private String loadResource(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalArgumentException("Playbook not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load playbook: " + path, e);
        }
    }
}
