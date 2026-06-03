# Design: Remove Dead MAP_CENTER Threat Fallbacks — #170

## What and Why

`DroolsTacticsTask.dispatch()` contains two ternary null-checks on `threat`:

```java
// ATTACK
Point2d target = targets.getOrDefault(tag, threat != null ? threat : MAP_CENTER);
// MOVE_TO_ENGAGE
Point2d target = threat != null ? threat : MAP_CENTER;
```

These are dead since the NEAREST_THREAT gate (`entryCriteria` + `canActivate` override) guarantees
`NEAREST_THREAT` is present in the CaseFile before `execute()` runs. `threat` is read from that key
with no default that could produce null when the key is present — so both null branches are
unreachable.

## What Changes

```java
// ATTACK — after
Point2d target = targets.getOrDefault(tag, threat);

// MOVE_TO_ENGAGE — after
Point2d target = threat;
```

## What Stays

`MAP_CENTER` constant is retained — it has two remaining legitimate uses:

- `RETREAT` case: `.orElse(MAP_CENTER)` — fallback when Nexus is destroyed (unrelated to threat)
- `dispatchDefend()`: same

These are Nexus-position fallbacks, not threat-position fallbacks. Out of scope for this issue.

## Testing

`dispatch()` is private; behaviour is tested via `execute()` in `DroolsTacticsTaskIT`. Verify
no existing IT asserts `MAP_CENTER` as the intent target under normal (Nexus-present, threat-present)
conditions. If coverage of the threat→target path is missing, add a focused unit scenario that
confirms the intent target matches `threat`, not `MAP_CENTER`.
