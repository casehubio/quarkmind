package io.quarkmind.plugin.commentary;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observer mode commentary backend for human consumption.
 *
 * <p>Implements {@link HumanObserverChannelBackend} — receives commentary messages from the
 * {@code quarkmind-commentary} Qhorus channel and stores the latest message for observation.
 * Future work will forward messages to a WebSocket endpoint at {@code /ws/commentary}.
 *
 * <p>Backend identity: {@code quarkmind-commentary-observer}
 * <p>Actor type: {@code HUMAN} (observer role — human watching the game)
 *
 * <p>{@code post()} catches all exceptions internally per {@link HumanObserverChannelBackend}
 * contract — failure is non-fatal; the gateway logs and continues.
 *
 * <p>Refs #181
 */
@ApplicationScoped
public class CommentaryChannelBackend implements HumanObserverChannelBackend {

    private static final Logger log = Logger.getLogger(CommentaryChannelBackend.class);

    private volatile OutboundMessage latest;
    private final AtomicLong count = new AtomicLong();

    @Override
    public String backendId() {
        return "quarkmind-commentary-observer";
    }

    @Override
    public ActorType actorType() {
        return ActorType.HUMAN;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        log.debugf("[COMMENTARY-BACKEND] Channel opened: %s", channel.name());
    }

    /**
     * Receives a commentary message and stores it for observation.
     *
     * <p>Catches all exceptions internally per {@link HumanObserverChannelBackend} contract.
     * Future: forward to WebSocket session at {@code /ws/commentary}.
     */
    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        try {
            latest = message;
            count.incrementAndGet();
            log.debugf("[COMMENTARY-BACKEND] Commentary received on channel=%s sender=%s type=%s",
                channel.name(), message.sender(), message.type());
        } catch (Exception e) {
            // HumanObserverChannelBackend contract: post() must catch all exceptions internally
            log.errorf(e, "[COMMENTARY-BACKEND] Failed to process commentary message on channel=%s",
                channel.name());
        }
    }

    @Override
    public void close(ChannelRef channel) {
        log.debugf("[COMMENTARY-BACKEND] Channel closed: %s", channel.name());
    }

    /** Returns the latest message received, or {@code null} if none. */
    public OutboundMessage latestMessage() {
        return latest;
    }

    /** Returns the total number of messages received since startup. */
    public long messageCount() {
        return count.get();
    }
}
