package io.quarkmind.chat.agent;

import java.util.List;

public record CharacterConfig(String agentId, String token, List<String> channels) {}
