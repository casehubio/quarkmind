package io.quarkmind.chat.agent;

import io.casehub.eidos.api.AgentDescriptor;
import io.quarkmind.agency.chat.BotIdentityDetector;

import java.util.Set;
import java.util.function.Supplier;

public class CharacterContext {

    private final String agentId;
    private final String tenantId;
    private final String systemPrompt;
    private final Supplier<AgentDescriptor> descriptorSupplier;
    private final BotIdentityDetector identityDetector;
    private final Set<String> participatedThreadIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ChatWorldBridge worldBridge;

    public CharacterContext(String agentId, String tenantId, String systemPrompt,
                            Supplier<AgentDescriptor> descriptorSupplier,
                            BotIdentityDetector identityDetector,
                            ChatWorldBridge worldBridge) {
        this.agentId = agentId;
        this.tenantId = tenantId;
        this.systemPrompt = systemPrompt;
        this.descriptorSupplier = descriptorSupplier;
        this.identityDetector = identityDetector;
        this.worldBridge = worldBridge;
    }

    public CharacterContext(String agentId, String tenantId, String systemPrompt,
                            Supplier<AgentDescriptor> descriptorSupplier,
                            BotIdentityDetector identityDetector) {
        this(agentId, tenantId, systemPrompt, descriptorSupplier, identityDetector, null);
    }

    public String agentId() { return agentId; }
    public String tenantId() { return tenantId; }
    public String systemPrompt() { return systemPrompt; }
    public Supplier<AgentDescriptor> descriptorSupplier() { return descriptorSupplier; }
    public BotIdentityDetector identityDetector() { return identityDetector; }
    public Set<String> participatedThreadIds() { return participatedThreadIds; }
    public ChatWorldBridge worldBridge() { return worldBridge; }
}
