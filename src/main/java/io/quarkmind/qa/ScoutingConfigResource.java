package io.quarkmind.qa;

import io.casehub.annotation.CaseType;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import io.quarkmind.agent.ScoutingIntelBroker;
import io.quarkmind.plugin.scouting.DroolsScoutingTask;

@UnlessBuildProfile("prod")
@Path("/qa/scouting")
public class ScoutingConfigResource {

    @Inject ScoutingIntelBroker broker;
    @Inject @CaseType("starcraft-game") DroolsScoutingTask scoutingTask;

    /** Reloads the subscription union from preferences — next tick's CEP gate reflects changes. */
    @POST
    @Path("/subscriptions/reload")
    public Response reloadSubscriptions() {
        broker.refreshAll();
        return Response.noContent().build();
    }

    /** Reloads dispatch thresholds from preferences — next tick uses updated values. */
    @POST
    @Path("/thresholds/reload")
    public Response reloadThresholds() {
        scoutingTask.refreshThresholds();
        return Response.noContent().build();
    }
}
