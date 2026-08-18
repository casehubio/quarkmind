package io.quarkmind.chat.protocol;

import io.casehub.connectors.chat.model.PresenceStatus;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.quarkmind.agency.spi.WorldPerception;

import java.util.List;
import java.util.Map;

public record ChatPerception(
        Map<String, List<ReceivedMessage>> channelDeltas,
        Map<String, PresenceStatus> presenceChanges,
        WakeReason reason
) implements WorldPerception {

    public ChatPerception {
        channelDeltas = channelDeltas != null ? Map.copyOf(channelDeltas) : Map.of();
        presenceChanges = presenceChanges != null ? Map.copyOf(presenceChanges) : Map.of();
    }

    public boolean hasActivity() {
        return !channelDeltas.isEmpty() && channelDeltas.values().stream().anyMatch(l -> !l.isEmpty());
    }

    public int totalMessageCount() {
        return channelDeltas.values().stream().mapToInt(List::size).sum();
    }
}
