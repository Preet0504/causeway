---
description: Phase 4 of 6 — rank bugs and characterize what each symptom looked like
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`, and never read or modify this project's own source. If something
feels like it needs raw file access, call the matching causeway tool instead.

Rank and characterize symptoms for: $ARGUMENTS

This is phase 4 of 6. **Before starting**, resolve the repository and check state:
`graph_resume_state` REQUIRES `repoUrl`, not just a runId — call `graph_list_runs` first, find
this runId's `repo`, then call `graph_resume_state` with that repoUrl and this runId together.
If S6-S7 are not `DONE`, STOP and tell the user to run `/mine-admit` first.

**S8 — Ranking.** `rank_importance` over coverage of changed lines, blast radius, and symptom
distance. Each dimension is scored independently; the composite is over Known dimensions ONLY —
an Unknown dimension is excluded, never coerced to zero. Record `scoredOn` alongside the score:
a composite over three dimensions and one over seven are not comparable, and without `scoredOn`
the number looks like it is. If the composite is degenerate (many bugs tied at the same score
because only one dimension was ever Known), say so rather than presenting a false ranking — take
the deep set on a documented alternative criterion instead, and name what it is.

**Persist per bug:** `graph_upsert_bug` with `{runId, record: {repo, fixSha, importance,
scoredOn}}` for every bug in F_b, immediately after ranking, not batched to the end — the tool
takes `runId` and a `record` object, not these fields at the top level.

**S9 — Symptom.** Dispatch `symptom-characterizer` over the selected deep set, waves of at most
5. Its `entryPoint` output is what `/mine-trace` aims reproduction at and what path length gets
measured from — it does not explain the cause, only what was observed and where.

---

## What this phase did
State: the ranking dimensions actually Known, whether the composite was usable or degenerate and
what selection criterion was used if it was, the deep-set size, and each selected bug's symptom
class. Every number from a tool response.

## Look deeper
```
/inspect-bugs <runId>              — importance and symptom class for every bug
/inspect-bug <runId> <fixSha>      — one bug's full symptom description and entry point
```

## Next
```
Run: /mine-trace <runId>
```
