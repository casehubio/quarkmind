package io.quarkmind.agency.session;

import java.util.UUID;

public class AgencySession {

    private volatile UUID id = UUID.randomUUID();

    public UUID id() { return id; }

    public void reset() { id = UUID.randomUUID(); }

    public void setId(UUID id) { this.id = id; }
}
