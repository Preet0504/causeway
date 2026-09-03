---
description: Try obtaining a reproducer for one specific bug, on demand
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Reproduce: $ARGUMENTS

Call `graph_bug_detail`. This command assumes the bug already has a `symptomEntryPoint` — S9
already ran for it, either because it was in the original deep set or via `/promote-bug`. If
`symptomEntryPoint` is absent, STOP and say so: this bug has no symptom characterization yet, so
there is nothing for a reproducer to target. Suggest `/promote-bug <runId> <fixSha>` instead,
which does the full S9-S11 treatment for a bug outside the deep set.

If a `reproducerTier` already exists and it is not T4, ask before re-attempting — a T1 or T2
result is already the highest-fidelity answer this ladder produces, and re-running it spends a
container cycle to very likely get the same answer.

Otherwise walk the same ladder `/mine-trace` uses, exactly as specified there: native test first
if the fix shipped one, then a stack trace via `repro_botsing`, then the synthesist (budget 3
iterations) if neither applies, then `repro_evosuite_sweep`, then T4 if nothing reproduces.
Persist the result: `graph_upsert_bug` with `{runId, record: {repo, fixSha, reproducerTier}}` —
the tool takes `runId` and a `record` object, not these fields at the top level.

---

## What this did
The tier reached, and — if T1 or T2 — the channel it differed on and the traceId.

## Related
```
/inspect-bug <runId> <fixSha>      — see this result in the bug's full detail
```

## Next
```
If this bug now has a trace, its path may have changed tier too:
  dispatch path-tracer for it directly, or re-run /mine-trace for the whole deep set.
```
