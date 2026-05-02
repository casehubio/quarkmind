package io.quarkmind.qa;

import io.quarkmind.domain.Race;
import io.quarkus.arc.profile.UnlessBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@UnlessBuildProfile("prod")
@Path("/qa/emulated/config")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatedConfigResource {

    @Inject EmulatedConfig config;

    @GET
    public EmulatedConfig.Snapshot getConfig() {
        return config.snapshot();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, Object> updates) {
        if (updates.containsKey("waveSpawnFrame"))
            config.setWaveSpawnFrame(((Number) updates.get("waveSpawnFrame")).intValue());
        if (updates.containsKey("waveUnitCount"))
            config.setWaveUnitCount(((Number) updates.get("waveUnitCount")).intValue());
        if (updates.containsKey("waveUnitType"))
            config.setWaveUnitType((String) updates.get("waveUnitType"));
        if (updates.containsKey("unitSpeed"))
            config.setUnitSpeed(((Number) updates.get("unitSpeed")).doubleValue());
        if (updates.containsKey("enemyRace")) {
            try {
                config.setEnemyRace(Race.valueOf(((String) updates.get("enemyRace")).trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                return Response.status(400).entity("Unknown race: " + updates.get("enemyRace")).build();
            }
        }
        if (updates.containsKey("enemyStrategyName")) {
            String name = ((String) updates.get("enemyStrategyName")).trim();
            config.setEnemyStrategyName(name.isBlank() ? null : name);
        }
        return Response.ok(config.snapshot()).build();
    }

    @PUT
    @Path("/enemy-race")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setEnemyRace(String race) {
        try {
            config.setEnemyRace(Race.valueOf(race.trim().toUpperCase()));
            return Response.ok().build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity("Unknown race: " + race).build();
        }
    }

    @PUT
    @Path("/enemy-strategy")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response setEnemyStrategy(String strategyName) {
        config.setEnemyStrategyName(strategyName.trim().isBlank() ? null : strategyName.trim());
        return Response.ok().build();
    }
}
