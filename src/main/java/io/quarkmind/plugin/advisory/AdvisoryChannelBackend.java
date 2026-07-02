package io.quarkmind.plugin.advisory;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.gateway.ChannelRef;
import io.casehub.qhorus.api.gateway.HumanObserverChannelBackend;
import io.casehub.qhorus.api.gateway.OutboundMessage;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HIL coaching observation backend for advisory messages.
 *
 * <p>Implements {@link HumanObserverChannelBackend} — receives advisory messages from the
 * {@code quarkmind-advisory} Qhorus channel and stores the latest message for observation.
 * Future work will forward messages to a WebSocket endpoint at {@code /ws/advisory}.
 *
 * <p>Backend identity: {@code quarkmind-advisory-observer}
 * <p>Actor type: {@code HUMAN} (observer role — human coaching interface)
 *
 * <p>{@code post()} catches all exceptions internally per {@link HumanObserverChannelBackend}
 * contract — failure is non-fatal; the gateway logs and continues.
 *
 * <p>Refs #180
 */
@ApplicationScoped
public class AdvisoryChannelBackend implements HumanObserverChannelBackend {

    private static final Logger log = Logger.getLogger(AdvisoryChannelBackend.class);

    private volatile OutboundMessage latest;
    private final AtomicLong count = new AtomicLong();

    @Override
    public String backendId() {
        return "quarkmind-advisory-observer";
    }

    @Override
    public ActorType actorType() {
        return ActorType.HUMAN;
    }

    @Override
    public void open(ChannelRef channel, Map<String, String> metadata) {
        log.debugf("[ADVISORY-BACKEND] Channel opened: %s", channel.name());
    }

    /**
     * Receives an advisory message and stores it for observation.
     *
     * <p>Catches all exceptions internally per {@link HumanObserverChannelBackend} contract.
     * Future: forward to WebSocket session at {@code /ws/advisory}.
     */
    @Override
    public void post(ChannelRef channel, OutboundMessage message) {
        try {
            latest = message;
            count.incrementAndGet();
            log.debugf("[ADVISORY-BACKEND] Advisory received on channel=%s sender=%s type=%s",
                channel.name(), message.sender(), message.type());
        } catch (Exception e) {
            // HumanObserverChannelBackend contract: post() must catch all exceptions internally
            log.errorf(e, "[ADVISORY-BACKEND] Failed to process advisory message on channel=%s",
                channel.name());
        }
    }

    @Override
    public void close(ChannelRef channel) {
        log.debugf("[ADVISORY-BACKEND] Channel closed: %s", channel.name());
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
