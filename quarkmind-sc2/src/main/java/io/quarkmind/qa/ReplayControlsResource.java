package io.quarkmind.qa;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkmind.agent.AgentOrchestrator;

import io.quarkmind.sc2.SC2Engine;
import io.quarkmind.sc2.replay.ReplayEngine;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@UnlessBuildProfile("prod")
@HandWrittenEndpoint("application-specific game simulation endpoint")
@Path("/qa/replay")
public class ReplayControlsResource {

    @Inject AgentOrchestrator orchestrator;
    @Inject SC2Engine engine;
    @Inject GameStateBroadcaster broadcaster;

    @GET @Path("/status") @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        if (!(engine instanceof ReplayEngine re)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(new ReplayStatusResponse(
            re.currentLoop(), re.totalLoops(),
            orchestrator.isSchedulerPaused(), orchestrator.getSpeedMultiplier()
        )).build();
    }

    @POST @Path("/pause")
    public Response pause() {
        orchestrator.pauseScheduler();
        return Response.noContent().build();
    }

    @POST @Path("/resume")
    public Response resume() {
        orchestrator.resumeScheduler();
        return Response.noContent().build();
    }

    @POST @Path("/seek")
    public Response seek(@QueryParam("loop") long targetLoop) {
        if (!(engine instanceof ReplayEngine re)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        broadcaster.setSuppressed(true);
        try {
            re.seekTo(targetLoop);
        } finally {
            broadcaster.setSuppressed(false);
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/reset")
    public Response reset() {
        if (!(engine instanceof ReplayEngine re)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        orchestrator.pauseScheduler();
        orchestrator.stopGame();
        broadcaster.setSuppressed(true);
        try {
            re.seekTo(0);
        } finally {
            broadcaster.setSuppressed(false);
        }
        orchestrator.startGame();
        orchestrator.resumeScheduler();
        return Response.noContent().build();
    }


    @GET @Path("/snapshot") @Produces(MediaType.APPLICATION_JSON)
    public Response snapshot() {
        var state = engine.observe();
        // Count by type for unit breakdown
        var myUnitCounts = new java.util.TreeMap<String, Long>();
        state.myUnits().forEach(u -> myUnitCounts.merge(u.type().name(), 1L, Long::sum));
        var enemyUnitCounts = new java.util.TreeMap<String, Long>();
        state.enemyUnits().forEach(u -> enemyUnitCounts.merge(u.type().name(), 1L, Long::sum));
        var enemyBldgCounts = new java.util.TreeMap<String, Long>();
        state.enemyBuildings().forEach(b -> enemyBldgCounts.merge(b.type().name(), 1L, Long::sum));
        var myBldgCounts = new java.util.TreeMap<String, Long>();
        state.myBuildings().forEach(b -> myBldgCounts.merge(b.type().name(), 1L, Long::sum));

        return Response.ok(new java.util.LinkedHashMap<String, Object>() {{
            put("loop",           state.gameFrame() * 22);
            put("minerals",       state.minerals());
            put("vespene",        state.vespene());
            put("supply",         state.supplyUsed() + "/" + state.supply());
            put("mineralPatches", state.mineralPatches().size());
            put("geysers",        state.geysers().size());
            put("myUnits",        myUnitCounts);
            put("myBuildings",    myBldgCounts);
            put("enemyUnits",     enemyUnitCounts);
            put("enemyBuildings", enemyBldgCounts);
        }}).build();
    }

    @POST @Path("/speed")
    public Response speed(@QueryParam("multiplier") int multiplier) {
        if (multiplier < 0 || multiplier > 8) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        orchestrator.setSpeedMultiplier(multiplier);
        return Response.noContent().build();
    }

    @GET
    @Path("/sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response syncStatus() {
        return Response.ok(new ReplaySyncResponse(
                orchestrator.getSyncMode(), orchestrator.getSyncTimeoutSeconds()
        )).build();
    }

    @POST
    @Path("/sync")
    public Response setSyncMode(@QueryParam("mode") String mode) {
        if (mode == null || (!mode.equals("full") && !mode.equals("reactive-only") && !mode.equals("none"))) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity("mode must be full, reactive-only, or none").build();
        }
        orchestrator.setSyncMode(mode);
        return Response.noContent().build();
    }

    record ReplaySyncResponse(String mode, int timeoutSeconds) {}

}
