package io.quarkmind.sc2.emulated.server;

import SC2APIProtocol.Debug;
import SC2APIProtocol.Sc2Api;
import com.github.ocraft.s2client.protocol.data.Units;
import com.github.ocraft.s2client.protocol.observation.Observation;
import com.github.ocraft.s2client.protocol.response.ResponseGameInfo;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import io.quarkmind.sc2.GameResult;
import io.quarkmind.sc2.real.QuarkusSC2Transport;
import io.quarkmind.sc2.real.SC2FrameCallback;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

class EmulatedSC2ServerTest {

    private EmulatedSC2Server server;
    private QuarkusSC2Transport transport;

    @BeforeEach
    void setUp() throws Exception {
        server = new EmulatedSC2Server();
        server.startOnPort(0); // ephemeral port
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.shutdown();
        if (server != null) server.stop();
    }

    private QuarkusSC2Transport connectedTransport() throws Exception {
        QuarkusSC2Transport t = new QuarkusSC2Transport();
        t.sc2Port = server.port();
        t.mapName = "emulated-map";
        t.difficultyStr = "VERY_EASY";
        t.aiRaceStr = "RANDOM";
        t.botRaceStr = "PROTOSS";
        t.connectRetryCount = 5;
        t.connectRetryIntervalMs = 200;
        t.skipProcessLaunch = true;
        t.connect();
        return t;
    }

    @Test @Timeout(10)
    void connect_pingSucceeds() throws Exception {
        transport = connectedTransport();
        // connect() sends a ping — if we get here, it succeeded
    }

    @Test @Timeout(10)
    void gameLoop_receivesObservationsAndEnds() throws Exception {
        transport = connectedTransport();
        transport.createGame();
        transport.joinGame();

        AtomicInteger stepCount = new AtomicInteger();
        CountDownLatch gameStarted = new CountDownLatch(1);
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) {
                gameStarted.countDown();
            }
            @Override public void onStep(Observation obs) throws InterruptedException {
                assertThat(obs).isNotNull();
                assertThat(obs.getGameLoop()).isGreaterThanOrEqualTo(0);
                if (stepCount.incrementAndGet() >= 5) {
                    transport.quit();
                }
            }
            @Override public void onGameEnd(GameResult result) {
                gameEnded.countDown();
            }
        });

        assertThat(gameStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gameEnded.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stepCount.get()).isGreaterThanOrEqualTo(5);
    }

    @Test @Timeout(10)
    void gameInfo_containsStartRaw_withPathingGrid() throws Exception {
        transport = connectedTransport();
        transport.createGame();
        transport.joinGame();

        AtomicReference<ResponseGameInfo> capturedInfo = new AtomicReference<>();
        CountDownLatch gameStarted = new CountDownLatch(1);
        CountDownLatch gameEnded = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) {
                capturedInfo.set(info);
                gameStarted.countDown();
            }
            @Override public void onStep(Observation obs) throws InterruptedException {
                transport.quit();
            }
            @Override public void onGameEnd(GameResult result) {
                gameEnded.countDown();
            }
        });

        assertThat(gameStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gameEnded.await(5, TimeUnit.SECONDS)).isTrue();

        ResponseGameInfo info = capturedInfo.get();
        assertThat(info).isNotNull();
        assertThat(info.getStartRaw()).isPresent();
        assertThat(info.getStartRaw().get().getPathingGrid()).isNotNull();
        assertThat(info.getStartRaw().get().getMapSize().getX()).isEqualTo(64);
        assertThat(info.getStartRaw().get().getMapSize().getY()).isEqualTo(64);
    }

    @Test @Timeout(10)
    void observations_containMineralsAndUnits() throws Exception {
        transport = connectedTransport();
        transport.createGame();
        transport.joinGame();

        AtomicReference<Observation> capturedObs = new AtomicReference<>();
        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                capturedObs.set(obs);
                stepped.countDown();
                transport.quit();
            }
            @Override public void onGameEnd(GameResult result) { ended.countDown(); }
        });

        assertThat(stepped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();

        Observation obs = capturedObs.get();
        assertThat(obs.getPlayerCommon().getMinerals()).isGreaterThanOrEqualTo(0);
        assertThat(obs.getRaw()).isPresent();
    }

    @Test @Timeout(10)
    void debugCreateUnit_spawnsUnitsVisibleInObservation() throws Exception {
        transport = connectedTransport();
        transport.createGame();
        transport.joinGame();

        AtomicReference<Observation> capturedObs = new AtomicReference<>();
        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger stepCount = new AtomicInteger();

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                if (stepCount.incrementAndGet() == 1) {
                    transport.sendDebug(Sc2Api.RequestDebug.newBuilder()
                        .addDebug(Debug.DebugCommand.newBuilder()
                            .setCreateUnit(Debug.DebugCreateUnit.newBuilder()
                                .setUnitType(Units.PROTOSS_ZEALOT.getUnitTypeId())
                                .setOwner(2)
                                .setPos(Point2d.of(12f, 12f).toSc2Api())
                                .setQuantity(2)
                                .build())
                            .build())
                        .build());
                } else if (stepCount.get() == 2) {
                    capturedObs.set(obs);
                    stepped.countDown();
                    transport.quit();
                }
            }
            @Override public void onGameEnd(GameResult result) { ended.countDown(); }
        });

        assertThat(stepped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();

        Observation obs = capturedObs.get();
        long enemyUnitCount = obs.getRaw().get().getUnits().stream()
            .filter(u -> u.getAlliance() == com.github.ocraft.s2client.protocol.unit.Alliance.ENEMY)
            .count();
        assertThat(enemyUnitCount).isGreaterThanOrEqualTo(2);
    }

    @Test @Timeout(10)
    void debugAllResources_setsMineralsAndVespene() throws Exception {
        transport = connectedTransport();
        transport.createGame();
        transport.joinGame();

        AtomicReference<Observation> capturedObs = new AtomicReference<>();
        CountDownLatch stepped = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger stepCount = new AtomicInteger();

        transport.runGameLoop(new SC2FrameCallback() {
            @Override public void onGameStart(ResponseGameInfo info) {}
            @Override public void onStep(Observation obs) throws InterruptedException {
                if (stepCount.incrementAndGet() == 1) {
                    transport.sendDebug(Sc2Api.RequestDebug.newBuilder()
                        .addDebug(Debug.DebugCommand.newBuilder()
                            .setGameState(Debug.DebugGameState.all_resources)
                            .build())
                        .build());
                } else if (stepCount.get() == 2) {
                    capturedObs.set(obs);
                    stepped.countDown();
                    transport.quit();
                }
            }
            @Override public void onGameEnd(GameResult result) { ended.countDown(); }
        });

        assertThat(stepped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();

        Observation obs = capturedObs.get();
        assertThat(obs.getPlayerCommon().getMinerals()).isGreaterThanOrEqualTo(10000);
        assertThat(obs.getPlayerCommon().getVespene()).isGreaterThanOrEqualTo(10000);
    }
}
