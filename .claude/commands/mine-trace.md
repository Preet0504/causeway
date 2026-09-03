---
description: Phase 5 of 6 — obtain a reproducer and trace the symptom-to-fault path
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`, and never read or modify this project's own source. If something
feels like it needs raw file access, call the matching causeway tool instead.

Reproduce and trace paths for: $ARGUMENTS

This is phase 5 of 6, and the core deliverable — everything before it exists to get here.
**Before starting**, resolve the repository and check state: `graph_resume_state` REQUIRES
`repoUrl`, not just a runId — call `graph_list_runs` first, find this runId's `repo`, then call
`graph_resume_state` with that repoUrl and this runId together. If S8-S9 are not `DONE`, STOP
and tell the user to run `/mine-select` first.

**S10 — Reproducer.** Walk the ladder per bug, cheapest rung first:

1. Fix added a regression test → `jvm_native_test_run` with that test's selector. Runs the
   project's own test at BOTH revisions; it should fail at parent(f) and pass at f. → **T1
   NATIVE**, and the parent side is instrumented, returning a `traceId` that makes an OBSERVED
   path possible next. This is the cheapest rung and the only one that yields a reproducer
   nobody had to synthesize; try it for every fix touching a test file. Do NOT use
   `jvm_harness_run` for this — it compiles plain `javac` and cannot run a JUnit test.
2. Symptom carries a stack trace → `repro_botsing` → **T2**
3. Otherwise → dispatch `reproducer-synthesist`, budget 3 iterations → **T2** or **T3**
4. Fall through → `repro_evosuite_sweep` with differential filter
5. Nothing reproduces → **T4 STATIC**

Only rung 3 costs an agent. Do not dispatch the synthesist for a bug whose reproducer came free.

**Persist per bug:** `graph_upsert_bug` with `{runId, record: {repo, fixSha, reproducerTier}}` as
soon as each bug's tier is decided — the tool takes `runId` and a `record` object, not these
fields at the top level.

**S11 — Path.** Dispatch `path-tracer`, one per admitted bug in the deep set, waves of at most
4. Both endpoints are known by construction — this is pathfinding between two fixed points, not
a search for an unknown fault. With a trace, work in the intersection of the static call graph
and what actually executed, and tier the result OBSERVED. Without one, the path is drawn from
the graph alone and MUST be tiered HYPOTHESIZED. A route with no call edge at all is
DISCONNECTED — record that as the finding it is, never bridge it with a plausible guess.

**Persist per bug:** `graph_upsert_bug` with `{runId, record: {repo, fixSha, pathFidelity}}`.

---

## What this phase did
State: the reproducer tier distribution across the deep set, path tier distribution (OBSERVED /
HYPOTHESIZED / DISCONNECTED), and **the fraction of path steps observed rather than static** —
this is the headline number of the whole run. Every figure from a tool response.

## Look deeper
```
/inspect-bugs <runId>              — reproducer tier and path tier for every bug
/inspect-bug <runId> <fixSha>      — one bug's full path, hop by hop, with evidence
/reproduce-bug <runId> <fixSha>    — try a bug outside the deep set that came back T4
```

## Next
```
Run: /mine-finalize <runId>
```
