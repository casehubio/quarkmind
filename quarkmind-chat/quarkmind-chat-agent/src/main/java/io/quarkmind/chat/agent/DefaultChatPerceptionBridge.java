package io.quarkmind.chat.agent;

import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatDeltaReport;
import io.quarkmind.agency.chat.ChatObservationRenderer;
import io.quarkmind.chat.protocol.ChatPerception;

import java.util.Set;

public class DefaultChatPerceptionBridge implements ChatPerceptionBridge {

    private final ChatObservationRenderer renderer;

    public DefaultChatPerceptionBridge(ChatObservationRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public ChatDeltaReport buildDelta(ChatPerception perception, BotIdentityDetector detector,
                                       Set<String> participatedThreadIds) {
        return ChatDeltaReport.build(perception.channelDeltas(), detector, participatedThreadIds);
    }

    @Override
    public String renderForLlm(ChatDeltaReport report) {
        return renderer.renderDelta(report);
    }
}
