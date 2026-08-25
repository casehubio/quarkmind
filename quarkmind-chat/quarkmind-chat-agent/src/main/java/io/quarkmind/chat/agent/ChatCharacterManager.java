package io.quarkmind.chat.agent;

import io.quarkmind.agency.AgencyContext;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.chat.protocol.ChatPerception;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatCharacterManager {

    private final ChatAgencyLoop agencyLoop;
    private final Map<String, CharacterContext> characters = new ConcurrentHashMap<>();

    public ChatCharacterManager(ChatAgencyLoop agencyLoop) {
        this.agencyLoop = agencyLoop;
    }

    public void addCharacter(CharacterContext character) {
        characters.put(character.agentId(), character);
    }

    public void tickCharacter(String agentId, ChatPerception perception) {
        var character = characters.get(agentId);
        if (character == null) return;

        var context = new AgencyContext(new NeedState());
        context.put("perception", perception);
        context.put("character", character);

        agencyLoop.tick(context);
    }

    public CharacterContext character(String agentId) {
        return characters.get(agentId);
    }

    public int characterCount() {
        return characters.size();
    }
}
