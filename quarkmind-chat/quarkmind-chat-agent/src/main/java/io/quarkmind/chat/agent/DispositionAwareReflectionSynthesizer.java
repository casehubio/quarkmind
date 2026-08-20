package io.quarkmind.chat.agent;

import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.reflection.ReflectionEvent;
import io.casehub.neocortex.memory.reflection.ReflectionSynthesizer;
import io.quarkmind.agency.personality.ReflectionDispositionActivator;
import org.jboss.logging.Logger;

import java.util.List;

public class DispositionAwareReflectionSynthesizer implements ReflectionSynthesizer {

    private static final Logger LOG = Logger.getLogger(DispositionAwareReflectionSynthesizer.class);

    private final ReflectionSynthesizer delegate;
    private final ReflectionDispositionActivator activator;

    public DispositionAwareReflectionSynthesizer(ReflectionSynthesizer delegate,
                                                 ReflectionDispositionActivator activator) {
        this.delegate = delegate;
        this.activator = activator;
    }

    @Override
    public List<ReflectionEvent> synthesize(String agentId, String tenantId,
                                            List<Memory> sources, int targetLevel) {
        var events = delegate.synthesize(agentId, tenantId, sources, targetLevel);
        for (var event : events) {
            try {
                activator.onReflection(agentId, tenantId, event.insight());
            } catch (Exception e) {
                LOG.debug("Disposition activation failed for reflection", e);
            }
        }
        return events;
    }
}
