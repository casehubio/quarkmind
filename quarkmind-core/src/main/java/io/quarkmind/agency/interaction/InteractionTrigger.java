package io.quarkmind.agency.interaction;

import io.quarkmind.agency.AgencyContext;
import java.util.Optional;

public interface InteractionTrigger {
    Optional<TriggerEvent> evaluate(AgencyContext context);
}
