package io.quarkmind.sc2.real;

import SC2APIProtocol.Error.ActionResult;
import SC2APIProtocol.Raw;
import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.data.Abilities;
import com.github.ocraft.s2client.protocol.unit.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@org.junit.jupiter.api.Tag("sc2")
class MorphInvestigationTest {

    private static QuarkusSC2Transport createTransport() {
        QuarkusSC2Transport transport = new QuarkusSC2Transport();
        transport.sc2Port                = 8168;
        transport.mapName                = "TorchesAIE_v4";
        transport.difficultyStr          = "VERY_EASY";
        transport.aiRaceStr              = "RANDOM";
        transport.botRaceStr             = "TERRAN";
        transport.connectRetryCount      = 60;
        transport.connectRetryIntervalMs = 5000;
        return transport;
    }

    private static final int CC_TYPE_ID = 18;
    private static final int OC_TYPE_ID = 132;

    /**
     * Test 1: Morph an idle CC (no prior training command).
     * Expected: SC2 accepts the morph — ActionResult.Success.
     */
    @Test
    void morphWhenCCIdle_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 1: Idle CC morph", MorphStrategy.WAIT_IDLE);
    }

    /**
     * Test 2: Train SCV, then immediately morph on the same frame.
     */
    @Test
    void morphWhileCCBusy_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 2: Immediate morph (CC busy)", MorphStrategy.IMMEDIATE);
    }

    /**
     * Test 3: Train SCV, cancel on next frame, morph on same frame as cancel.
     */
    @Test
    void cancelThenMorphSameFrame_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 3: Cancel + morph same frame", MorphStrategy.CANCEL_SAME_FRAME);
    }

    /**
     * Test 4: Train SCV, cancel, wait 1 frame, morph.
     */
    @Test
    void cancelThenMorphNextFrame_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 4: Cancel + morph next frame", MorphStrategy.CANCEL_NEXT_FRAME);
    }

    /**
     * Test 5: Train SCV, morph with setQueueCommand(true).
     */
    @Test
    void morphWithQueueCommand_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 5: Morph with queueCommand=true", MorphStrategy.QUEUE_COMMAND);
    }

    /**
     * Test 6: Train SCV, cancel, wait 3 frames, morph.
     */
    @Test
    void cancelThenMorphAfterDelay_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        runProbe("Test 6: Cancel + 3 frame delay + morph", MorphStrategy.CANCEL_DELAY_3);
    }

    /**
     * Test 7: Morph using the ability ID reported by SC2 (416) instead of ocraft's 1516.
     * The SC2 action interface uses different ability IDs than the data/catalog interface.
     */
    @Test
    void morphUsingQueriedAbilityId_logsActionResult() throws Exception {
        assumeTrue(QuarkusSC2Transport.isSC2Available());
        QuarkusSC2Transport transport = createTransport();
        transport.connect();
        transport.createGame();
        transport.joinGame();

        List<String> log = new ArrayList<>();
        log.add("[MorphProbe] strategy: BUILD_BARRACKS_THEN_MORPH");

        try {
            long    ccTag           = -1;
            boolean barracksCreated = false;
            boolean morphSent       = false;
            int     morphFrame      = -1;

            for (int frame = 0; frame < 1500; frame++) {
                Sc2Api.ResponseObservation obs      = transport.requestObservation();
                Raw.ObservationRaw         raw      = obs.getObservation().getRawData();
                int                        minerals = obs.getObservation().getPlayerCommon().getMinerals();

                if (ccTag < 0) {
                    for (Raw.Unit u : raw.getUnitsList()) {
                        if (u.getAlliance() == Raw.Alliance.Self && u.getUnitType() == CC_TYPE_ID) {
                            ccTag = u.getTag();
                            log.add(String.format("[MorphProbe] frame %d: Found CC tag=%d at (%.1f, %.1f), minerals=%d",
                                                  frame, ccTag, u.getPos().getX(), u.getPos().getY(), minerals));
                            break;
                        }
                    }
                }

                // Create a Barracks via debug on frame 1 (tech prerequisite for OC morph)
                if (ccTag >= 0 && !barracksCreated && frame == 1) {
                    barracksCreated = true;
                    // Find CC position for placement offset
                    float ccX = 0, ccY = 0;
                    for (Raw.Unit u : raw.getUnitsList()) {
                        if (u.getTag() == ccTag) {
                            ccX = u.getPos().getX();
                            ccY = u.getPos().getY();
                            break;
                        }
                    }
                    SC2APIProtocol.Debug.DebugCreateUnit createBarracks = SC2APIProtocol.Debug.DebugCreateUnit.newBuilder()
                                                                                                              .setUnitType(21) // Barracks
                                                                                                              .setOwner(1)
                                                                                                              .setPos(SC2APIProtocol.Common.Point2D.newBuilder()
                                                                                                                                                   .setX(ccX - 8f).setY(ccY + 8f).build())
                                                                                                              .setQuantity(1)
                                                                                                              .build();
                    Sc2Api.RequestDebug debugReq = Sc2Api.RequestDebug.newBuilder()
                                                                      .addDebug(SC2APIProtocol.Debug.DebugCommand.newBuilder()
                                                                                                                 .setCreateUnit(createBarracks).build())
                                                                      .build();
                    transport.sendDebug(debugReq);
                    log.add(String.format("[MorphProbe] frame %d: DEBUG CREATE Barracks at (%.1f, %.1f)", frame, ccX - 8f, ccY + 8f));
                }

                if (ccTag < 0 || minerals < 150 || !barracksCreated) {
                    transport.requestStep();
                    continue;
                }

                if (morphSent) {
                    int since = frame - morphFrame;
                    if (since == 5 || since == 100 || since == 300 || since == 500 || since == 650) {
                        boolean ocFound = findUnit(raw, OC_TYPE_ID);
                        boolean ccFound = findUnit(raw, CC_TYPE_ID);
                        log.add(String.format("[MorphProbe] frame %d (+%d): OC=%s CC=%s minerals=%d",
                                              frame, since, ocFound, ccFound, minerals));
                        if (ocFound) {
                            log.add("[MorphProbe] SUCCESS — Orbital Command found!");
                            return;
                        }
                        if (since >= 650) {
                            log.add("[MorphProbe] FAILED — CC did not morph after 650 game loops");
                            return;
                        }
                    }
                    transport.requestStep();
                    continue;
                }

                // Query available abilities — should now include 1516 (with Barracks)
                SC2APIProtocol.Query.ResponseQuery qr             = transport.queryAbilities(ccTag);
                boolean                            morphAvailable = false;
                if (qr.getAbilitiesCount() > 0) {
                    Sc2Api.ResponseData            abilityData  = transport.requestAbilityData();
                    java.util.Map<Integer, String> abilityNames = new java.util.HashMap<>();
                    for (SC2APIProtocol.Data.AbilityData ad : abilityData.getAbilitiesList()) {
                        abilityNames.put(ad.getAbilityId(), ad.getLinkName() + " (" + ad.getFriendlyName() + ")");
                    }
                    log.add(String.format("[MorphProbe] frame %d: CC available abilities (with Barracks):", frame));
                    for (var a : qr.getAbilities(0).getAbilitiesList()) {
                        String name = abilityNames.getOrDefault(a.getAbilityId(), "UNKNOWN");
                        log.add(String.format("  id=%d name=%s", a.getAbilityId(), name));
                        if (a.getAbilityId() == 1516) {morphAvailable = true;}
                    }
                }

                if (!morphAvailable) {
                    log.add("[MorphProbe] ability 1516 still not available — waiting...");
                    transport.requestStep();
                    continue;
                }

                // Send morph with the correct ability ID 1516
                morphSent  = true;
                morphFrame = frame;
                ResolvedCommand morphCmd = new ResolvedCommand(
                        Tag.of(ccTag), Abilities.MORPH_ORBITAL_COMMAND, Optional.empty());
                List<ActionResult> results = transport.sendActionsWithResults(List.of(morphCmd));
                log.add(String.format("[MorphProbe] frame %d: MORPH_ORBITAL_COMMAND (1516) result=%s, minerals=%d",
                                      frame, results, minerals));
                transport.requestStep();
            }
        } catch (Exception e) {
            log.add("[MorphProbe] EXCEPTION: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            System.out.println("\n=== Test 7: Build Barracks then morph with ability 1516 ===");
            log.forEach(System.out::println);
            try {transport.quit();} catch (Exception ignored) {}
        }
    }


    private void runProbe(String label, MorphStrategy strategy) throws Exception {
        QuarkusSC2Transport transport = createTransport();
        transport.connect();
        transport.createGame();
        transport.joinGame();

        List<String> log = new ArrayList<>();
        log.add("[MorphProbe] strategy: " + strategy);

        try {
            driveLoop(transport, log, strategy);
        } finally {
            try {transport.quit();} catch (Exception ignored) {}
        }

        System.out.println("\n=== " + label + " ===");
        log.forEach(System.out::println);
    }

    private void driveLoop(QuarkusSC2Transport transport, List<String> log,
                           MorphStrategy strategy) throws InterruptedException {
        long    ccTag          = -1;
        boolean trainSent      = false;
        int     trainFrame     = -1;
        boolean cancelSent     = false;
        int     cancelFrame    = -1;
        boolean morphAttempted   = false;
        int     morphFrame       = -1;
        boolean abilitiesQueried = false;

        for (int frame = 0; frame < 500; frame++) {
            Sc2Api.ResponseObservation        obs      = transport.requestObservation();
            Raw.ObservationRaw raw      = obs.getObservation().getRawData();
            Sc2Api.PlayerCommon    common   = obs.getObservation().getPlayerCommon();
            int                               minerals = common.getMinerals();

            if (ccTag < 0) {
                for (Raw.Unit u : raw.getUnitsList()) {
                    if (u.getAlliance() == Raw.Alliance.Self
                        && u.getUnitType() == CC_TYPE_ID) {
                        ccTag = u.getTag();
                        log.add(String.format("[MorphProbe] frame %d: Found CC tag=%d, minerals=%d",
                                              frame, ccTag, minerals));
                        break;
                    }
                }
            }

            if (ccTag < 0 || minerals < 150) {
                transport.requestStep();
                continue;
            }

            if (morphAttempted) {
                int since = frame - morphFrame;
                if (since == 5 || since == 50) {
                    boolean ocFound = findUnit(raw, OC_TYPE_ID);
                    boolean ccFound = findUnit(raw, CC_TYPE_ID);
                    log.add(String.format(
                            "[MorphProbe] frame %d (+%d): OC=%s CC=%s minerals=%d",
                            frame, since, ocFound, ccFound, minerals));
                }
                if (since >= 50) {
                    boolean ocFound = findUnit(raw, OC_TYPE_ID);
                    log.add(ocFound ? "[MorphProbe] SUCCESS — Orbital Command found!"
                                    : "[MorphProbe] FAILED — CC did not morph");
                    return;
                }
                transport.requestStep();
                continue;
            }

            // Query and log available abilities on first eligible frame
            if (!abilitiesQueried) {
                abilitiesQueried = true;
                SC2APIProtocol.Query.ResponseQuery qr = transport.queryAbilities(ccTag);
                if (qr.getAbilitiesCount() > 0) {
                    var avail = qr.getAbilities(0).getAbilitiesList();
                    log.add(String.format("[MorphProbe] frame %d: CC available abilities (%d):", frame, avail.size()));
                    for (var a : avail) {
                        log.add(String.format("  ability_id=%d requires_point=%s", a.getAbilityId(), a.getRequiresPoint()));
                    }
                }
            }

            // For strategies that need a busy CC, train an SCV first
            if (!trainSent && strategy != MorphStrategy.WAIT_IDLE) {
                trainSent  = true;
                trainFrame = frame;
                Tag tag = Tag.of(ccTag);
                ResolvedCommand trainCmd = new ResolvedCommand(
                        tag, Abilities.TRAIN_SCV, Optional.empty());
                List<ActionResult> results = transport.sendActionsWithResults(List.of(trainCmd));
                log.add(String.format("[MorphProbe] frame %d: TRAIN_SCV result=%s", frame, results));
                if (strategy == MorphStrategy.IMMEDIATE) {
                    doMorph(transport, log, frame, ccTag, false);
                    morphAttempted = true;
                    morphFrame     = frame;
                }
                transport.requestStep();
                continue;
            }

            switch (strategy) {
                case WAIT_IDLE -> {
                    doMorph(transport, log, frame, ccTag, false);
                    morphAttempted = true;
                    morphFrame     = frame;
                }
                case CANCEL_SAME_FRAME -> {
                    if (frame >= trainFrame + 1) {
                        doCancel(transport, log, frame, ccTag);
                        doMorph(transport, log, frame, ccTag, false);
                        morphAttempted = true;
                        morphFrame     = frame;
                    }
                }
                case CANCEL_NEXT_FRAME -> {
                    if (!cancelSent && frame >= trainFrame + 1) {
                        doCancel(transport, log, frame, ccTag);
                        cancelSent  = true;
                        cancelFrame = frame;
                    } else if (cancelSent && frame == cancelFrame + 1) {
                        doMorph(transport, log, frame, ccTag, false);
                        morphAttempted = true;
                        morphFrame     = frame;
                    }
                }
                case QUEUE_COMMAND -> {
                    if (frame >= trainFrame + 1) {
                        doMorph(transport, log, frame, ccTag, true);
                        morphAttempted = true;
                        morphFrame     = frame;
                    }
                }
                case CANCEL_DELAY_3 -> {
                    if (!cancelSent && frame >= trainFrame + 1) {
                        doCancel(transport, log, frame, ccTag);
                        cancelSent  = true;
                        cancelFrame = frame;
                    } else if (cancelSent && frame == cancelFrame + 3) {
                        doMorph(transport, log, frame, ccTag, false);
                        morphAttempted = true;
                        morphFrame     = frame;
                    }
                }
                default -> {}
            }

            transport.requestStep();
        }
        log.add("[MorphProbe] Max frames reached without resolution");
    }

    private void doMorph(QuarkusSC2Transport transport, List<String> log,
                         int frame, long ccTag, boolean queueCommand) throws InterruptedException {
        ResolvedCommand morphCmd = new ResolvedCommand(
                Tag.of(ccTag), Abilities.MORPH_ORBITAL_COMMAND, Optional.empty());
        log.add(String.format(
                "[MorphProbe] frame %d: MORPH_ORBITAL_COMMAND ability=%d tag=%d queueCmd=%s",
                frame, Abilities.MORPH_ORBITAL_COMMAND.getAbilityId(), ccTag, queueCommand));
        List<ActionResult> results = transport.sendActionsWithResults(List.of(morphCmd), queueCommand);
        log.add(String.format("[MorphProbe] frame %d: morph result=%s", frame, results));
    }

    private void doCancel(QuarkusSC2Transport transport, List<String> log,
                          int frame, long ccTag) throws InterruptedException {
        ResolvedCommand cancelCmd = new ResolvedCommand(
                Tag.of(ccTag), Abilities.CANCEL_LAST, Optional.empty());
        List<ActionResult> results = transport.sendActionsWithResults(List.of(cancelCmd));
        log.add(String.format("[MorphProbe] frame %d: CANCEL_LAST result=%s", frame, results));
    }

    private boolean findUnit(Raw.ObservationRaw raw, int unitTypeId) {
        return raw.getUnitsList().stream()
                  .anyMatch(u -> u.getAlliance() == Raw.Alliance.Self
                                 && u.getUnitType() == unitTypeId);
    }

    enum MorphStrategy {
        IMMEDIATE,
        CANCEL_SAME_FRAME,
        CANCEL_NEXT_FRAME,
        QUEUE_COMMAND,
        WAIT_IDLE,
        CANCEL_DELAY_3
    }
}
