---
layout: post
title: "From One Model to Many"
date: 2026-10-13
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [onnx, classifier, architecture, sc2]
---

# From One Model to Many

QuarkMind's strategy classifier has always been a single ONNX model per matchup — one neural net trying to answer "what is the opponent doing?" across all game phases, all strategy archetypes, all situations. Three models (vs Terran, vs Zerg, vs Protoss), each classifying into 7-9 fine-grained archetypes.

It doesn't work. Not because the architecture is wrong, but because the training data can't support it. 403 tournament replays split across 9 classes produces extreme imbalance — in vs_protoss, 331 of 395 training samples are PROXY. The model learns to predict the majority class and calls it 92% accuracy. A coarse experiment collapsing to 5 classes showed the same pattern: headline numbers above 90%, but the model is just voting for the dominant class in every matchup.

The real insight came from questioning why we'd want one universal classifier at all.

## Phase-specific detectors

An SC2 game has distinct phases with distinct signals. A rush happens in the first three minutes — the features that matter are worker count, expansion timing, building placement. A tech transition happens at five to seven minutes — the features that matter are army composition and tech building timing. Training one model across all phases forces it to learn features that are temporally irrelevant to each other. A rush detector doesn't need to know about Colossus counts.

The alternative: a library of small binary ONNX detectors, each answering one question during one phase. "Is this a rush?" at two minutes. "Is there an air tech transition?" at five minutes. "Is there a composition pivot?" at eight minutes. Each model is tiny, trains on far more data (every non-rush replay is a negative example), and runs only when relevant.

## Multiple detectors, simultaneously

The next step was realising these don't need to run one at a time. Multiple detectors can be active simultaneously, selected by the current game state. Drools sees a Spawning Pool — it activates the rush detector and the ling-bane detector. Both run. Their confidences feed into strategy selection as a signal vector, not a single archetype prediction.

This maps directly onto the YAML playbook coordination patterns that just landed with epic #600. The `race:` step — first-to-complete alternation — is exactly "run rush detector and proxy detector concurrently, act on whichever fires first." Temporal absence patterns express "no expansion by three minutes — assume aggression" without any ML at all. The orchestration primitives (channels, deadline scopes, spawned tasks with latches) handle concurrent detector execution with timeout safety.

## CBR learns which detectors to trust

The architecture gets interesting when you record the detector chains. Each game becomes a case: which detectors fired, when, with what confidence, what strategy was chosen, what the outcome was. CBR retrieves similar past games and suggests which detector combinations are productive. Over time, those cases become training data for a meta-model — an ONNX that learns the detector selection pattern directly, without the retrieval step.

The selector and aggregator are both pluggable. Start with heuristic rules. Add CBR when game data exists. The meta-ONNX is a future evolution that the architecture supports without rework.

## What this opens up

I filed #411 for the full detector library architecture. The design decisions are captured — hybrid decomposition (phase gates which detectors run, each detector is situation-specific), tiered feature sets (economy, composition, spatial, full), YAML orchestration with Drools evidence accumulation, pluggable selection and aggregation with a meta-learning loop.

The relabelling work from #410 feeds directly into this. The building-timing rules in `rules.py` provide the training labels. The reconstitution pipeline produces the feature vectors. What changes is what gets trained on them — many small focused models instead of one large unfocused one.
