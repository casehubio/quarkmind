package io.quarkmind.chat.agent;

import io.casehub.eidos.api.DispositionSignalStore;
import io.casehub.eidos.api.DispositionValue;
import io.quarkmind.agency.llm.LlmPriority;
import io.quarkmind.agency.llm.LlmRequest;
import io.quarkmind.agency.llm.LlmRequestQueue;
import io.quarkmind.agency.personality.ReflectionDispositionActivator;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LlmReflectionDispositionActivator implements ReflectionDispositionActivator {

    private static final Logger LOG = Logger.getLogger(LlmReflectionDispositionActivator.class);

    private final LlmRequestQueue llmQueue;
    private final DispositionSignalStore signalStore;
    private volatile List<DispositionValue> dispositionProfile;

    public LlmReflectionDispositionActivator(LlmRequestQueue llmQueue,
                                             DispositionSignalStore signalStore,
                                             List<DispositionValue> initialProfile) {
        this.llmQueue = llmQueue;
        this.signalStore = signalStore;
        this.dispositionProfile = List.copyOf(initialProfile);
    }

    public void updateProfile(List<DispositionValue> newProfile) {
        this.dispositionProfile = List.copyOf(newProfile);
    }

    @Override
    public void onReflection(String agentId, String tenantId, String insight) {
        var profile = this.dispositionProfile;
        if (profile.isEmpty()) return;

        String terms = profile.stream()
                .map(DispositionValue::term)
                .collect(Collectors.joining(", "));

        String prompt = "Given this reflection insight and these personality function terms, " +
                "which term does this reflection most strongly activate? " +
                "Respond with a single function term from the list, or \"none\" if no term applies.\n\n" +
                "Reflection: " + insight + "\n\n" +
                "Function terms: " + terms;

        llmQueue.submit(new LlmRequest(prompt, LlmPriority.LOW, Map.of(), response -> {
            try {
                String term = response.trim();
                if ("none".equalsIgnoreCase(term)) return;
                String matched = profile.stream()
                        .map(DispositionValue::term)
                        .filter(t -> t.equalsIgnoreCase(term))
                        .findFirst().orElse(null);
                if (matched != null) {
                    signalStore.recordActivation(agentId, tenantId, matched);
                }
            } catch (Exception e) {
                LOG.debug("Disposition activation classification failed", e);
            }
        }));
    }
}
