package io.quarkmind.agency.chat;

import io.casehub.connectors.chat.model.ReceivedMessage;

public record ClassifiedMessage(ReceivedMessage message, AttentionPriority priority) {}
