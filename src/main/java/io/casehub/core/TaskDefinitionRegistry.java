package io.casehub.core;

import io.casehub.control.PlanningStrategy;
import io.casehub.error.CircularDependencyException;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration and lookup service for both domain {@link TaskDefinition}s and
 * {@link io.casehub.control.PlanningStrategy}s. Validates that registering a new
 * TaskDefinition does not create circular dependencies among entry criteria/produced keys,
 * and provides lookup by case type.
 */
@ApplicationScoped
public class TaskDefinitionRegistry {

    private final Map<String, TaskDefinition> taskDefsById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> tdToCaseTypes = new ConcurrentHashMap<>();
    private final Map<String, PlanningStrategy> strategiesById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> strategyToCaseTypes = new ConcurrentHashMap<>();

    // Domain TaskDefinitions
    public void register(TaskDefinition td, Set<String> caseTypes) throws CircularDependencyException {
        validateNoCycles(td);
        taskDefsById.put(td.getId(), td);
        tdToCaseTypes.put(td.getId(), caseTypes);
    }

    public void unregister(String tdId) {
        taskDefsById.remove(tdId);
        tdToCaseTypes.remove(tdId);
    }

    public List<TaskDefinition> getForCaseType(String caseType) {
        List<TaskDefinition> result = new ArrayList<>();
        tdToCaseTypes.forEach((tdId, types) -> {
            if (types.contains(caseType)) {
                TaskDefinition td = taskDefsById.get(tdId);
                if (td != null) {
                    result.add(td);
                }
            }
        });
        return result;
    }

    public Optional<TaskDefinition> getById(String tdId) {
        return Optional.ofNullable(taskDefsById.get(tdId));
    }

    // PlanningStrategies
    public void registerStrategy(PlanningStrategy strategy, Set<String> caseTypes) {
        strategiesById.put(strategy.getId(), strategy);
        strategyToCaseTypes.put(strategy.getId(), caseTypes);
    }

    public void unregisterStrategy(String strategyId) {
        strategiesById.remove(strategyId);
        strategyToCaseTypes.remove(strategyId);
    }

    public List<PlanningStrategy> getStrategiesForCaseType(String caseType) {
        List<PlanningStrategy> result = new ArrayList<>();
        strategyToCaseTypes.forEach((strategyId, types) -> {
            if (types.contains(caseType)) {
                PlanningStrategy strategy = strategiesById.get(strategyId);
                if (strategy != null) {
                    result.add(strategy);
                }
            }
        });
        return result;
    }

    private void validateNoCycles(TaskDefinition newTd) throws CircularDependencyException {
        // Build a directed graph: edges from each entry criterion -> each produced key
        // This models the data flow: a TaskDefinition consumes entry criteria and produces output keys
        Map<String, Set<String>> graph = new HashMap<>();

        // Add edges for all already-registered domain TaskDefinitions
        for (TaskDefinition td : taskDefsById.values()) {
            addEdges(graph, td);
        }

        // Add edges for the new TaskDefinition
        addEdges(graph, newTd);

        // Run DFS cycle detection
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        List<String> cycle = new ArrayList<>();

        for (String node : graph.keySet()) {
            if (!visited.contains(node)) {
                if (dfsFindCycle(graph, node, visited, onStack, cycle)) {
                    Collections.reverse(cycle);
                    throw new CircularDependencyException(
                            "Circular dependency detected among keys: " + cycle);
                }
            }
        }
    }

    private void addEdges(Map<String, Set<String>> graph, TaskDefinition td) {
        for (String criterion : td.entryCriteria()) {
            for (String produced : td.producedKeys()) {
                graph.computeIfAbsent(criterion, k -> new HashSet<>()).add(produced);
            }
            // Ensure criterion node exists in graph even with no outgoing edges
            graph.computeIfAbsent(criterion, k -> new HashSet<>());
        }
        for (String produced : td.producedKeys()) {
            graph.computeIfAbsent(produced, k -> new HashSet<>());
        }
    }

    private boolean dfsFindCycle(Map<String, Set<String>> graph, String node,
                                  Set<String> visited, Set<String> onStack,
                                  List<String> cycle) {
        visited.add(node);
        onStack.add(node);

        for (String neighbor : graph.getOrDefault(node, Collections.emptySet())) {
            if (!visited.contains(neighbor)) {
                if (dfsFindCycle(graph, neighbor, visited, onStack, cycle)) {
                    cycle.add(node);
                    return true;
                }
            } else if (onStack.contains(neighbor)) {
                cycle.add(neighbor);
                cycle.add(node);
                return true;
            }
        }

        onStack.remove(node);
        return false;
    }
}
