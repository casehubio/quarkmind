package io.casehub.worker;

import io.casehub.coordination.PropagationContext;
import io.casehub.resilience.RetryPolicy;

import java.time.Duration;
import java.util.*;

/**
 * Immutable request object for submitting a Task via {@link TaskBroker}. Uses the builder pattern
 * to carry task type, input context, required case worker capabilities, optional timeout,
 * idempotency key, {@link io.casehub.coordination.PropagationContext}, and retry policy.
 *
 * @see TaskBroker#submitTask(TaskRequest)
 */
public class TaskRequest {
    private String taskType;
    private Map<String, Object> context;
    private Set<String> requiredCapabilities;
    private Optional<Duration> timeout;
    private Optional<String> idempotencyKey;
    private PropagationContext propagationContext;
    private RetryPolicy retryPolicy;

    private TaskRequest() {
        this.context = new HashMap<>();
        this.requiredCapabilities = new HashSet<>();
        this.timeout = Optional.empty();
        this.idempotencyKey = Optional.empty();
        this.retryPolicy = RetryPolicy.defaults();
    }

    public String getTaskType() { return taskType; }
    public Map<String, Object> getContext() { return context; }
    public Set<String> getRequiredCapabilities() { return requiredCapabilities; }
    public Optional<Duration> getTimeout() { return timeout; }
    public Optional<String> getIdempotencyKey() { return idempotencyKey; }
    public PropagationContext getPropagationContext() { return propagationContext; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }

    public static TaskRequestBuilder builder() {
        return new TaskRequestBuilder();
    }

    public static class TaskRequestBuilder {
        private final TaskRequest request = new TaskRequest();

        public TaskRequestBuilder taskType(String taskType) {
            request.taskType = taskType;
            return this;
        }

        public TaskRequestBuilder context(Map<String, Object> context) {
            request.context = new HashMap<>(context);
            return this;
        }

        public TaskRequestBuilder requiredCapabilities(Set<String> capabilities) {
            request.requiredCapabilities = new HashSet<>(capabilities);
            return this;
        }

        public TaskRequestBuilder timeout(Duration timeout) {
            request.timeout = Optional.ofNullable(timeout);
            return this;
        }

        public TaskRequestBuilder idempotencyKey(String key) {
            request.idempotencyKey = Optional.ofNullable(key);
            return this;
        }

        public TaskRequestBuilder propagationContext(PropagationContext ctx) {
            request.propagationContext = ctx;
            return this;
        }

        public TaskRequestBuilder retryPolicy(RetryPolicy policy) {
            request.retryPolicy = policy;
            return this;
        }

        public TaskRequest build() {
            Objects.requireNonNull(request.taskType, "taskType is required");
            return request;
        }
    }
}
