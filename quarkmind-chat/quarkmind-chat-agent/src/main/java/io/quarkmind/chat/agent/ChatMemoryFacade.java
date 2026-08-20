package io.quarkmind.chat.agent;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.experience.ExperienceQuery;
import io.casehub.neocortex.memory.personality.PersonalityWeightedRetrieval;
import io.casehub.neocortex.memory.personality.PersonalityWeights;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public class ChatMemoryFacade {

    private static final int DEFAULT_MAX_MEMORIES = 15;

    private final CaseMemoryStore memoryStore;
    private final GraphCaseMemoryStore graphStore;
    private final boolean graphAvailable;
    private int maxMemories = DEFAULT_MAX_MEMORIES;

    public ChatMemoryFacade(CaseMemoryStore memoryStore, GraphCaseMemoryStore graphStore,
                            boolean graphAvailable) {
        this.memoryStore = memoryStore;
        this.graphStore = graphStore;
        this.graphAvailable = graphAvailable;
    }

    public List<Memory> recall(String agentId, String tenantId,
                               String conversationContext, Set<String> participantIds,
                               PersonalityWeights weights, Instant now) {
        var episodic = memoryStore.query(
                ExperienceQuery.search(agentId, tenantId, conversationContext));

        List<Memory> relationship = List.of();
        if (graphAvailable && !participantIds.isEmpty()) {
            relationship = participantIds.stream()
                    .flatMap(pid -> graphStore.graphQuery(
                            GraphMemoryQuery.forEntity(pid,
                                    ExperienceEvents.DOMAIN, tenantId,
                                    "what do I know about this person?")).stream())
                    .toList();
        }

        var merged = Stream.concat(episodic.stream(), relationship.stream()).toList();
        var ranked = PersonalityWeightedRetrieval.reweight(merged, weights, now);
        return ranked.size() <= maxMemories ? ranked : ranked.subList(0, maxMemories);
    }

    public String ingest(String agentId, String tenantId,
                         String observationText, Map<String, String> sourceRefs,
                         Set<String> participantIds) {
        var attrs = new HashMap<>(sourceRefs);
        for (String pid : participantIds) {
            attrs.put("participant." + pid, pid);
        }
        var input = new MemoryInput(agentId, ExperienceEvents.DOMAIN, tenantId,
                null, observationText, attrs, null);
        return memoryStore.store(input);
    }

    public void scoreImportance(String memoryId, String tenantId, double importance) {
        memoryStore.updateImportance(memoryId, tenantId, importance);
    }

    public void setMaxMemories(int max) { this.maxMemories = max; }
}
