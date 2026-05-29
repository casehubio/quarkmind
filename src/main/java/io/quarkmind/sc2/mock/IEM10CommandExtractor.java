package io.quarkmind.sc2.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkmind.domain.UnitType;
import io.quarkmind.sc2.SelectionState;
import io.quarkmind.sc2.intent.TimedIntent;
import io.quarkmind.sc2.intent.TrainIntent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Note: tag format "j-index-recycle" mirrors IEM10JsonSimulatedGame.makeTag (package-private)
// Do NOT use Sc2ReplayShared.makeTag here — that produces "r-" prefix for .SC2Replay binary parser

/**
 * Extracts training intents from IEM10 JSON gameEvents for any player.
 *
 * Uses IEM10 2016 build (~39948) abilLink constants — different from AI Arena
 * 2023+ build (~67188). Both abilLink numbers and abilCmdIndex values changed
 * between patches. See IEM10AbilityDiscoveryTest for derivation evidence.
 *
 * Selection tracking delegates to SelectionState. The first element of the current
 * selection when a Cmd fires is the building tag for the resulting TrainIntent.
 * Building tags are "j-index-recycle" format, matching IEM10JsonSimulatedGame tracker event tags.
 */
public class IEM10CommandExtractor {

    // IEM10 2016 build ~39948 — DO NOT use for AI Arena replays
    private static final int NEXUS_2016          = 167;
    private static final int GATEWAY_2016        = 164;
    private static final int ROBOTICS_2016       = 166;
    private static final int STARGATE_2016       = 165;
    private static final int LARVA_2016          = 185;
    private static final int HATCHERY_2016       = 235;
    private static final int COMMAND_CENTER_2016 = 147;
    private static final int BARRACKS_2016       = 151;

    private IEM10CommandExtractor() {}

    /** Extracts training intents for the watched (Protoss) player. */
    public static List<TimedIntent> extract(IEM10JsonSimulatedGame game) {
        return extract(game, game.watchedUserId());
    }

    /**
     * Extracts training intents for any player identified by their gameEvents userId.
     * The userId comes from ToonPlayerDescMap.userID — NOT playerID - 1.
     */
    public static List<TimedIntent> extract(IEM10JsonSimulatedGame game, int userId) {
        SelectionState currentSelection = new SelectionState();
        List<TimedIntent> intents       = new ArrayList<>();

        for (JsonNode event : game.gameEvents()) {
            String evtType = event.path("evtTypeName").asText();
            int    uid     = event.path("userid").path("userId").asInt(-1);
            if (uid != userId) continue;

            if ("SelectionDelta".equals(evtType)) {
                applySelectionDelta(event, currentSelection);
            } else if ("Cmd".equals(evtType)) {
                if (!isTrainingCommand(event)) continue;
                if (currentSelection.isEmpty()) continue;
                JsonNode abil = event.path("abil");
                if (abil.isMissingNode()) continue;
                int  abilLink     = abil.path("abilLink").asInt(-1);
                int  abilCmdIndex = abil.path("abilCmdIndex").asInt(0);
                long loop         = event.path("loop").asLong();
                if (abilLink < 0) continue;

                UnitType unitType = dispatch(abilLink, abilCmdIndex);
                if (unitType == null) continue;

                String buildingTag = currentSelection.first();
                intents.add(new TimedIntent(loop, new TrainIntent(buildingTag, unitType)));
            }
        }

        return Collections.unmodifiableList(intents);
    }

    static void applySelectionDelta(JsonNode event, SelectionState selection) {
        JsonNode delta      = event.path("delta");
        JsonNode removeMask = delta.path("removeMask");

        if (removeMask.has("Mask")) {
            int mask = removeMask.get("Mask").asInt();
            selection.removeIf(i -> (mask & (1 << i)) != 0);
        } else if (removeMask.has("SweepToEnd")) {
            int from = removeMask.get("SweepToEnd").asInt();
            selection.truncateTo(from);
        } else if (removeMask.has("OneIndice")) {
            int idx = removeMask.get("OneIndice").asInt();
            selection.removeAt(idx);
        }
        // "None" or absent removeMask → carry forward (no removal)

        JsonNode addTags = delta.path("addUnitTags");
        for (JsonNode tagNode : addTags) {
            long packed  = tagNode.asLong();
            int  index   = (int) (packed >> 18);
            int  recycle = (int) (packed & 0x3FFFF);
            selection.addTag("j-" + index + "-" + recycle);
        }
    }

    /** Training commands have data: {None: null} — no target point or target unit. */
    private static boolean isTrainingCommand(JsonNode event) {
        JsonNode data = event.path("data");
        if (data.isMissingNode()) return true;
        return data.has("None") && data.size() == 1;
    }

    // Units not mapped: Zealot and WarpPrism were not resolved in the narrow-window
    // correlation (IEM10AbilityDiscoveryTest) — signal too weak vs Probe noise.
    // Terran Factory/Starport units beyond SCV/Marine similarly unresolved.
    // See issue #160 for follow-up.
    private static UnitType dispatch(int abilLink, int abilCmdIndex) {
        return switch (abilLink) {
            case NEXUS_2016    -> UnitType.PROBE;
            case GATEWAY_2016  -> switch (abilCmdIndex) {
                case 1  -> UnitType.STALKER;
                case 6  -> UnitType.ADEPT;
                default -> null;
            };
            case ROBOTICS_2016 -> switch (abilCmdIndex) {
                case 1  -> UnitType.OBSERVER;
                case 3  -> UnitType.IMMORTAL;
                case 18 -> UnitType.DISRUPTOR;
                default -> null;
            };
            case STARGATE_2016 -> switch (abilCmdIndex) {
                case 0  -> UnitType.PHOENIX;
                case 9  -> UnitType.TEMPEST;
                default -> null;
            };
            case LARVA_2016    -> switch (abilCmdIndex) {
                case 0  -> UnitType.DRONE;
                case 1  -> UnitType.ZERGLING;
                case 9  -> UnitType.ROACH;
                default -> null;
            };
            case HATCHERY_2016       -> abilCmdIndex == 0 ? UnitType.QUEEN  : null;
            case COMMAND_CENTER_2016 -> abilCmdIndex == 0 ? UnitType.SCV    : null;
            case BARRACKS_2016       -> abilCmdIndex == 0 ? UnitType.MARINE : null;
            default -> null;
        };
    }
}
