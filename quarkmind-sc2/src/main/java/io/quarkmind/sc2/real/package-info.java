/**
 * Quarkus-native StarCraft II transport — active only in the %sc2 Quarkus profile.
 * All CDI beans annotated {@code @IfBuildProfile("sc2")}.
 *
 * Connects to SC2's Remote API at {@code ws://127.0.0.1:{port}/sc2api} via
 * {@link io.quarkmind.sc2.real.QuarkusSC2Transport}, which runs a game loop on a
 * virtual thread exchanging binary protobuf frames.
 *
 * Two-loop architecture:
 *  - {@link io.quarkmind.sc2.real.QuarkusSC2Transport} game loop (virtual thread, SC2 frame
 *    speed ~22fps): fires {@link io.quarkmind.sc2.real.SC2FrameCallback#onStep} each frame,
 *    dispatches actions via {@code RequestAction} and advances the simulation via {@code RequestStep}.
 *  - {@link io.quarkmind.agent.AgentOrchestrator} {@code @Scheduled} loop (500ms): reads
 *    GameState via {@code SC2Engine.observe()}, runs CaseEngine, writes Intents to IntentQueue.
 *
 * @see io.quarkmind.sc2.mock for the mock implementations used in development and testing.
 */
package io.quarkmind.sc2.real;
