package io.quarkmind.agency.moment;

import io.quarkmind.agency.AgencyContext;
import java.util.List;

public interface MomentDetector {
    List<MomentEvent> detect(AgencyContext context);
}
