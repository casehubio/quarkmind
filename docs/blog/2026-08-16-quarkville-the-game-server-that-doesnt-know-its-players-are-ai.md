---
title: "QuarkVille: The Game Server That Doesn't Know Its Players Are AI"
date: 2026-08-16
type: diary
status: draft
issue: 273
tags: [quarkmind-ville, architecture, agency-framework, game-design]
---

QuarkMind started as a StarCraft II agent. The agency framework we extracted into quarkmind-core — needs, intents, perception, the whole perceive-need-goal-plan-act loop — was always meant to work for other worlds. QuarkVille is the first test of that claim.

The idea is a Sims/Animal Crossing-style town sim. Autonomous characters walking around, talking, managing competing needs, driven by personality. I looked at Wacky Manor — a CaseHub example that already runs multiple LLM characters — and the first instinct was to copy it and make it generic. But Manor has a structural problem: `ScenarioOrchestrator` is a god class that owns both the world simulation and the LLM orchestration. The game logic and the AI thinking are tangled in one JVM.

The cleaner split came from asking a different question: what if the game server didn't know its players were AI?

A mechanical server runs the world — positions, need decay, action resolution, fixed-rate tick at 500ms. Each LLM agent is an independent WebSocket client that connects, receives perception ("you see Bob 10m north, he said 'hello'"), calls Claude, and sends back intents ("MOVE to (15, 20, 0)", "TALK: Hello Bob"). The server doesn't care whether the client is an LLM, a human, or a bot script. The WebSocket protocol is the boundary.

This maps cleanly onto quarkmind-core's SPIs. `WorldBridge<VillePerception, VilleIntent>` lives on the client side — the WebSocket adapter. `AgencyLoop.tick()` runs in each client after receiving perception. `NeedState` is server-authoritative (the server decays needs; the agent reads them from perception, never mutates locally). `IntentQueue<VilleIntent>` buffers decisions client-side and dispatches them to the server.

The needs design turned out to be the most interesting part. Two needs for the first milestone: SOCIAL (decays when alone, satisfied by conversation) and ENERGY (decays from activity, recovers when stationary and alone). Claude's plan review caught a game design flaw in my initial spec: if ENERGY recovers from standing still regardless of who's nearby, the optimal strategy is to never move. The fix makes ENERGY recover only when stationary AND alone. An introvert's ENERGY decays faster near others, so they genuinely need to move away to rest. An extrovert's SOCIAL decays fast when alone, so they seek proximity. Two needs, two dispositions, and the characters organically converge and diverge.

The server side is straightforward Quarkus: `VilleServer` holds the `WorldState` and runs `GameTick.execute()` on a `@Scheduled` timer. `VilleSocket` is the WebSocket Next endpoint at `/ws/ville` — clients send a CONNECT message declaring their role (agent or observer), then the server filters perceptions by range for agents and sends everything to observers. `PerceptionBuilder` does the range filtering — each agent only sees characters within conversation range (5.0 units), while observers get the full world state for visualization.

The agent client proved the quarkmind-core SPI contract works without CaseEngine. `VilleAgencyLoop` implements `AgencyLoop` with a simple `LlmInvoker` functional interface — no CDI, no CaseFile, just perception in, LLM call, intents out. The LLM receives current position, need levels, and nearby characters with their recent dialogue, then responds with a JSON action. `VilleWorldBridge` wraps a `BlockingQueue<VillePerception>` — the WebSocket listener feeds perceptions in, `perceive()` blocks until one arrives. `AgentRunner` wires them together with virtual threads — one per character, same code that would run as separate processes in production.

The Godot 4 client connects as an observer. An isometric camera over a green ground plane, CSG cylinder placeholders for characters (Kenney assets come later), `Label3D` dialogue bubbles that fade after five seconds, and a thought panel at the bottom showing each agent's internal reasoning forwarded from the server. The client is GDScript — `WebSocketPeer` connecting to the same `/ws/ville` endpoint.

The module structure reflects the client/server boundary: `quarkmind-ville-protocol` (shared types — sealed `VilleIntent`, `VillePerception`, directional `VilleClientMessage`/`VilleServerMessage`), `quarkmind-ville-server` (Quarkus game loop, world state, WebSocket endpoint), `quarkmind-ville-agent` (agency loop, WorldBridge, LLM invocation). Three POMs instead of one, but agents might run on different machines — they can't have the server on their classpath.

The name shift matters too. "quarkmind-town" was too close to DevTown, another CaseHub application. QuarkVille — distinct, evocative, and it stuck immediately.

What's genuinely interesting is how little QuarkVille needed from quarkmind-core. Eight SPIs exercised (`WorldBridge`, `AgencyLoop`, `NeedState`, `NeedDefinition`, `DispositionNeedModifier`, `Intent`, `IntentQueue`, `AgencyContext`) — and none of them needed modification. The SC2 extraction held. The SPIs that weren't needed (`NavigationSPI`, `VisibilitySPI`, `SpatialMemory`, `MomentDetector`) are exactly the ones that require game-specific complexity: obstacles, fog of war, significant events. They'll earn their place when QuarkVille grows walls and rooms.
