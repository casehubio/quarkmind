package io.quarkmind.qa.workbench;

import java.time.Instant;

public record CommentaryPayload(
    String text,
    String capability,
    String commentaryType,
    long gameFrame,
    String workerId,
    long latencyMs,
    Instant createdAt
) implements WorkbenchPayload {}
