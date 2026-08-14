package io.quarkmind.agent;

import io.quarkmind.sc2.GameStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdvisoryInvocationCounter {

    private final ConcurrentHashMap<String, Long> invokedAdvisors = new ConcurrentHashMap<>();

    public void record(String advisorId, long gameFrame) {
        invokedAdvisors.putIfAbsent(advisorId, gameFrame);
    }

    public OptionalLong firstFrame(String advisorId) {
        Long frame = invokedAdvisors.get(advisorId);
        return frame != null ? OptionalLong.of(frame) : OptionalLong.empty();
    }

    public Set<String> snapshot() {
        return Set.copyOf(invokedAdvisors.keySet());
    }

    void onGameStarted(@Observes GameStarted event) {
        invokedAdvisors.clear();
    }
}
