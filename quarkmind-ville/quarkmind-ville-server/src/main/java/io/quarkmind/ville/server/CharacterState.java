package io.quarkmind.ville.server;

import io.quarkmind.agency.needs.DispositionNeedModifier;
import io.quarkmind.agency.needs.NeedState;
import io.quarkmind.ville.protocol.Position;
import io.quarkmind.ville.protocol.VilleIntent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class CharacterState {

    private final String id;
    private Position position;
    private Position movementTarget;
    private final NeedState needState;
    private final ConcurrentLinkedDeque<VilleIntent> pendingIntents = new ConcurrentLinkedDeque<>();
    private DispositionNeedModifier dispositionModifier;
    private volatile boolean connected;
    private String lastDialogue;

    public CharacterState(String id, Position position) {
        this.id = id;
        this.position = position;
        this.needState = new NeedState();
        this.needState.set("SOCIAL", 100.0);
        this.needState.set("ENERGY", 100.0);
        this.connected = false;
    }

    public String id() { return id; }
    public Position position() { return position; }
    public void setPosition(Position position) { this.position = position; }
    public Position movementTarget() { return movementTarget; }
    public void setMovementTarget(Position target) { this.movementTarget = target; }
    public NeedState needState() { return needState; }
    public void queueIntent(VilleIntent intent) { pendingIntents.addLast(intent); }
    public List<VilleIntent> drainIntents() {
        var result = new ArrayList<VilleIntent>();
        VilleIntent intent;
        while ((intent = pendingIntents.pollFirst()) != null) {
            result.add(intent);
        }
        return result;
    }
    public DispositionNeedModifier dispositionModifier() { return dispositionModifier; }
    public void setDispositionModifier(DispositionNeedModifier mod) { this.dispositionModifier = mod; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }
    public String lastDialogue() { return lastDialogue; }
    public void setLastDialogue(String dialogue) { this.lastDialogue = dialogue; }
}
