package io.quarkmind.qa;

import io.casehub.platform.api.mcp.HandWrittenEndpoint;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkmind.sc2.map.SC2MapCache;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@UnlessBuildProfile("prod")
@HandWrittenEndpoint("application-specific game simulation endpoint")
@Path("/qa/terrain")
@Produces(MediaType.APPLICATION_JSON)
public class TerrainResource {

    @Inject SC2MapCache cache;

    @GET
    public Response getTerrain(@QueryParam("map") String mapName) {
        if (mapName == null || mapName.isBlank())
            return Response.status(Response.Status.BAD_REQUEST).build();
        return cache.get(mapName)
            .map(Response::ok)
            .orElse(Response.status(Response.Status.NOT_FOUND))
            .build();
    }
}
