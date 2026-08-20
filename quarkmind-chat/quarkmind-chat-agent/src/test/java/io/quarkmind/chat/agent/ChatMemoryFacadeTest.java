package io.quarkmind.chat.agent;

import io.casehub.neocortex.memory.*;
import io.casehub.neocortex.memory.experience.ExperienceEvents;
import io.casehub.neocortex.memory.personality.PersonalityWeights;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChatMemoryFacadeTest {

    @Test
    void ingestStoresObservationWithExperienceDomain() {
        var store = new RecordingMemoryStore();
        var facade = new ChatMemoryFacade(store, store, false);

        var refs = Map.of("source.channelId", "ch-1", "source.firstMessageId", "m1",
                "source.lastMessageId", "m3");
        var participants = Set.of("user-123");

        String memoryId = facade.ingest("agent-1", "tenant-1",
                "Talked to Bob about ML", refs, participants);

        assertNotNull(memoryId);
        assertEquals(1, store.stored.size());
        var input = store.stored.get(0);
        assertEquals(ExperienceEvents.DOMAIN, input.domain());
        assertEquals("agent-1", input.entityId());
        assertNull(input.importance());
        assertEquals("ch-1", input.attributes().get("source.channelId"));
        assertTrue(input.attributes().containsKey("participant.user-123"));
    }

    @Test
    void scoreImportanceDelegatesToUpdateImportance() {
        var store = new RecordingMemoryStore();
        var facade = new ChatMemoryFacade(store, store, false);

        facade.scoreImportance("mem-1", "tenant-1", 0.75);

        assertEquals("mem-1", store.lastUpdatedMemoryId);
        assertEquals(0.75, store.lastUpdatedImportance, 0.001);
    }

    @Test
    void recallQueriesStoreWithSemanticSearch() {
        var store = new RecordingMemoryStore();
        store.queryResults = List.of(
            new Memory("m1", "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                "Talked about ML", Map.of(), Instant.now().minusSeconds(3600), 0.8));
        var facade = new ChatMemoryFacade(store, store, false);

        var results = facade.recall("agent-1", "t1", "machine learning",
                Set.of(), new PersonalityWeights(Map.of()), Instant.now());

        assertFalse(results.isEmpty());
        assertNotNull(store.lastQuery);
    }

    @Test
    void recallSkipsGraphQueryWhenNotAvailable() {
        var store = new RecordingMemoryStore();
        store.queryResults = List.of();
        var facade = new ChatMemoryFacade(store, store, false);

        facade.recall("agent-1", "t1", "hello",
                Set.of("user-1"), new PersonalityWeights(Map.of()), Instant.now());

        assertFalse(store.graphQueried);
    }

    @Test
    void recallIncludesGraphResultsWhenAvailable() {
        var store = new RecordingMemoryStore();
        store.queryResults = List.of();
        store.graphResults = List.of(
            new Memory("g1", "user-1", ExperienceEvents.DOMAIN, "t1", null,
                "User-1 likes NLP", Map.of(), Instant.now(), 0.9));
        var facade = new ChatMemoryFacade(store, store, true);

        var results = facade.recall("agent-1", "t1", "hello",
                Set.of("user-1"), new PersonalityWeights(Map.of()), Instant.now());

        assertTrue(store.graphQueried);
        assertFalse(results.isEmpty());
    }

    @Test
    void recallRespectsMaxMemories() {
        var store = new RecordingMemoryStore();
        var now = Instant.now();
        var memories = new ArrayList<Memory>();
        for (int i = 0; i < 20; i++) {
            memories.add(new Memory("m" + i, "agent-1", ExperienceEvents.DOMAIN, "t1", null,
                    "Memory " + i, Map.of(), now.minusSeconds(i * 60), 0.5));
        }
        store.queryResults = memories;
        var facade = new ChatMemoryFacade(store, store, false);
        facade.setMaxMemories(5);

        var results = facade.recall("agent-1", "t1", "test",
                Set.of(), new PersonalityWeights(Map.of()), now);

        assertEquals(5, results.size());
    }

    static class RecordingMemoryStore implements GraphCaseMemoryStore {
        List<MemoryInput> stored = new ArrayList<>();
        MemoryQuery lastQuery;
        List<Memory> queryResults = List.of();
        List<Memory> graphResults = List.of();
        String lastUpdatedMemoryId;
        double lastUpdatedImportance;
        boolean graphQueried;
        int storeCounter;

        @Override public String store(MemoryInput input) {
            stored.add(input);
            return "mem-" + (++storeCounter);
        }
        @Override public List<Memory> query(MemoryQuery query) {
            lastQuery = query;
            return queryResults;
        }
        @Override public void updateImportance(String id, String t, double imp) {
            lastUpdatedMemoryId = id;
            lastUpdatedImportance = imp;
        }
        @Override public int erase(EraseRequest r) { return 0; }
        @Override public List<Memory> graphQuery(GraphMemoryQuery q) {
            graphQueried = true;
            return graphResults;
        }
    }
}
