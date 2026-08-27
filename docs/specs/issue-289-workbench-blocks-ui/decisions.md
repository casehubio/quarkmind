# Decisions — #289 Workbench blocks-ui migration + commentary surfacing

## D1: Workbench migration scope

**Choice:** Full workbench rebuild — replace the entire workbench with blocks-ui components
**Alternatives:**
- Commentary page only — lower risk but leaves inconsistent tech stack; future pages still need migration
- Hybrid shell — replaces layout only; still two rendering models to maintain
**Rationale:** All CaseHub applications build on pages and blocks-ui. QuarkMind should too. The workbench will grow (commentary, feedback, trust display, future pages) and a consistent component model pays off. Better to do it once cleanly than migrate incrementally.
**Trade-offs:** Breaks existing Playwright pixel tests (WorkbenchRenderTest, WorkbenchSocketIT). Higher upfront effort. Must verify all existing functionality (pattern, coaching, strategy pages) still works after migration.
**Review note (R1-01, R1-07):** Reviewer claimed this requires Quinoa/npm build pipeline. Incorrect — blocks-ui components are consumed as built JS bundles from Maven WebJar artifacts. Consumer adds a Maven dependency and loads JS from META-INF/resources/. No npm infrastructure needed for consuming. Reviewer also claimed pages framework is CRUD-only — overstated. PushController is transport-agnostic; data primitives are domain-agnostic.
**Sources:** blocks-ui consumer guide, existing visualizer.js workbench rendering (~lines 783-921)
**Exploration:** quick
**Status:** captured

## D2: Commentary feed component

**Choice:** `<blocks-channel-activity>` — renders the existing qhorus `quarkmind-commentary` channel natively
**Alternatives:**
- Custom feed with `<blocks-timeline>` — more layout control but requires custom rendering and a new data adapter for something channel-activity already handles
- Compose from channel-activity primitives (`<blocks-channel-feed>` etc.) — more control over layout but more wiring
**Rationale:** Commentary already dispatches to a qhorus channel. The wrapper (shipped on issue-137-channel-activity-wrapper) composes channel-nav, channel-feed, channel-input, channel-topic-bar, and tabbed sidebar. Feedback buttons via `renderContent` extension point. If quarkmind needs a different arrangement (e.g. embedding just `<blocks-channel-feed>` next to the canvas), the 12 sub-components are available for direct composition.
**Review note (R1-02, R1-03):** Reviewer claimed component didn't exist — was correct at review time, now fixed. Reviewer claimed PushController is SSE-locked — incorrect. PushController is transport-agnostic: `pushController.applyOp(op)` from any transport handler (WebSocket `onmessage` is one line of integration).
**Trade-offs:** Tied to qhorus channel semantics. If commentary needs game-frame-synced timeline presentation (not just a feed), would need to revisit.
**Spec refinement:** The spec uses `<blocks-channel-feed>` (a sub-component of channel-activity) directly rather than the full `<blocks-channel-activity>` wrapper. The workbench has a single commentary channel — no channel nav, no topics, no member panel. Using the feed sub-component avoids unnecessary UI (channel switching) and avoids nesting split-workbench-within-split-workbench.
**Sources:** blocks-ui channel-activity wrapper (issue-137), CommentaryChannelBroker.java, PushController API
**Exploration:** quick
**Status:** captured

## D3: Replay commentary mode

**Choice:** Enable the existing commentary pipeline in replay mode — profile configuration to wire CommentaryWorkerFactory and ChatModel in `%replay`
**Alternatives:**
- Post-hoc batch generation — produces polished transcript after replay, but doesn't deliver the "watch a replay with live commentary" experience
- Synchronized replay — pause at commentary-worthy moments, wait for LLM, resume
**Rationale:** The replay engine already runs the full agent loop including game tick executor, summarisation, and moment broker. Commentary is another consumer of L2 moments — the gap is profile wiring (ChatModel config in replay profile).
**Trade-offs:** Requires LLM availability during replay. At >1x replay speed, commentary will lag behind game state. Mitigation options: (1) cap replay speed to 1x when commentary is active, (2) synchronized pause-at-moment model where replay waits for LLM before advancing past commentary triggers, (3) accept lag and let commentary catch up during quiet phases. Option 2 is the strongest UX but requires replay engine changes. Detailed timing strategy deferred to spec.
**Sources:** GameTickExecutor.java, Quarkus profiles in CLAUDE.md, CommentaryWorkerFactory.java
**Exploration:** quick
**Status:** captured

## D4: Workbench layout components

**Choice:** `<blocks-split-workbench>` as the shell with `<blocks-detail-pane>` for tabbed pages
**Alternatives:**
- Keep CSS grid, blocks-ui inside pages only — simpler migration but misses draggable divider, responsive collapse, ARIA regions, localStorage persistence
- Custom `<qm-visualizer-shell>` with CSS Grid — full control but rebuilds what split-workbench provides
**Rationale:** Full rebuild (D1) means replacing the shell too. split-workbench gives draggable resize, responsive collapse, and accessibility for free. Slots are unconstrained — Three.js canvas in the `list` slot is fine. detail-pane's `emptyMessage` is configurable (set to "" for always-visible). Four tabs: Pattern, Coaching, Strategy, Commentary.
**Review note (R1-05, R1-06):** Reviewer claimed slots only accept lists and detail-pane shows "Select an item" unconditionally. Both incorrect — slots accept any children with no type constraints; emptyMessage is a configurable property.
**Constraint:** List panel ratio caps at 0.2–0.7. If the canvas needs >70% width, compose from primitives instead. Current layout is ~83% canvas (1fr vs 300px on 1800px). At 70% cap, canvas loses ~13% — acceptable for most viewport widths but worth monitoring. Wider viewports (>2000px) give 70% = 1400px+ which is generous.
**Depends on:** D1 (full workbench rebuild)
**Sources:** blocks-ui split-workbench component (split-workbench.ts), blocks-ui detail-pane component (detail-pane.ts)
**Exploration:** quick
**Status:** captured

## D5: Page rendering approach

**Choice:** Lit components per page — `<qm-pattern-page>`, `<qm-coaching-page>`, `<qm-strategy-page>`, `<qm-commentary-page>` as quarkmind-specific Lit elements
**Alternatives:**
- Keep vanilla JS rendering inside blocks-ui layout — fewer files to change but two rendering models coexist; defeats the purpose of D1
**Rationale:** Full rebuild means full rebuild. Each existing render function (~40 lines of template) translates directly to a Lit `render()` method. Reactive property updates replace manual `innerHTML` re-rendering. blocks-ui primitives (`<status-badge>`, `<commitment-state-pill>`) replace hand-rolled status badges. Components consumed from Maven WebJar — no npm build pipeline needed in quarkmind.
**Trade-offs:** These components live in quarkmind's own static resources, not in blocks-ui. They import Lit and blocks-ui primitives from the WebJar bundles.
**Depends on:** D1 (full workbench rebuild), D4 (layout components)
**Sources:** existing renderPatternPage/renderCoachingPage/renderStrategyPage in visualizer.js
**Exploration:** quick
**Status:** captured
