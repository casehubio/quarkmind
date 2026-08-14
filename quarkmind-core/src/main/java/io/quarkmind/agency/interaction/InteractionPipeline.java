package io.quarkmind.agency.interaction;

import io.quarkmind.agency.AgencyContext;

public interface InteractionPipeline {
    void evaluate(AgencyContext context);
}
