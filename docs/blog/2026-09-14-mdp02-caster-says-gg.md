---
layout: post
title: "When the Caster Says GG But Nobody Died"
date: 2026-09-14
entry_type: note
subtype: diary
projects: [casehubio/quarkmind]
tags: [sc2, dataset, nlp, whisper, alignment]
---

# When the Caster Says GG But Nobody Died

The commentary dataset pipeline produced 235 training examples from 30 IEM10 games. That number started at 49, went to 236 after the segmenter fix, and came down by one after I filtered out a segment where the entire commentary was "bye" — a caster signoff at the end of the Grand Final.

The interesting work was the offset spot-check. Each game in a multi-game VOD needs a game-start timestamp — an offset into the YouTube video where the in-game clock begins. The estimator uses Whisper-transcribed caster commentary to find these: load cues ("we're loaded into game number two") and end cues ("GG") are matched against replay durations to triangulate the offset.

Ten of the thirty games had low-confidence offsets. I expected most to be close enough. They were not.

The worst case was Polt vs Soulkey, a Bo5 quarterfinal. Game 5's estimated offset placed it 594 seconds past the end of the video. The game literally could not exist at that timestamp. Claude traced the cascade: a false "that's it" match at 45:58 — the caster saying "maybe that's it" about Zerg biology, not about the game ending — anchored Game 3's backward estimate wrong, which propagated through interpolation into Games 4 and 5.

Four bugs surfaced in the estimator:

The regex `loaded\s+.*game\s+(\w+)` was supposed to capture the game number from "loaded into prion terraces for game number two." It captured "number" instead of "two" — the greedy `.*` consumed everything up to "game", then `(\w+)` grabbed the next word. Since "number" isn't in the word-to-number map, the load cue was silently dropped. Every "game number N" reference with intervening text was invisible to the estimator.

The pattern `takes\s+(?:it|the\s+match|the\s+win)` matched "takes the wind out of hero's sails" because `win` is a prefix of `wind`. A missing `\b` word boundary.

The `that's it` end pattern was optional on the "and" prefix — `(?:and\s+)?that's it`. Every conversational "maybe that's it" and "but that's it" matched as a game-ending call. Making "and" required fixed it: "and that's it, GG" still matches; "maybe that's it" about zergling evolution does not.

And the overlap adjuster would push games forward without checking VOD bounds, creating physically impossible offsets. If the only valid position for a game is past the end of the video, the offset should be discarded and re-interpolated, not accepted.

After the fixes: the herO vs sOs semifinal G4 moved by 432 seconds, G5 by 698 seconds. Both now align with the caster's explicit "game number four" and "game number five" load announcements. The Polt vs Soulkey G5 offset dropped from 3980 to 3380 — fitting within the 4354-second video with 6 seconds to spare.

The dataset now has 4 high-confidence offsets (up from 2), 18 medium, and 8 low. The remaining lows are all mathematically plausible — well-bounded by anchors, no overruns. Phase 1 is done: 235 validated examples across 30 games, 54 tests, all 7 validation checks passing. Phase 2 will automate the VOD matching and scale to the full SC2EGSet — 72 tournament archives on Zenodo, most with ESL YouTube broadcasts available.
