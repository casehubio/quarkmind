package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.s2prot.Event;
import hu.scelightapi.sc2.rep.model.trackerevents.IBaseUnitEvent;
import hu.scelightapi.sc2.rep.model.trackerevents.ITrackerEvents;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

class UnitPositionsDiagnosticTest {

    @Test
    void crossReferenceUnitBornAndPositions() {
        Path replayFile = Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");
        Replay replay = RepParserEngine.parseReplay(replayFile, EnumSet.of(RepContent.TRACKER_EVENTS));
        var events = replay.trackerEvents.getEvents();

        Map<Integer, String> indexToTag = new HashMap<>();
        Map<Integer, String> indexToName = new HashMap<>();
        Map<Integer, String> indexToBornPos = new HashMap<>();
        Map<Integer, Integer> indexToPlayer = new HashMap<>();

        int posEventCount = 0;
        for (var rawEvent : events) {
            Event event = (Event) rawEvent;

            if (event.getId() == ITrackerEvents.ID_UNIT_BORN) {
                IBaseUnitEvent ube = (IBaseUnitEvent) event;
                int tagIndex = ube.getUnitTagIndex();
                int tagRecycle = ube.getUnitTagRecycle();
                String tag = "r-" + tagIndex + "-" + tagRecycle;
                String name = ube.getUnitTypeName().toString();
                Integer ctrlId = ube.getControlPlayerId();
                indexToTag.put(tagIndex, tag);
                indexToName.put(tagIndex, name);
                indexToBornPos.put(tagIndex, ube.getXCoord() + "," + ube.getYCoord());
                if (ctrlId != null) indexToPlayer.put(tagIndex, ctrlId);
            }

            if (event.getId() == ITrackerEvents.ID_UNIT_POSITIONS) {
                posEventCount++;
                if (posEventCount <= 5) {
                    Integer firstIndex = event.get("firstUnitIndex");
                    Integer[] items = event.get("items");
                    System.out.println("=== UnitPositions #" + posEventCount + " loop=" + event.getLoop()
                        + " firstIndex=" + firstIndex + " items.length=" + items.length + " ===");

                    int runningIndex = firstIndex;
                    for (int i = 0; i < items.length; i += 3) {
                        int delta = items[i];
                        int x = items[i + 1];
                        int y = items[i + 2];
                        runningIndex += delta;

                        String tag = indexToTag.getOrDefault(runningIndex, "???");
                        String name = indexToName.getOrDefault(runningIndex, "???");
                        String bornPos = indexToBornPos.getOrDefault(runningIndex, "???");
                        Integer player = indexToPlayer.get(runningIndex);

                        System.out.printf("  tagIndex=%d tag=%s name=%-20s player=%d bornPos=%-12s posEvent=(%d, %d)%n",
                                runningIndex, tag, name, player != null ? player : -1, bornPos, x, y);
                    }
                }
            }

            if (event.getId() == ITrackerEvents.ID_UNIT_DIED) {
                Integer tagIndex = event.get("unitTagIndex");
                if (tagIndex != null) {
                    indexToTag.remove(tagIndex);
                    indexToName.remove(tagIndex);
                    indexToBornPos.remove(tagIndex);
                    indexToPlayer.remove(tagIndex);
                }
            }
        }

        System.out.println("\nTotal UnitPositions events: " + posEventCount);
    }
}
