package io.quarkmind.agency;

import io.quarkmind.agency.needs.NeedState;

public class AgencyContext {

    private final NeedState needState;

    public AgencyContext(NeedState needState) {
        this.needState = needState;
    }

    public NeedState needState() {
        return needState;
    }
}
