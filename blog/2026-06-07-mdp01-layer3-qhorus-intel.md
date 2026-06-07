---
layout: post
title: "Layer 3: Four Surprises from qhorus"
date: 2026-06-07
type: phase-update
entry_type: note
subtype: diary
projects: [quarkmind]
tags: [qhorus, casehub, java, cdi]
---

# QuarkMind — Layer 3: Four Surprises from qhorus

**Date:** 2026-06-07
**Type:** phase-update

---

## What I was trying to achieve: typed intel delivery from scouting to tactics

`DroolsTacticsTask` has been reading threat position directly from the CaseFile blackboard — `caseFile.get(QuarkMindCaseFile.NEAREST_THREAT, Point2d.class)`. That works, but it couples tactics to knowing the exact key name scouting writes. Layer 3 is supposed to fix that: replace the raw blackboard read with a typed message on a casehub-qhorus channel.

The design was simple enough on paper. `ScoutingIntelBroker` creates a channel at startup, `DroolsScoutingTask` dispatches typed intel messages when values change beyond a configurable threshold, `DroolsTacticsTask` implements `MessageObserver` and caches the latest intel in an `AtomicReference<TacticsIntelCache>`. Tactics no longer needs to know any CaseFile key name — it just reads from its cache.

## What we believed going in: the spec had been reviewed against source

The design had been reviewed against actual qhorus source code before implementation started. All the API surprises — the channel creation signature, the `MessageObserver` method name, the JSON deserialization pattern — had been caught and corrected at the spec stage. I thought the implementation would be straightforward.

It wasn't.

## Four things qhorus doesn't document

**EVENT content is null.** `MessageObserverDispatcher` explicitly nullifies content for `MessageType.EVENT` before constructing `MessageReceivedEvent`. The constructor even throws if you try to pass non-null content for EVENT. The right type for content-carrying observe-channel broadcasts is `MessageType.STATUS`. This is in the source code but not in any docs. Filed as qhorus#257.

**Channel names cannot contain dots.** `ChannelSlugValidator` enforces `[a-z][a-z0-9]*(-[a-z0-9]+)*` per segment. The spec had named the channel `quarkmind.scouting.intel` following Java package conventions. That throws `IllegalArgumentException: Invalid channel name segment` at startup. The error doesn't explain why dots are the problem — it just flags the whole name. Had to rename to `quarkmind-scouting-intel`. Filed as qhorus#258.

**`@Transactional` is not intercepted in `@PostConstruct`.** `ScoutingIntelBroker` creates the channel in its `@PostConstruct` by calling `ChannelService.create()`, which is `@Transactional`. The CDI proxy hasn't been created yet when `@PostConstruct` runs, so the transaction annotation does nothing — the call runs outside a transaction and the channel creation fails silently. The fix is `QuarkusTransaction.requiringNew().call(() -> ...)` — programmatic transaction, no proxy needed. This is an Arc limitation, not specific to qhorus, but it bites here.

**Qualified CDI beans are invisible to unqualified `Instance<T>` lookups.** `DroolsTacticsTask` carries `@CaseType("starcraft-game")`, which removes `@Default`. The qhorus `MessageService` discovers observers via `Instance<MessageObserver>` without `@Any`, so `DroolsTacticsTask` would never receive messages. We needed a `TacticsMessageBridge` — a plain `@ApplicationScoped` bean with no extra qualifiers that implements `MessageObserver` and delegates to `DroolsTacticsTask`. Same problem applied to `ScoutingIntelBroker`'s `Instance<ScoutingIntelConsumer>`, which needed `@Any` added. Filed as qhorus#259.

All four failed silently. No compile errors, no startup warnings — just observers that never fired and channels that never created.

## What's worth keeping from the design

The subscription model held up well. `ScoutingIntelBroker` collects the union of all `ScoutingIntelConsumer.subscribedIntelTypes()` declarations at startup. `DroolsScoutingTask` checks `broker.isSubscribed(ScoutingIntelType.BUILD_ORDER)` before running Drools CEP — if nothing subscribes to build order detection, the entire CEP block is skipped. At game-loop speed that matters.

The `@Observes GameStarted` cache reset in `DroolsTacticsTask` turned out to be the right call beyond just testing. Without it, cross-game state would bleed into the next match. The cache clears when a new game starts — production-correct, not a test hack.

Scouting still writes `NEAREST_THREAT` to the CaseFile for observability (QA endpoints, visualizer). Tactics no longer reads it. Issue #179 tracks eventual deprecation once we verify no other code touches that key.
