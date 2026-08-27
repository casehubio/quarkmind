---
layout: post
title: "Shadow DOM Ate My Test API"
date: 2026-08-27
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [blocks-ui, lit, shadow-dom, playwright, workbench]
---

# Shadow DOM Ate My Test API

The QuarkMind workbench ran on vanilla JS — innerHTML rendering, CSS grid layout, manual tab switching. It worked, but every page component was a function that dumped HTML into a div and hoped nothing else was writing to the same container. Adding commentary surfacing meant a fifth page, which meant a fifth function following the same fragile pattern. Time to migrate.

We replaced the entire layout with CaseHub's blocks-ui components: `<blocks-split-workbench>` for the split-pane shell, `<blocks-detail-pane>` for tabbed navigation, and four Lit page components — pattern, coaching, strategy, commentary — each rendering inside Shadow DOM. Quinoa handles the Vite build; the compiled bundle lands as a single ES module import. The old CSS grid, the tab-switching JS, the innerHTML render functions — all gone.

The commentary pipeline was the motivating feature. `WorkbenchEnricher` observes `CommentaryCompleted` events and forwards them through the workbench WebSocket as a new `commentary` message type. On connect, `WorkbenchSocket` replays the last 100 commentary messages from the Qhorus channel history so a late-joining client sees the full narrative. The `<qm-commentary-page>` component receives these as `QhorusMessage[]` and hands them to `<blocks-channel-feed>`, which already knows how to render channel activity. One config flag — `quarkmind.commentary.enabled` — gates the whole thing off by default, active only in replay mode.

The interesting problem came after the migration landed. The Playwright test suite has a `window.__test` API that exposes semantic accessors — sprite counts, HUD text, panel visibility, pattern assessment counts. Several of these reached into DOM elements that no longer exist. `setupInspectPanel()` injected a unit-detail panel into `#wb-detail`, which the migration removed. `__test.hudText()` queried `#hud` — but the element was `#wb-hud`, a bug that predated the migration and had been silently returning empty strings. The mineral tier test expected an element with `id="minerals-val"` that `updateHud()` never created.

The inspect panel fix was the most structural: rather than finding a new home inside the `<blocks-detail-pane>` tabs, we created a floating overlay inside `#wb-canvas` — absolute-positioned, semi-transparent, shown on unit click, hidden on empty-canvas click. The panel now lives where the interaction happens, directly over the canvas, rather than competing for space in the detail pane. Arguably a better design than the original fixed panel.

The Playwright tests themselves needed rewriting for Shadow DOM. Tab clicks became `page.evaluate()` calls that pierce the shadow root — `document.querySelector('blocks-detail-pane').shadowRoot.querySelector('[aria-controls="panel-coaching"]').click()`. The `@TestHTTPResource` annotation replaced hardcoded port numbers. Both `WorkbenchRenderTest` and `WorkbenchSocketIT` now pass cleanly against the new layout.

What this opens up: the workbench is now a proper component architecture. Adding a sixth page means writing a Lit component and registering a tab — not copying another innerHTML function and hoping the event wiring holds. The commentary feed gets the same rendering treatment as Slack-style channel activity anywhere else in the CaseHub platform. And the `window.__test` API, now that it actually queries the right elements, provides reliable semantic hooks for visual regression testing.
