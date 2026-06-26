package io.quarkmind.sc2.emulated.server;

import SC2APIProtocol.Common;
import SC2APIProtocol.Raw;
import SC2APIProtocol.Sc2Api;
import com.google.protobuf.ByteString;
import io.quarkmind.domain.EnemyStrategy;
import io.quarkmind.domain.Race;
import io.quarkmind.domain.TechTree;
import io.quarkmind.domain.TerrainGrid;
import io.quarkmind.qa.EmulatedConfig;
import io.quarkmind.sc2.SC2WebSocketCodec;
import io.quarkmind.sc2.emulated.*;
import io.quarkmind.sc2.intent.Intent;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.*;

@IfBuildProfile("emulated-sc2")
@ApplicationScoped
@Startup
public class EmulatedSC2Server {

    private static final Logger log = Logger.getLogger(EmulatedSC2Server.class);

    @Inject EmulatedConfig config;

    @ConfigProperty(name = "starcraft.sc2.port", defaultValue = "8168")
    int configuredPort;

    private final EmulatedGame game = new EmulatedGame();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;
    private volatile Socket activeClient;
    private Race playerRace;
    private TerrainGrid terrainGrid;

    @PostConstruct
    void start() {
        try {
            startOnPort(configuredPort);
        } catch (IOException e) {
            throw new RuntimeException("[EMULATED-SC2] Failed to start server", e);
        }
    }

    void startOnPort(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = Thread.ofVirtual().name("emulated-sc2-acceptor").start(this::acceptLoop);
        log.infof("[EMULATED-SC2] Server listening on port %d", serverSocket.getLocalPort());
    }

    int port() { return serverSocket.getLocalPort(); }

    @PreDestroy
    void stop() {
        running = false;
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) {}
        if (acceptThread != null) try { acceptThread.join(2000); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop() {
        try {
            while (running && !serverSocket.isClosed()) {
                Socket client = serverSocket.accept();
                Thread.ofVirtual().name("emulated-sc2-handler").start(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running) log.debugf("[EMULATED-SC2] Accept loop closed: %s", e.getMessage());
        }
    }

    private void handleClient(Socket client) {
        try {
            SC2WebSocketCodec.performServerHandshake(client.getInputStream(), client.getOutputStream());
            if (activeClient != null && !activeClient.isClosed()) {
                log.warnf("[EMULATED-SC2] Rejecting second WebSocket session — SC2 is single-client");
                return;
            }
            activeClient = client;
            serveProtocol(client);
        } catch (Exception e) {
            log.debugf("[EMULATED-SC2] Client handler closed: %s", e.getMessage());
        } finally {
            if (activeClient == client) activeClient = null;
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void serveProtocol(Socket client) throws Exception {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        while (!client.isClosed() && running) {
            byte[] payload = SC2WebSocketCodec.readFrame(in);
            if (payload == null) break;

            Sc2Api.Request req = Sc2Api.Request.parseFrom(payload);
            Sc2Api.Response resp = buildResponse(req);
            out.write(SC2WebSocketCodec.encodeServerFrame(resp.toByteArray()));
            out.flush();
        }
    }

    private Sc2Api.Response buildResponse(Sc2Api.Request req) {
        Sc2Api.Response.Builder b = Sc2Api.Response.newBuilder();

        if (req.hasPing()) {
            return b.setPing(Sc2Api.ResponsePing.getDefaultInstance())
                    .setStatus(Sc2Api.Status.launched).build();
        }
        if (req.hasCreateGame()) {
            return b.setCreateGame(Sc2Api.ResponseCreateGame.getDefaultInstance())
                    .setStatus(Sc2Api.Status.init_game).build();
        }
        if (req.hasJoinGame()) {
            wireEmulatedGame();
            return b.setJoinGame(Sc2Api.ResponseJoinGame.newBuilder().setPlayerId(1).build())
                    .setStatus(Sc2Api.Status.in_game).build();
        }
        if (req.hasGameInfo()) {
            return b.setGameInfo(buildGameInfo()).setStatus(Sc2Api.Status.in_game).build();
        }
        if (req.hasObservation()) {
            return b.setObservation(GameStateToProtobuf.translate(game.snapshot()))
                    .setStatus(Sc2Api.Status.in_game).build();
        }
        if (req.hasAction()) {
            handleActions(req.getAction());
            return b.setAction(Sc2Api.ResponseAction.getDefaultInstance())
                    .setStatus(Sc2Api.Status.in_game).build();
        }
        if (req.hasStep()) {
            game.tick();
            return b.setStep(Sc2Api.ResponseStep.getDefaultInstance())
                    .setStatus(Sc2Api.Status.in_game).build();
        }
        if (req.hasQuit()) {
            return b.setQuit(Sc2Api.ResponseQuit.getDefaultInstance())
                    .setStatus(Sc2Api.Status.quit).build();
        }
        return b.setStatus(Sc2Api.Status.in_game).build();
    }

    private void wireEmulatedGame() {
        terrainGrid = TerrainGrid.emulatedMap();
        game.setMovementStrategy(new PathfindingMovement(terrainGrid));
        game.setTerrainGrid(terrainGrid);

        playerRace = config != null ? config.getPlayerRace() : Race.PROTOSS;
        game.setPlayerRaceModel(RaceModelFactory.forRace(playerRace));

        Race enemyRace = config != null ? config.getEnemyRace() : Race.PROTOSS;
        String strategyName = config != null ? config.getEnemyStrategyName() : null;
        EnemyStrategy strategy = (strategyName != null && !strategyName.isBlank())
            ? EnemyStrategyLibrary.forName(strategyName)
            : EnemyStrategyLibrary.randomForRace(enemyRace);
        game.setEnemyBehavior(new EnemyBehavior(strategy, game.enemy, new TechTree()));

        game.reset();
        log.infof("[EMULATED-SC2] Game wired — player=%s, enemy strategy=%s",
            playerRace, strategy.name());
    }

    private Sc2Api.ResponseGameInfo buildGameInfo() {
        TerrainGrid grid = terrainGrid != null ? terrainGrid : TerrainGrid.emulatedMap();
        byte[] pathingData = grid.toPathingGrid();

        // 8bpp terrain height stub — ocraft requires this (orElseThrow)
        Common.ImageData heightStub = Common.ImageData.newBuilder()
            .setBitsPerPixel(8)
            .setSize(Common.Size2DI.newBuilder().setX(1).setY(1).build())
            .setData(ByteString.copyFrom(new byte[]{0}))
            .build();

        // 1bpp placement grid stub — 8×1 to avoid 1×1 1bpp integer division edge case
        Common.ImageData placementStub = Common.ImageData.newBuilder()
            .setBitsPerPixel(1)
            .setSize(Common.Size2DI.newBuilder().setX(8).setY(1).build())
            .setData(ByteString.copyFrom(new byte[]{0}))
            .build();

        Raw.StartRaw startRaw = Raw.StartRaw.newBuilder()
            .setMapSize(Common.Size2DI.newBuilder().setX(64).setY(64).build())
            .setPathingGrid(Common.ImageData.newBuilder()
                .setBitsPerPixel(1)
                .setSize(Common.Size2DI.newBuilder().setX(64).setY(64).build())
                .setData(ByteString.copyFrom(pathingData))
                .build())
            .setTerrainHeight(heightStub)
            .setPlacementGrid(placementStub)
            .setPlayableArea(SC2APIProtocol.Common.RectangleI.newBuilder()
                .setP0(Common.PointI.newBuilder().setX(0).setY(0).build())
                .setP1(Common.PointI.newBuilder().setX(64).setY(64).build())
                .build())
            .build();

        Common.Race protoRace = mapToProtobufRace(playerRace != null ? playerRace : Race.PROTOSS);
        return Sc2Api.ResponseGameInfo.newBuilder()
            .setMapName("emulated-64x64")
            .addPlayerInfo(Sc2Api.PlayerInfo.newBuilder()
                .setPlayerId(1)
                .setType(Sc2Api.PlayerType.Participant)
                .setRaceRequested(protoRace)
                .build())
            .setStartRaw(startRaw)
            .setOptions(Sc2Api.InterfaceOptions.newBuilder().setRaw(true).build())
            .build();
    }

    private Common.Race mapToProtobufRace(Race race) {
        return switch (race) {
            case PROTOSS -> Common.Race.Protoss;
            case TERRAN -> Common.Race.Terran;
            case ZERG -> Common.Race.Zerg;
        };
    }

    private void handleActions(Sc2Api.RequestAction actionReq) {
        for (Sc2Api.Action action : actionReq.getActionsList()) {
            if (action.hasActionRaw() && action.getActionRaw().hasUnitCommand()) {
                Intent intent = ProtobufToIntent.translate(action.getActionRaw().getUnitCommand());
                if (intent != null) {
                    game.applyIntent(intent);
                }
            }
        }
    }
}
