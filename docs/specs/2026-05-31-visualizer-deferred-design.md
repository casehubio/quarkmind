# Visualizer Deferred Work — Design Spec

**Issue:** #131  
**Branch:** issue-131-visualizer-deferred  
**Date:** 2026-05-31  
**Revised:** 2026-05-31 (post spec-review)

---

## Context

Four visualizer items deferred from earlier phases, required before the emulated
engine is considered showcase-ready. Sub-tasks 1 and 3 are fully independent.
Sub-task 2's `parseMinerals()` fix must land before sub-task 4, because sub-task 4
reads mineral values from the HUD and any test with minerals ≥ 1,000 would fail
if the comma-format parser is not in place.

Recommended implementation order: 2 → 1 → 3 → 4.

---

## Sub-task 1: Probe Spread

### Problem

When multiple probes are assigned to the same mineral patch they converge on the
same world position. The visualizer renders them as pixel-exact overlapping
sprites — indistinguishable from a single unit.

### Design

Post-pass `applyUnitSpread(spriteMap)` called once at the end of `syncUnits()`,
after all sprite positions are set, operating on `unitSprites` (friendly units) only.

**Clustering:** axis-aligned proximity — units where `|Δx| < TILE*0.5` AND
`|Δz| < TILE*0.5` form a cluster. Rounded-tile grouping is not used; it
misclassifies units at tile boundaries.

**Spread:** for any cluster > 1, distribute sprites in a uniform ring around the
group centroid. Angular step: `(2π / clusterSize) * i`. Spread radius: `TILE * 0.32` —
visible separation without looking like units have left the patch.

**Stability:** ring assignment is insertion-order stable within a session (JS Map
preserves insertion order). Assignment is not stable across page reloads — two
probes that arrive at a patch in different orders across sessions may swap ring
positions. This is acceptable; the invariant is visual stability during a session,
not identity-preserving determinism.

**3D meshes:** `applyUnitSpread` offsets only the 2D sprite position, not the
corresponding entry in `unit3dMeshes`. In 3D-camera mode probes still stack. This
is intentional — 3D mode is a debug view, not the primary showcase path. The 2D
sprite position is what the raycaster uses for click detection, so click hits will
land on the spread positions, not the game-model positions. This is a known minor
footgun for any future feature correlating screen clicks back to game coordinates;
document in an inline comment.

---

## Sub-task 2: Mineral HUD Formatting

### Problem

`updateHud()` emits mineral count as a raw integer. Large values are unreadable;
there is no visual feedback when the economy is critically low.

### Design

Switch the HUD element from `textContent` to `innerHTML`, wrapping only the
mineral value in a `<span id="minerals-val">`. All other fields remain as
concatenated string content with no HTML — safe, no additional XSS surface.
The `innerHTML` change is safe here because `state.minerals` is a server-computed
integer passed through `toLocaleString()`, never user-controlled. Do not use this
pattern for any HUD field that may contain user-supplied or untrusted content.

**New `updateHud()` template:**
```js
const tier = m < 50 ? 'minerals-critical' : m < 150 ? 'minerals-low' : '';
document.getElementById('hud').innerHTML =
  `Minerals: <span id="minerals-val" class="${tier}">${m.toLocaleString('en-US')}</span>` +
  `   Gas: ${state.vespene}` +
  `   Supply: ${state.supplyUsed}/${state.supply}` +
  `   Frame: ${state.gameFrame}`;
```
where `m = state.minerals ?? 0`.

**Format:** `toLocaleString('en-US')` — produces `"1,234"`. Locale pinned to
`en-US` for stability across all Electron/Chromium contexts.

**Color tiers:**

| Class | Threshold | Color | Rationale |
|-------|-----------|-------|-----------|
| `minerals-critical` | `< 50` | `#ff4444` | Cannot train any unit (cheapest = 50 — Probe, Marine, Drone across all races) |
| `minerals-low` | `50–149` | `#ffaa00` | Can train one basic unit; immediately broke. Threshold is Protoss-anchored (Zealot = 100); applies to all races by approximation |
| _(default)_ | `≥ 150` | HUD default | Functioning economy |

The `< 50` threshold is cross-race valid. The `50–149` threshold is Protoss-anchored
but is a reasonable proxy for all races in this codebase (QuarkMind currently
implements Protoss economy in mock mode). If multi-race HUD is added later, derive
thresholds from `SC2Data.mineralCost(activeRaceWorker)`.

**CSS:** three rules in the existing style block in `visualizer.html`.

**Java — `parseMinerals()` rewrite:**
The current implementation uses a character scanner that stops at the first
non-digit. With comma-formatted output it returns `1` for `"1,234"`. The method
must be rewritten:
```java
private static int parseMinerals(String hud) {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile("Minerals:\\s*([\\d,]+)").matcher(hud);
    if (!m.find()) throw new AssertionError("HUD text missing 'Minerals:': " + hud);
    return Integer.parseInt(m.group(1).replace(",", ""));
}
```

**Existing test fix — `hudMineralCountIncreasesWithTicks()`:**
The inline JS regex at line 434 uses `\d+` and will not match comma-formatted
values. Update to `[\d,]+`:
```java
"() => { const m = window.__test.hudText().match(/Minerals:\\s*([\\d,]+)/); " +
"        return m && parseInt(m[1].replace(/,/g,'')) > " + threshold + "; }",
```

---

## Sub-task 3: Resource Sprites

### Problem

Geysers render as a plain green solid-colour `THREE.Sprite` — visually
indistinguishable from a tinted square. Mineral patches use the same primitive
pattern in blue. Neither can be verified by pixel-colour assertion in
`VisualizerRenderTest`.

### Design

Replace both solid-colour resource sprites with canvas-drawn textures via a shared
`makeResourceMaterial(type)` factory. The factory replaces the inline
`new THREE.SpriteMaterial({ color: ... })` calls in both `syncGeysers()` and
`syncMineralPatches()`. Materials are created once at startup and stored as
module-level constants — same lifecycle as `BUILDING_MATS`.

**Geyser canvas (64×64px):**
- Base: radial gradient `#00cc88` (centre) → `#007755` (edge), circle filling ~80% of canvas
- Overlay: three concentric rings at 30%, 50%, 70% radius in `rgba(180,255,220,0.5)` — gas-vent suggestion
- `SpriteMaterial`: `transparent: true, depthWrite: false, alphaTest: 0.05` — replaces old `opacity: 0.95` on the solid-colour material; canvas edges are naturally transparent so explicit opacity is not needed
- Scale: `s * 0.9 × s * 1.1` — slightly taller than square, distinguishes from mineral patch

**Mineral patch canvas (64×64px):**
- Base: radial gradient `#66ccff` (centre) → `#2277aa` (edge), circle filling ~80% of canvas
- Overlay: two horizontal streaks in `rgba(180,230,255,0.4)` — crystal-vein suggestion
- `SpriteMaterial`: same flags as geyser
- Scale: `s * 1.4 × s * 0.8` — preserves existing wider-than-tall shape

**`makeResourceMaterial(type)` signature:**
```js
function makeResourceMaterial(type) { // type: 'geyser' | 'mineral'
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  // dispatch to drawGeyser(ctx, 64) or drawMineralPatch(ctx, 64)
  const tex = new THREE.CanvasTexture(c);
  return new THREE.SpriteMaterial({ map: tex, transparent: true,
                                    depthWrite: false, alphaTest: 0.05 });
}
const GEYSER_MAT  = makeResourceMaterial('geyser');
const MINERAL_MAT = makeResourceMaterial('mineral');
```

**Test additions (`VisualizerRenderTest`):**
- Geyser: `focusOnFirstGeyser()`, sample centre pixel, assert `R < G && B < G && (R > 10 || B > 10)`. The `(R > 10 || B > 10)` guard confirms a gradient rendered — not the degenerate solid-colour fallback.
- Mineral: same pattern — `focusOnFirstMineral()` (add to `window.__test` if absent), assert `B > R && B > G && (R > 10 || G > 10)`.

---

## Sub-task 4: Time-Based UI Tests

### Problem

Current Playwright tests express timing as raw tick counts. Tick counts are an
implementation detail; game-time seconds are the meaningful unit.

### Background — tick vs game loop

`SC2Data.LOOPS_PER_TICK = 22`. `engine.tick()` (= `SimulatedGame.gameTick()`) advances
`gameFrame` by 1 per call — i.e., `gameFrame` is the outer tick count, not the
game loop count. One outer tick = 22 game loops. To get game-time seconds from
`gameFrame`: `gameFrame * LOOPS_PER_TICK / GAME_LOOPS_PER_SECOND = gameFrame * 22 / 22.4`.

### Design

**`window.__test` addition (visualizer.js):**
```js
// gameFrame in SimulatedGame is the outer tick count (SC2Data.LOOPS_PER_TICK = 22
// game loops per tick). Multiply by 22 before dividing by 22.4 (GAME_LOOPS_PER_SECOND).
gameTimeSeconds: () => ((state?.gameFrame ?? 0) * 22) / 22.4
```

**Java helper (private, `VisualizerRenderTest`):**
```java
// Advances engine by enough outer ticks to cover the requested game-seconds.
// One outer tick = SC2Data.LOOPS_PER_TICK (22) game loops.
// Calls observe() after the tick loop so the visualizer receives the updated state.
private void tickForSeconds(double seconds) {
    int ticks = (int) Math.ceil(
        seconds * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK);
    for (int i = 0; i < ticks; i++) orchestrator.gameTick();
    engine.observe();
}
```

`engine.observe()` (or the equivalent `orchestrator.observe()`) must be called
after the tick loop — without it, the JS page still sees pre-tick state and any
HUD assertion will read stale values.

**New test: `mineralIncomeScalesWithGameTime`**
```java
// Verify mineral income expressed in game-time seconds, not tick counts.
int ticks = (int) Math.ceil(5 * SC2Data.GAME_LOOPS_PER_SECOND / SC2Data.LOOPS_PER_TICK); // 6
tickForSeconds(5);
page.waitForFunction(
    "() => window.__test.gameTimeSeconds() >= 4.5", // ≥ 4.5s after 6 ticks ≈ 5.89s
    new Page.WaitForFunctionOptions().setTimeout(3000));
int minerals = parseMinerals((String) page.evaluate("() => window.__test.hudText()"));
int expectedFloor = SC2Data.INITIAL_MINERALS +
    (int)(ticks * SC2Data.mineralIncomePerTick(SC2Data.INITIAL_PROBES));
assertThat(minerals).as("minerals after 5 game-seconds").isGreaterThanOrEqualTo(expectedFloor);
```

The income lower bound uses `ticks * mineralIncomePerTick(12)` — not `seconds * GAME_LOOPS_PER_SECOND * mineralIncomePerTick(12)`, which is dimensionally wrong
(income is per outer tick, not per game loop).

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/resources/META-INF/resources/visualizer.js` | `applyUnitSpread()`, `updateHud()` → innerHTML + tiers, `makeResourceMaterial()` + `drawGeyser()` + `drawMineralPatch()`, `syncGeysers()` + `syncMineralPatches()` updates, `window.__test.gameTimeSeconds` |
| `src/main/resources/META-INF/resources/visualizer.html` | CSS rules for mineral tier colours |
| `src/test/java/io/quarkmind/qa/VisualizerRenderTest.java` | `parseMinerals()` rewrite, JS regex fix in `hudMineralCountIncreasesWithTicks()`, geyser + mineral pixel assertions, `tickForSeconds()` helper, `mineralIncomeScalesWithGameTime` test, `focusOnFirstMineral()` if absent in `window.__test` |

No Java domain model changes. No new CDI beans. No server-side state changes.

---

## Testing

All changes covered by `VisualizerRenderTest` (`@Tag("browser")`).

Run: `mvn test -Pplaywright`

Existing tests must continue to pass. New assertions added:
- Geyser canvas pixel colour (`R < G && B < G && (R > 10 || B > 10)`)
- Mineral patch canvas pixel colour (`B > R && B > G && (R > 10 || G > 10)`)
- `mineralIncomeScalesWithGameTime`
