# Workbench blocks-ui Migration + Commentary Surfacing

**Issue:** casehubio/quarkmind#289
**Date:** 2026-08-26
**Decisions:** D1–D5 in `decisions.md`

## 1. Overview

Replace the visualizer workbench — currently vanilla JS with innerHTML string interpolation — with CaseHub's blocks-ui Lit web component library. Surface commentary in the workbench for the first time via the existing qhorus `quarkmind-commentary` channel.

**What changes:**
- Layout: CSS grid → `<blocks-split-workbench>` with draggable divider
- Pages: innerHTML rendering → Lit components (`<qm-pattern-page>`, `<qm-coaching-page>`, `<qm-strategy-page>`)
- New: `<qm-commentary-page>` using `<blocks-channel-feed>` to render commentary
- New: Commentary pipeline enabled in `%replay` profile
- Workbench WebSocket protocol extended with commentary events

**What doesn't change:**
- Three.js canvas rendering (sprites, models, camera)
- Server-side plugin pipeline (pattern, coaching, strategy, scouting)
- CommentaryChannelBroker / CommentaryWorkerFactory
- Game state WebSocket (`/ws/game`)
- Status bar content

**Scope constraint:** The workbench (WorkbenchSocket, WorkbenchEnricher, WorkbenchBroadcaster) is annotated `@UnlessBuildProfile("prod")` — it is excluded from production builds. All workbench features, including commentary surfacing via the workbench WebSocket, are dev/QA tooling. Commentary's production-ready path is the qhorus `quarkmind-commentary` channel via `CommentaryChannelBroker`. This is deliberate: the workbench is a development instrument for observing agent behaviour, not a user-facing feature.

## 2. Layout Architecture

### Current layout

```
┌─────────────────── #workbench (CSS grid) ───────────────────┐
│ toolbar (36px) — tabs: Pattern | Coaching | Strategy        │
├─────────────────────────────┬────────────────────────────────┤
│ #wb-canvas (Three.js)       │ #wb-panel (300px fixed)       │
│ ~83% width                  │   #wb-pages (tabbed content)  │
│                             │   #wb-detail (160px fixed)    │
├─────────────────────────────┴────────────────────────────────┤
│ #wb-status (24px) — frame, phase, game, intel               │
└──────────────────────────────────────────────────────────────┘
```

### New layout

```
┌────────────── <blocks-split-workbench> ──────────────────────┐
│ header slot: HUD + connection status                         │
├───────────────────────────┬──┬────────────────────────────────┤
│ list slot: Three.js       │÷÷│ detail slot:                  │
│ canvas (50-70%)           │  │ <blocks-detail-pane>          │
│                           │  │   tabs: Pattern | Coaching |  │
│ Camera controls overlay   │  │         Strategy | Commentary │
│ Mode toggle overlay       │  │                               │
│                           │  │   tab content: <qm-*-page>   │
├───────────────────────────┴──┴────────────────────────────────┤
│ status bar (outside split-workbench, same as current)         │
└───────────────────────────────────────────────────────────────┘
```

**Key changes:**
- split-workbench provides draggable divider (20–70% ratio), keyboard resize (arrow keys), localStorage persistence, responsive collapse at 768px, ARIA regions
- Canvas moves to `list` slot (left panel). At 70% max, canvas is 1260px on 1800px viewport (down from ~1500px). Acceptable per D4.
- `<blocks-detail-pane>` replaces manual tab switching with built-in tab bar, keyboard navigation, and ARIA tablist
- Four tabs instead of three (commentary added)
- The toolbar row is absorbed into split-workbench's `header` slot
- Status bar remains outside split-workbench at the bottom of the page

**selection-topic:** The split-workbench and detail-pane share a `selection-topic` for coordinating selection state. For the workbench, we use topic `"qm-workbench"`.

The detail-pane's tabs only render when `_item` is set (via the `${selectionTopic}:selected` event). Without this event, the pane shows the `emptyMessage` div with no tabs. To ensure the detail panel is always visible with tabs:
1. Set `empty-message=""` on the detail-pane (cosmetic fallback for the brief moment before the event fires)
2. Fire a `qm-workbench:selected` event on page load — this is the **required mechanism** that activates tab rendering

The selection event payload should be `{}` (empty object) since workbench page components receive data directly from the controller, not via `.item`. The event is fired from the workbench controller immediately after DOM setup:
```js
import { emitPagesEvent } from '@casehubio/pages-component';
emitPagesEvent(document, 'qm-workbench:selected', {});
```

### CSS custom properties (dark theme)

The visualizer uses a dark theme (`#0a0a1a` background). blocks-ui components use CSS custom properties with fallback defaults designed for light theme. Override at the `:root` level:

```css
:root {
  --pages-neutral-1: #0a0a1a;
  --pages-neutral-2: #0f0f2a;
  --pages-neutral-3: #1a1a3e;
  --pages-neutral-4: #2a2a5e;
  --pages-neutral-7: #888;
  --pages-neutral-11: #ccc;
  --pages-accent-9: #88bbff;
  --pages-font-family: monospace;
}
```

## 3. Component Hierarchy

### Quarkmind Lit components

Four page components, each a Lit element in quarkmind-sc2's static resources:

| Component | File | Replaces |
|-----------|------|----------|
| `<qm-pattern-page>` | `workbench/qm-pattern-page.js` | `renderPatternPage()` |
| `<qm-coaching-page>` | `workbench/qm-coaching-page.js` | `renderCoachingPage()` |
| `<qm-strategy-page>` | `workbench/qm-strategy-page.js` | `renderStrategyPage()` |
| `<qm-commentary-page>` | `workbench/qm-commentary-page.js` | (new) |

Each component:
- Extends `LitElement`
- Receives data via a reactive `data` property (set by the workbench controller)
- Renders using Lit `html` tagged templates
- Uses blocks-ui primitives where appropriate (e.g. confidence bars, status badges)

### Workbench controller

A new `workbench/qm-workbench-controller.js` module replaces the global workbench state and WebSocket management in `visualizer.js`. Responsibilities:
- Manages WebSocket connection to `/ws/workbench`
- Maintains workbench state (pattern, coaching, strategy, commentary)
- Pushes data to page components via property assignment (all four pages use the same pattern)
- Maintains `QhorusMessage[]` array for commentary — maps `CommentaryPayload` to `QhorusMessage`, sets `.messages` on `<blocks-channel-feed>`
- Handles coaching acknowledgment (send coaching_response via WebSocket)
- Fires `qm-workbench:selected` event on DOM setup to activate `<blocks-detail-pane>` tab rendering

### detail-pane tab registration

The `<blocks-detail-pane>` is configured with four tabs:

```js
const detailPane = document.querySelector('blocks-detail-pane');
detailPane.tabs = [
  { id: 'pattern',    label: 'Pattern',    tagName: 'qm-pattern-page',    order: 0 },
  { id: 'coaching',   label: 'Coaching',   tagName: 'qm-coaching-page',   order: 1 },
  { id: 'strategy',   label: 'Strategy',   tagName: 'qm-strategy-page',   order: 2 },
  { id: 'commentary', label: 'Commentary', tagName: 'qm-commentary-page', order: 3 },
];
detailPane.emptyMessage = '';
```

The detail-pane creates tab elements lazily via `document.createElement(tagName)` and sets `.item` on them. For the workbench, `.item` is not used (each page manages its own data); pages receive data directly from the controller.

## 4. Data Flow

### Pattern, coaching, strategy (unchanged protocol, new rendering)

```
CDI Event (PatternAssessmentPublished, CoachingAdvicePublished, etc.)
  → WorkbenchEnricher observes, enriches, serializes
    → WorkbenchBroadcaster.broadcast(WorkbenchEvent)
      → WebSocket /ws/workbench
        → qm-workbench-controller.js
          → sets .data on <qm-pattern-page> / <qm-coaching-page> / <qm-strategy-page>
            → Lit re-renders
```

No server-side changes needed for these three event types. The controller parses the WebSocket message, identifies the event type, and pushes data to the appropriate page component.

### Commentary (new data path)

Commentary messages are already persisted via `CommentaryChannelBroker` to the qhorus `quarkmind-commentary` channel. For the workbench, we add a parallel path through the workbench WebSocket:

**Server side — new observer in WorkbenchEnricher:**

```java
void onCommentaryCompleted(@Observes CommentaryCompleted event) {
    broadcaster.broadcast(new WorkbenchEvent("commentary",
        new CommentaryPayload(event.text(), event.capability(),
            event.commentaryType().name(), event.gameFrame(),
            event.workerId(), event.latencyMs(), Instant.now())));
}
```

New record `CommentaryPayload(String text, String capability, String commentaryType, long gameFrame, String workerId, long latencyMs, Instant createdAt)` implements `WorkbenchPayload`. The `createdAt` field captures the observation timestamp — for live events this is `Instant.now()`, for history messages it is the original `Message.createdAt()` from the qhorus store.

This requires adding `CommentaryPayload` to the sealed interface's `permits` clause:

```java
public sealed interface WorkbenchPayload
    permits PatternPayload, CoachingPayload, CoachingCompliancePayload, StrategyPayload, CommentaryPayload {}
```

**Server side — history on connect:**

When a workbench WebSocket session opens, send commentary history **before** registering the session for live broadcasts. This ordering is critical — commentary is append-only, so if live events arrive while history is still sending, the client receives messages out of order. By sending history first and then joining the live broadcast set, we guarantee chronological ordering at the cost of potentially missing commentary that fires during the brief gap (acceptable for a dev tool).

```java
@OnOpen
public void onOpen(WebSocketConnection connection) {
    sendCommentaryHistory(connection);     // history first — before live events
    broadcaster.addSession(connection);    // then register for live broadcasts + snapshots
}
```

`sendCommentaryHistory` implementation:
1. **Channel ID:** Inject `CommentaryChannelBroker` into `WorkbenchSocket` and use `broker.channelId()` to resolve the UUID
2. **Query:** Call `messageService.history(channelId, 0L, 500)` to fetch messages ascending from the start. Then take the **tail 100**: `history.subList(Math.max(0, history.size() - 100), history.size())`. The `history()` method (which delegates to `pollAfter`) returns messages in ascending ID order after the given `afterId` — `afterId=0` returns from the oldest. The limit of 500 is a safety cap (commentary volume per game is bounded at ~60–200 messages); taking the tail 100 ensures we send the most recent messages, not the oldest.
3. **Deserialization:** Each `Message` has `content()` containing JSON-serialized `CommentaryCompleted`. Deserialize via `objectMapper.readValue(message.content(), CommentaryCompleted.class)` to recover the structured commentary data
4. **Timestamp:** Use `msg.createdAt()` to preserve the original timestamp (not wall-clock at send time)
5. **Wrap and send:** For each deserialized entry, construct `WorkbenchEvent("commentary_snapshot", new CommentaryPayload(...))` and send as a JSON WebSocket text message

```java
private void sendCommentaryHistory(WebSocketConnection connection) {
    UUID channelId = commentaryChannelBroker.channelId();
    if (channelId == null) return;
    List<Message> all = messageService.history(channelId, 0L, 500);
    List<Message> recent = all.size() > 100
        ? all.subList(all.size() - 100, all.size()) : all;
    for (Message msg : recent) {
        try {
            CommentaryCompleted completed = objectMapper.readValue(
                msg.content(), CommentaryCompleted.class);
            WorkbenchEvent event = new WorkbenchEvent("commentary_snapshot",
                new CommentaryPayload(completed.text(), completed.capability(),
                    completed.commentaryType().name(), completed.gameFrame(),
                    completed.workerId(), completed.latencyMs(), msg.createdAt()));
            connection.sendText(objectMapper.writeValueAsString(event))
                .subscribe().with(ignored -> {}, err -> {});
        } catch (Exception e) {
            // skip malformed messages
        }
    }
}
```

**Server side — updateSnapshot:**

Commentary events are append-only (unlike pattern/strategy/coaching which are latest-value snapshots). Add an explicit case in `WorkbenchBroadcaster.updateSnapshot()` for documentation clarity:

```java
private void updateSnapshot(WorkbenchEvent event) {
    switch (event.type()) {
        case "pattern"  -> latestPattern  = event;
        case "strategy" -> latestStrategy = event;
        case "coaching" -> latestCoaching = event;
        case "commentary", "commentary_snapshot" -> {} // append-only — no snapshot replacement
        default -> {}
    }
}
```

**Client side — direct QhorusMessage[] property assignment:**

The `qm-commentary-page` embeds a `<blocks-channel-feed>` (sub-component of channel-activity, used directly for simpler integration — per D2 rationale "the 12 sub-components are available for direct composition"). The workbench controller maintains a `QhorusMessage[]` array and sets `.messages` on channel-feed directly — the same pattern used for pattern/coaching/strategy pages:

```
WebSocket message (type: "commentary")
  → controller maps CommentaryPayload to QhorusMessage
    → creates new array: this._messages = [...this._messages, newMessage]
      → sets .messages on <blocks-channel-feed> (new array reference)
        → Lit re-renders with new message

WebSocket message (type: "commentary_snapshot")
  → controller maps CommentaryPayload to QhorusMessage
    → creates new array: this._messages = [...this._messages, newMessage]
      → sets .messages on <blocks-channel-feed> (new array reference)
        → Lit re-renders
```

**Immutable array updates:** `<blocks-channel-feed>` declares `.messages` as a Lit reactive property (`@property({ type: Array })`). Lit uses strict reference equality (`===`) for change detection — mutating the same array via `push()` and reassigning does NOT trigger a re-render. The controller must create a new array reference on every update (`[...old, new]`). Auto-scroll and unread counting in channel-feed both depend on `changed.has('messages')` firing correctly.

**QhorusMessage mapping for commentary:**

`<blocks-channel-feed>` expects `QhorusMessage[]`. The workbench controller maps each `CommentaryPayload` to a `QhorusMessage` as follows:

| QhorusMessage field | Source | Value |
|---|---|---|
| `id` | generated | `'commentary-' + messageCounter++` (monotonic client-side counter) |
| `channelId` | fixed | `'quarkmind-commentary'` |
| `sender` | payload | `payload.workerId` (e.g. `'commentator-atlas'`) |
| `messageType` | fixed | `'STATUS'` (matches `CommentaryChannelBroker`'s `MessageType.STATUS`) |
| `actorType` | fixed | `'AGENT'` |
| `content` | payload | `payload.text` |
| `topic` | payload | `payload.commentaryType` (e.g. `'REACTIVE'`, `'NARRATIVE'`) |
| `topicId` | none | `''` |
| `replyCount` | fixed | `0` |
| `artefactRefs` | fixed | `[]` |
| `createdAt` | payload | `payload.createdAt` (ISO 8601 from `Instant` serialization — original observation time for live, original `Message.createdAt()` for history) |

The `formatSender` callback on `<blocks-channel-feed>` can map `workerId` to a display name (e.g. `'commentator-atlas'` → `'Atlas'`).

**Why not PushController:** `<blocks-channel-feed>` takes `.messages: QhorusMessage[]` as a reactive Lit property — it has no knowledge of PushController. The PushController → ChannelStateController → channel-feed pipeline is designed for the full multi-channel qhorus experience. For a single commentary feed in the workbench, direct property assignment is simpler, consistent with the other three page data paths, and avoids unnecessary intermediaries.

**Why channel-feed directly (not full channel-activity):** The workbench has a single commentary channel — no channel nav, no topics, no member panel. Using `<blocks-channel-feed>` directly avoids the split-workbench-within-split-workbench nesting and the channel navigation UI that serves no purpose with one channel.

### Coaching acknowledgment (unchanged)

The coaching response flow (accept/dismiss → WebSocket → `CoachingAcknowledgmentHandler`) remains unchanged. The `<qm-coaching-page>` component emits a custom event when a button is clicked, and the workbench controller sends the WebSocket message.

## 5. Replay Commentary

### Profile wiring

Enable the commentary pipeline in `%replay` profile by adding configuration to `application.properties`:

```properties
%replay.quarkmind.commentary.enabled=true
```

The commentary pipeline is already wired to observe L2 moment events from the summarisation pipeline. In replay mode, the game tick executor runs the full agent loop including summarisation and moment detection. The missing piece is:
1. `CommentaryWorkerFactory` needs a `ChatModel` bean available in `%replay`
2. The LLM config (model, API key) needs to be set for the replay profile

**Timing strategy:** At 1x replay speed, commentary arrives in real time relative to game events. At >1x speed, commentary will lag. For the initial implementation, accept lag — commentary catches up during quiet phases. The spec defers the synchronized-pause model (where the replay engine waits for the LLM before advancing past commentary triggers) to a follow-up issue, as it requires replay engine changes.

**New config properties:**

```properties
%replay.quarkmind.commentary.enabled=true
%replay.quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini
%replay.quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:}
```

### Commentary activation guard

Add a configuration-based activation guard in `QuarkMindCaseHub.wireCommentary()`. `CommentaryWorkerFactory` is a static-only utility class (private constructor, no CDI), so the guard belongs in the CDI bean that calls it:

```java
// In QuarkMindCaseHub — CDI bean, can receive @ConfigProperty injection
@ConfigProperty(name = "quarkmind.commentary.enabled", defaultValue = "false")
boolean commentaryEnabled;

private int wireCommentary(List<Capability> capabilities, List<Binding> bindings,
                           List<Worker> workers) {
    if (!commentaryEnabled) {
        log.info("[CASEHUB] Commentary disabled via quarkmind.commentary.enabled=false");
        return 0;
    }
    if (chatModelInstance == null || !chatModelInstance.isResolvable()) {
        log.warn("[CASEHUB] No ChatModel bean available — commentary workers omitted.");
        return 0;
    }
    // ... existing wiring code ...
}
```

The `commentaryEnabled` check is an early return before the existing ChatModel check. This prevents commentary from running in profiles where it's not wanted (mock, emulated) without needing to exclude the bean entirely. The `%replay` profile sets `quarkmind.commentary.enabled=true` to opt in.

## 6. JS Module Resolution

### The problem

blocks-ui components are ESM modules with bare specifiers (`import { LitElement } from 'lit'`). They cannot be loaded directly by a browser `<script>` tag without module resolution.

### Approach: Quinoa + Vite build in quarkmind-sc2

Add Quarkus Quinoa to quarkmind-sc2 for the frontend build pipeline. This is the platform-standard integration for consuming blocks-ui components (see platform UI architecture: three-tier consumption model).

**Directory structure:**

```
quarkmind-sc2/
  src/main/webui/
    package.json          ← minimal: lit + @casehubio/blocks-ui-* deps via portal resolutions
    vite.config.ts        ← builds workbench-blocks.js → dist/
    workbench-entry.ts    ← imports and re-exports needed components
    .casehub-packages/    ← unpacked from Maven artifacts (git-ignored)
```

**workbench-entry.ts** imports all modules that require bare specifier resolution (Vite resolves these at build time; the browser cannot resolve bare specifiers at runtime):

```ts
// External blocks-ui dependencies
import '@casehubio/blocks-ui-split-workbench';
import '@casehubio/blocks-ui-detail-pane';
import '@casehubio/blocks-ui-channel-activity/channel-feed';
// Local components — extend LitElement, use @customElement decorator,
// import blocks-ui primitives via bare specifiers
import './workbench/qm-pattern-page.ts';
import './workbench/qm-coaching-page.ts';
import './workbench/qm-strategy-page.ts';
import './workbench/qm-commentary-page.ts';
// Controller — imports emitPagesEvent from @casehubio/pages-component
import './workbench/qm-workbench-controller.ts';
```

All quarkmind-local modules that use `import ... from 'lit'` or `import ... from '@casehubio/...'` must be part of this entry (directly or transitively). Without this, `@customElement` decorators never execute and `document.createElement('qm-pattern-page')` produces an undefined custom element. Lit is a transitive dependency.

**Build integration — three-tier consumption model:**

1. `pom.xml` declares Maven dependencies on `casehub-blocks-ui-npm` and `casehub-pages-npm` SNAPSHOT artifacts
2. `maven-dependency-plugin:unpack` extracts npm packages to `src/main/webui/.casehub-packages/`
3. `package.json` uses `portal:` resolutions pointing to `.casehub-packages/` (no npm registry lookups)
4. Quinoa runs `yarn install` (resolves from local portals) → `yarn build` (Vite) → serves output from `META-INF/resources/`

Quinoa provides automatic Vite build detection, dev mode live reload (HMR for `.ts` changes), and output served from `META-INF/resources/` — no `frontend-maven-plugin` needed.

**visualizer.html** loads the bundle:

```html
<script type="module" src="/blocks/workbench-blocks.js"></script>
<script src="/sprites/three.min.js"></script>
<script src="/visualizer.js"></script>
```

**Three.js scope constraint:** ES modules have their own scope. `THREE` global set by `three.min.js` (non-module script) is accessible to `visualizer.js` (also non-module) but NOT inside the Lit/Vite module bundle. Canvas rendering code (Three.js scene, camera, sprites) must remain in non-module scripts — it must NOT move into the Lit components or the Vite bundle. The workbench controller (ES module) communicates with the canvas renderer through DOM events or shared global state, not by importing Three.js APIs.

**Why not import maps:** Import maps require serving every transitive dependency individually. The Lit dependency tree alone has ~8 modules. Managing the map is fragile and breaks on any dependency update. A bundler resolves this once at build time.

**Why not a blocks-ui browser bundle:** Producing a browser-ready bundle is a blocks-ui concern. It's the right long-term solution but requires changes to the blocks-ui build pipeline. For #289, a quarkmind-local build is self-contained and unblocked.

## 7. Testing Strategy

### Playwright pixel tests (updated)

`WorkbenchRenderTest` and `WorkbenchSocketIT` need full rewrites:

**WorkbenchRenderTest:**
- Assert `<blocks-split-workbench>` renders with two panels
- Assert `<blocks-detail-pane>` has four tabs (Pattern, Coaching, Strategy, Commentary)
- Assert tab switching changes visible content
- Assert canvas (Three.js) is present in the list slot
- Assert dark theme CSS custom properties are applied

**WorkbenchSocketIT:**
- Assert pattern data renders in `<qm-pattern-page>` after WebSocket message
- Assert coaching data renders in `<qm-coaching-page>` with accept/dismiss buttons
- Assert coaching acknowledgment sends WebSocket message
- Assert strategy data renders in `<qm-strategy-page>`
- Assert commentary data renders in `<qm-commentary-page>` via channel-feed

### Unit tests (new)

- `WorkbenchEventTest` — add `CommentaryPayload` serialization test
- `WorkbenchEnricherTest` — add `onCommentaryCompleted` observer test

### Replay commentary test

A new Playwright test or manual verification:
- Start in `%replay` profile with commentary enabled
- Verify commentary messages appear in the Commentary tab as the replay progresses

## 8. Migration Plan (incremental)

1. **Add Quinoa + Vite build** — `src/main/webui/`, `package.json`, `vite.config.ts`, Quinoa extension. Maven dependency on `casehub-blocks-ui-npm` with unpack to `.casehub-packages/`. Verify `workbench-blocks.js` is produced.
2. **Skeleton layout** — Replace `visualizer.html` CSS grid with `<blocks-split-workbench>` + `<blocks-detail-pane>`. Canvas in list slot, empty tabs in detail slot. Verify Three.js still renders.
3. **Pattern page** — Create `<qm-pattern-page>`, wire to workbench controller. Delete `renderPatternPage()`.
4. **Coaching page** — Create `<qm-coaching-page>` with accept/dismiss buttons, wire acknowledgment. Delete `renderCoachingPage()` and `sendCoachingResponse()`.
5. **Strategy page** — Create `<qm-strategy-page>`. Delete `renderStrategyPage()`.
6. **Commentary server** — Add `CommentaryPayload`, observer in `WorkbenchEnricher`, history-on-connect in `WorkbenchSocket`.
7. **Commentary page** — Create `<qm-commentary-page>` with `<blocks-channel-feed>` and direct QhorusMessage[] property assignment.
8. **Replay commentary** — Profile wiring, activation guard in `QuarkMindCaseHub.wireCommentary()`.
9. **Delete old code** — Remove `setupWorkbenchTabs()`, old CSS classes, old HTML structure.
10. **Test updates** — Rewrite Playwright tests, add unit tests.

Steps 2–5 can be verified independently — each step produces a working workbench with the migrated pages functional.

## 9. Scope Boundaries

**In scope:**
- Full workbench rebuild with blocks-ui
- Commentary surfacing via channel-feed
- Replay commentary (profile wiring + activation guard)
- Playwright test rewrites

**Out of scope (follow-up issues):**
- Synchronized replay-commentary model (#290) — replay pauses for LLM; requires replay engine changes
- Human feedback trust dimensions (#231) — depends on commentary being visible, unblocked by this issue
- Channel-activity sidebar panels (#291) — single-channel workbench doesn't need channel navigation; revisit if commentary evolves to support topics/artifacts

## References

- `specs/issue-289-workbench-blocks-ui/decisions.md` — D1–D5
- `quarkmind-sc2/.../visualizer.html` — current workbench layout (lines 1–175)
- `quarkmind-sc2/.../visualizer.js` — current rendering (lines 783–921)
- `quarkmind-sc2/.../WorkbenchSocket.java` — WebSocket endpoint
- `quarkmind-sc2/.../WorkbenchEnricher.java` — CDI event observer
- `quarkmind-sc2/.../WorkbenchEvent.java` — event record
- `quarkmind-sc2/.../CommentaryChannelBroker.java` — qhorus channel integration
- `quarkmind-sc2/.../CommentaryCompleted.java` — CDI event record
- `blocks-ui/components/split-workbench/src/split-workbench.ts` — layout shell
- `blocks-ui/components/detail-pane/src/detail-pane.ts` — tabbed detail panel
- `blocks-ui/components/channel-activity/src/blocks-channel-activity.ts` — channel wrapper
- `blocks-ui/components/channel-activity/src/push-controller.ts` — transport-agnostic push
- `blocks-ui/components/channel-activity/src/channel-feed.ts` — message feed sub-component
