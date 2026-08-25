package io.quarkmind.chat.agent;

import io.casehub.connectors.chat.model.ChatChannelRef;
import io.casehub.connectors.chat.model.ReceivedMessage;
import io.casehub.connectors.chat.spi.MessageHistory;
import io.casehub.connectors.chat.spi.Messaging;
import io.casehub.connectors.chat.spi.Reactions;
import io.casehub.connectors.chat.spi.Threading;
import io.quarkmind.agency.chat.BotIdentityDetector;
import io.quarkmind.agency.intent.IntentQueue;
import io.quarkmind.agency.spi.WorldBridge;
import io.quarkmind.chat.protocol.ChatIntent;
import io.quarkmind.chat.protocol.ChatPerception;
import io.quarkmind.chat.protocol.WakeReason;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatWorldBridge implements WorldBridge<ChatPerception, ChatIntent> {

    private final MessageHistory messageHistory;
    private final List<String> watchedChannels;
    private final BotIdentityDetector identityDetector;
    private Messaging messaging;
    private Threading threading;
    private Reactions reactions;
    private Instant lastCheck = Instant.EPOCH;

    public ChatWorldBridge(MessageHistory messageHistory, List<String> watchedChannels,
                           BotIdentityDetector identityDetector) {
        this.messageHistory = messageHistory;
        this.watchedChannels = watchedChannels;
        this.identityDetector = identityDetector;
    }

    public void setMessaging(Messaging messaging) { this.messaging = messaging; }
    public void setThreading(Threading threading) { this.threading = threading; }
    public void setReactions(Reactions reactions) { this.reactions = reactions; }

    public List<String> watchedChannels()         {return watchedChannels;}


    @Override
    public void connect() {}

    @Override
    public void disconnect() {}

    public ChatPerception perceive(WakeReason reason) {
        Map<String, List<ReceivedMessage>> deltas = new LinkedHashMap<>();
        for (String channelId : watchedChannels) {
            var messages = messageHistory.messages(new ChatChannelRef(channelId), lastCheck);
            if (!messages.isEmpty()) {
                deltas.put(channelId, messages);
            }
        }
        lastCheck = Instant.now();
        return new ChatPerception(deltas, Map.of(), reason);
    }

    @Override
    public ChatPerception perceive() {
        return perceive(WakeReason.MESSAGE);
    }

    @Override
    public void dispatch(IntentQueue<ChatIntent> intents) {
        for (ChatIntent intent : intents.drainAll()) {
            switch (intent) {
                case ChatIntent.Send s ->
                        messaging.send(new ChatChannelRef(s.channelId()), s.content());
                case ChatIntent.Reply r ->
                        threading.reply(r.parent(), r.content());
                case ChatIntent.React r ->
                        reactions.add(r.message(), r.emoji());
            }
        }
    }
}
