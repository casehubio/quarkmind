package io.quarkmind.chat.agent;

import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.chat.ChatDeltaReport;
import io.quarkmind.chat.protocol.ChatPerception;

import java.util.Set;

public interface ChatPerceptionBridge {
    ChatDeltaReport buildDelta(ChatPerception perception, BotIdentityDetector detector,
                               Set<String> participatedThreadIds);
    String renderForLlm(ChatDeltaReport report);
}
