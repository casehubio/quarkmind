package io.quarkmind.qa;

import io.quarkmind.domain.Race;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Optional;

/**
 * Live configuration for EmulatedGame.
 * Layer 1: read from application.properties with hardcoded defaults.
 * Layer 2: runtime-mutable via EmulatedConfigResource (PUT /qa/emulated/config).
 * Layer 3: visualizer config panel calls the REST endpoint.
 *
 * <p>No profile guard — this bean is instantiated in all profiles (including %mock and %prod).
 * It is only actively read by EmulatedEngine (@IfBuildProfile("emulated")). All
 * @ConfigProperty fields must retain their defaultValue permanently to avoid resolution
 * failures in profiles where the property is not configured.
 *
 * <p>Enemy strategy wiring is handled by EnemyBehavior (Task 5+).
 * Race and strategy name are configurable via config properties and REST.
 */
@ApplicationScoped
public class EmulatedConfig {

    @ConfigProperty(name = "emulated.active", defaultValue = "false")
    boolean active;

    @ConfigProperty(name = "emulated.wave.spawn-frame", defaultValue = "200")
    int defaultWaveSpawnFrame;

    @ConfigProperty(name = "emulated.wave.unit-count", defaultValue = "4")
    int defaultWaveUnitCount;

    @ConfigProperty(name = "emulated.wave.unit-type", defaultValue = "ZEALOT")
    String defaultWaveUnitType;

    @ConfigProperty(name = "emulated.unit.speed", defaultValue = "0.5")
    double defaultUnitSpeed;

    @ConfigProperty(name = "emulated.player.race", defaultValue = "PROTOSS")
    String defaultPlayerRace;

    @ConfigProperty(name = "emulated.enemy.race", defaultValue = "PROTOSS")
    String defaultEnemyRace;

    @ConfigProperty(name = "emulated.enemy.strategy")
    Optional<String> defaultEnemyStrategy;

    // Volatile for thread safety (REST thread writes, scheduler thread reads)
    private volatile int    waveSpawnFrame;
    private volatile int    waveUnitCount;
    private volatile String waveUnitType;
    private volatile double unitSpeed;
    private volatile Race   playerRace;
    private volatile Race   enemyRace;
    private volatile String enemyStrategyName;

    @PostConstruct
    void init() {
        waveSpawnFrame    = defaultWaveSpawnFrame;
        waveUnitCount     = defaultWaveUnitCount;
        waveUnitType      = defaultWaveUnitType;
        unitSpeed         = defaultUnitSpeed;
        playerRace        = Race.valueOf(defaultPlayerRace);
        enemyRace         = Race.valueOf(defaultEnemyRace);
        enemyStrategyName = defaultEnemyStrategy.filter(s -> !s.isBlank()).orElse(null);
    }

    public int    getWaveSpawnFrame()     { return waveSpawnFrame;    }
    public int    getWaveUnitCount()      { return waveUnitCount;     }
    public String getWaveUnitType()       { return waveUnitType;      }
    public double getUnitSpeed()          { return unitSpeed;         }
    public Race   getPlayerRace()         { return playerRace;        }
    public Race   getEnemyRace()          { return enemyRace;         }
    public String getEnemyStrategyName()  { return enemyStrategyName; }

    public void setWaveSpawnFrame(int v)     { this.waveSpawnFrame    = v; }
    public void setWaveUnitCount(int v)      { this.waveUnitCount     = v; }
    public void setWaveUnitType(String v)    { this.waveUnitType      = v; }
    public void setUnitSpeed(double v)       { this.unitSpeed         = v; }
    public void setPlayerRace(Race r)        { this.playerRace        = r; }
    public void setEnemyRace(Race r)         { this.enemyRace         = r; }
    public void setEnemyStrategyName(String n) { this.enemyStrategyName = n; }

    public boolean isActive() { return active; }

    /** Serialisable snapshot for the REST response body. */
    public record Snapshot(boolean active, int waveSpawnFrame, int waveUnitCount,
                           String waveUnitType, double unitSpeed,
                           String enemyRace, String enemyStrategyName) {}

    public Snapshot snapshot() {
        return new Snapshot(active, waveSpawnFrame, waveUnitCount, waveUnitType, unitSpeed,
            enemyRace != null ? enemyRace.name() : null,
            enemyStrategyName);
    }
}
