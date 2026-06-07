package io.quarkmind.plugin;

import io.casehub.coordination.PropagationContext;
import io.casehub.core.CaseFile;
import io.casehub.persistence.memory.InMemoryCaseFileRepository;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import io.quarkmind.agent.QuarkMindCaseFile;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.agent.plugin.ScoutingIntelPayload;
import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.Unit;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsTacticsTaskTest {

    // ---- Helpers ----

    private static Unit unit(String tag, UnitType type, Point2d pos) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, 0, 0);
    }

    private static Unit unit(String tag, UnitType type, Point2d pos, int cooldown) {
        return new Unit(tag, type, pos, 80, 80, 80, 80, cooldown, 0);
    }

    private static Unit enemy(Point2d pos) {
        return new Unit("e-0", UnitType.ZEALOT, pos, 100, 100, 50, 50, 0, 0);
    }

    // ---- computeInRangeTags: per-unit range ----

    @Test
    void stalkerAt4_5_isInRange() {
        // Stalker range = 5.0; distance 4.5 → in range
        Unit s = unit("s-0", UnitType.STALKER, new Point2d(10, 10));
        Unit e = enemy(new Point2d(14.5f, 10)); // distance exactly 4.5
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(s), List.of(e));
        assertThat(result).contains("s-0");
    }

    @Test
    void stalkerAt5_5_isNotInRange() {
        // Stalker range = 5.0; distance 5.5 → out of range
        Unit s = unit("s-0", UnitType.STALKER, new Point2d(10, 10));
        Unit e = enemy(new Point2d(15.5f, 10)); // distance exactly 5.5
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(s), List.of(e));
        assertThat(result).doesNotContain("s-0");
    }

    @Test
    void zealotAt0_4_isInRange() {
        // Zealot range = 0.5; distance 0.4 → in range
        Unit z = unit("z-0", UnitType.ZEALOT, new Point2d(10, 10));
        Unit e = enemy(new Point2d(10.4f, 10));
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(z), List.of(e));
        assertThat(result).contains("z-0");
    }

    @Test
    void zealotAt0_6_isNotInRange() {
        // Zealot range = 0.5; distance 0.6 → out of range
        Unit z = unit("z-0", UnitType.ZEALOT, new Point2d(10, 10));
        Unit e = enemy(new Point2d(10.6f, 10));
        Set<String> result = DroolsTacticsTask.computeInRangeTags(List.of(z), List.of(e));
        assertThat(result).doesNotContain("z-0");
    }

    // ---- computeOnCooldownTags ----

    @Test
    void computeOnCooldownTags_includesOnlyUnitsWithCooldown() {
        Unit ready  = unit("s-ready",  UnitType.STALKER, new Point2d(10, 10), 0);
        Unit kiting = unit("s-kiting", UnitType.STALKER, new Point2d(10, 10), 3);
        Set<String> result = DroolsTacticsTask.computeOnCooldownTags(List.of(ready, kiting));
        assertThat(result).containsOnly("s-kiting");
    }

    @Test
    void computeOnCooldownTags_allReady_returnsEmpty() {
        Unit r0 = unit("s-0", UnitType.STALKER, new Point2d(10,10), 0);
        Unit r1 = unit("s-1", UnitType.STALKER, new Point2d(10,10), 0);
        assertThat(DroolsTacticsTask.computeOnCooldownTags(List.of(r0, r1))).isEmpty();
    }

    // ---- computeBlinkReadyTags ----

    @Test
    void computeBlinkReadyTagsReturnsStalkerWithCooldownZero() {
        List<Unit> army = List.of(
            new Unit("s-0", UnitType.STALKER, new Point2d(0,0), 80, 80, 80, 80, 0, 0),  // blink ready
            new Unit("s-1", UnitType.STALKER, new Point2d(0,0), 80, 80, 80, 80, 0, 5),  // on blink cooldown
            new Unit("z-0", UnitType.ZEALOT,  new Point2d(0,0), 100, 100, 50, 50, 0, 0) // not a Stalker
        );
        Set<String> result = DroolsTacticsTask.computeBlinkReadyTags(army);
        assertThat(result).containsExactly("s-0");
    }

    // ---- computeShieldsLowTags ----

    @Test
    void computeShieldsLowTagsReturnsBelowTwentyFivePercent() {
        List<Unit> army = List.of(
            new Unit("s-0", UnitType.STALKER, new Point2d(0,0), 80, 80, 19, 80, 0, 0), // 19 < 20 (25% of 80)
            new Unit("s-1", UnitType.STALKER, new Point2d(0,0), 80, 80, 20, 80, 0, 0), // exactly 25%, NOT low
            new Unit("s-2", UnitType.STALKER, new Point2d(0,0), 80, 80,  0, 80, 0, 0)  // 0 shields — low
        );
        Set<String> result = DroolsTacticsTask.computeShieldsLowTags(army);
        assertThat(result).containsExactlyInAnyOrder("s-0", "s-2");
    }

    // ---- TacticsIntelCache merge (new) ----

    @Test
    void merge_threatPosition_updatesCachePosition() {
        TacticsIntelCache prev = TacticsIntelCache.empty();
        var payload = new ScoutingIntelPayload.ThreatPosition(new Point2d(50f, 60f));
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.threatPosition()).isEqualTo(new Point2d(50f, 60f));
        assertThat(result.posture()).isNull();
        assertThat(result.timingAlert()).isNull();
    }

    @Test
    void merge_postureUpdate_preservesThreatPosition() {
        var prev = new TacticsIntelCache(new Point2d(10f, 10f), null, null);
        var payload = new ScoutingIntelPayload.PostureUpdate("AGGRESSIVE");
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.posture()).isEqualTo("AGGRESSIVE");
        assertThat(result.threatPosition()).isEqualTo(new Point2d(10f, 10f));
    }

    @Test
    void merge_timingAlert_setsFlag() {
        TacticsIntelCache prev = TacticsIntelCache.empty();
        var payload = new ScoutingIntelPayload.TimingAlert(true);
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, payload);
        assertThat(result.timingAlert()).isTrue();
    }

    @Test
    void merge_armySize_returnsUnchangedCache() {
        var prev = new TacticsIntelCache(new Point2d(5f, 5f), "DEFENSIVE", false);
        TacticsIntelCache result = DroolsTacticsTask.merge(prev, new ScoutingIntelPayload.ArmySize(42));
        assertThat(result).isEqualTo(prev);
    }

    @Test
    void canActivate_emptyCache_returnsFalse() {
        DroolsTacticsTask task = new DroolsTacticsTask(null, null);
        CaseFile caseFile = caseFileWith(QuarkMindCaseFile.READY, "present",
                                         QuarkMindCaseFile.STRATEGY, "present");
        assertThat(task.canActivate(caseFile)).isFalse();
    }

    @Test
    void canActivate_cacheHasThreatPosition_returnsTrue() throws Exception {
        DroolsTacticsTask task = new DroolsTacticsTask(null, null);
        task.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        task.onMessage(makeThreatPositionEvent(new Point2d(10f, 20f)));
        CaseFile caseFile = caseFileWith(QuarkMindCaseFile.READY, "present",
                                         QuarkMindCaseFile.STRATEGY, "present");
        assertThat(task.canActivate(caseFile)).isTrue();
    }

    @Test
    void onMessage_updatesIntelCache() throws Exception {
        DroolsTacticsTask task = new DroolsTacticsTask(null, null);
        task.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(task.intelCache.get().threatPosition()).isNull();
        task.onMessage(makeThreatPositionEvent(new Point2d(30f, 40f)));
        assertThat(task.intelCache.get().threatPosition()).isEqualTo(new Point2d(30f, 40f));
    }

    @Test
    void onMessage_unknownType_logsAndDoesNotThrow() throws Exception {
        DroolsTacticsTask task = new DroolsTacticsTask(null, null);
        task.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String badJson = "{\"type\":\"UNKNOWN_TYPE\",\"data\":{}}";
        var event = new MessageReceivedEvent(
            ScoutingIntelBroker.CHANNEL_NAME, UUID.randomUUID(),
            MessageType.STATUS, "scouting.drools-cep", null, badJson);
        task.onMessage(event);
        assertThat(task.intelCache.get().threatPosition()).isNull();
    }

    // ---- helpers ----

    private static CaseFile caseFileWith(String key1, Object val1, String key2, Object val2) {
        var cf = new InMemoryCaseFileRepository()
            .create("starcraft-game", Map.of(), PropagationContext.createRoot());
        cf.put(key1, val1);
        cf.put(key2, val2);
        return cf;
    }

    private static MessageReceivedEvent makeThreatPositionEvent(Point2d pos) throws Exception {
        var payload = new ScoutingIntelPayload.ThreatPosition(pos);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String content = mapper.writeValueAsString(
            java.util.Map.of("type", payload.getClass().getSimpleName(), "data", payload));
        return new MessageReceivedEvent(
            ScoutingIntelBroker.CHANNEL_NAME, UUID.randomUUID(),
            MessageType.STATUS, "scouting.drools-cep", null, content);
    }

}
