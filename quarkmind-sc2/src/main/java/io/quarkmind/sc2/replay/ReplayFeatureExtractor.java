package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

public class ReplayFeatureExtractor {

    private final StrippedReplayFeatureExtractor strippedExtractor = new StrippedReplayFeatureExtractor();
    private final TrackerEventFeatureExtractor trackerExtractor = new TrackerEventFeatureExtractor();

    public Map<String, Object> extract(Path replayPath) {
        Replay replay = RepParserEngine.parseReplay(replayPath,
            EnumSet.of(RepContent.GAME_EVENTS, RepContent.TRACKER_EVENTS, RepContent.DETAILS));
        if (replay == null) {
            throw new IllegalArgumentException("Cannot parse replay: " + replayPath);
        }

        if (replay.trackerEvents != null && replay.trackerEvents.getEvents().length > 0) {
            return trackerExtractor.extractWithCommands(replay);
        }

        return strippedExtractor.extract(replayPath);
    }
}
