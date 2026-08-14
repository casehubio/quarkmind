package io.quarkmind.agency.task;

import io.casehub.api.context.CaseContext;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TaskDefinitionTest {

    @Test
    void defaultMethods_returnSensibleDefaults() {
        TaskDefinition task = new TaskDefinition() {
            @Override public String getId() { return "test-plugin"; }
            @Override public String getName() { return "Test Plugin"; }
            @Override public void execute(CaseContext ctx) {}
        };

        assertEquals("test-plugin", task.getId());
        assertEquals("Test Plugin", task.getName());
        assertEquals(Set.of(), task.requires());
        assertEquals(Set.of(), task.produces());
        assertNotNull(task.activateIf());
    }

    @Test
    void requires_canBeOverridden() {
        TaskDefinition task = new TaskDefinition() {
            @Override public String getId() { return "gated"; }
            @Override public String getName() { return "Gated"; }
            @Override public Set<String> requires() { return Set.of("READY", "STRATEGY"); }
            @Override public void execute(CaseContext ctx) {}
        };

        assertEquals(Set.of("READY", "STRATEGY"), task.requires());
    }
}
