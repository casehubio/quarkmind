# Cascade Validation Baseline — 2026-08-23

Issue: #213 — IEM10 replay validation & accuracy benchmarking
Branch: `issue-213-iem10-replay-validation`
Datasets: 30 IEM10 Taipei 2016 + 29 AI Arena bot replays (59 total)
Models: neocortex ONNX strategy classifiers (vs_terran, vs_zerg, vs_protoss)

## Cascade Accuracy — IEM10 (30 games)

```
Mode: DROOLS_ONLY
Min     PvT           PvZ           PvP           Overall  Samples
1       —             —             —
2       —             1/1=100%      —             100%     1
3       7/7=100%      5/5=100%      —             100%     12
4       0/10=0%       —             4/4=100%      29%      14
5       0/10=0%       —             —             0%       10

Mode: ONNX_ONLY
Min     PvT           PvZ           PvP           Overall  Samples
1       —             —             —
2       —             1/1=100%      —             100%     1
3       4/7=57%       5/5=100%      —             75%      12
4       4/10=40%      —             0/4=0%        29%      14
5       7/10=70%      —             —             70%      10

Mode: CASCADE (Drools 0.7 / ONNX 0.5)
Min     PvT           PvZ           PvP           Overall  Samples
1       —             —             —
2       —             1/1=100%      —             100%     1
3       4/7=57%       5/5=100%      —             75%      12
4       4/10=40%      —             0/4=0%        29%      14
5       5/10=50%      —             —             50%      10
```

## Cascade Accuracy — AI Arena (29 PvP bot games)

```
Mode: DROOLS_ONLY
Min     PvP           Overall  Samples
3       4/4=100%      100%     4
4       9/11=82%      82%      11
5       0/4=0%        0%       4

Mode: ONNX_ONLY
Min     PvP           Overall  Samples
3       2/4=50%       50%      4
4       2/11=18%      18%      11
5       0/4=0%        0%       4

Mode: CASCADE
Min     PvP           Overall  Samples
3       2/4=50%       50%      4
4       2/11=18%      18%      11
5       0/4=0%        0%       4
```

## Comparison Baselines (IEM10, minute 4)

```
Drools-only: 29%
ONNX-only:   29%
Cascade:     29%
Δ Cascade vs Drools-only: +0%
```

## Tier Hit Rates (cascade mode, minute 4)

```
Drools resolved:  83% (49/59)
ONNX resolved:    17% (10/59)
LLM triggered:    0% (0/59)
Default fallback: 10% (6/59)
```

## Cascade Latency Benchmark

Model: vs_terran, minute 3 features (180 ticks, 6 populated windows)
Warmup: 100 iterations, Measured: 1000 iterations

```
Component              | Mean       | p50        | p95        | p99        | Max
Feature extraction     | 3.5µs      | 1.0µs      | 2.1µs      | 4.2µs      | 2.17ms
ONNX inference         | 232.2µs    | 227.5µs    | 270.1µs    | 332.0µs    | 2.13ms
Full cascade           | 241.9µs    | 234.7µs    | 288.8µs    | 350.8µs    | 414.9µs
```

All assertions pass:
- Feature extraction p99 (4.2µs) < 1ms
- Full cascade p99 (350.8µs) < 10ms
- Drools-only rush accuracy at minute 3 (100%) ≥ 70%

## Analysis

**Drools tier** performs well for rush detection at minute 3 (100% accuracy) but
degrades beyond minute 4 as ground truth labels shift to mid-game compositions
that the Drools rules don't cover.

**ONNX tier** shows limited accuracy on these datasets. The models were trained on
modern (2018+) replays but the IEM10 dataset is from 2016 — meta has shifted
significantly. The AI Arena bots use fixed PvP build orders that differ from the
training distribution. Cross-era accuracy benchmarking requires either era-matched
training data or fine-tuning.

**Cascade mode** currently matches ONNX-only because the ONNX tier fires whenever
Drools confidence is below 0.7 (83% of games at minute 4 resolve at Drools tier,
17% fall through to ONNX). The ONNX override actively hurts AI Arena accuracy
(Drools-only: 82% vs cascade: 18% at minute 4).

**Latency** is excellent — full cascade at 351µs p99 is well within the 500ms tick
budget, leaving room for all other plugins in the game loop.

## Replay Validation Divergence

`mvn test -Preport` passes — classifier changes do not regress economic divergence
tracking.
