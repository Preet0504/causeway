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

Ranking runs in **two stages**, not one, because two of the three dimensions cannot be Known
before S9 runs. `symptomDistance` obviously needs S9's `entryPoint` — it IS the distance from
that point. `blastRadius` needs it too, for a less obvious but equally hard reason: this
codebase is a library with no `main()`, so `jvm_callgraph_build` REQUIRES declared entry points
and refuses an unrestricted whole-program build (`NotApplicable — "No main method is present"`).
A graph built from a handful of generic entry points (the library's own parsing constructors,
say) silently omits any part of the code those roots never reach — an accessor method like
`getBigInteger` that no constructor calls will show **zero callers**, not because it truly has
none, but because the analysis never explored that part of the program. That looked, at first,
like blast radius was independent of S9 and only `symptomDistance` was not; live testing this
design found the opposite — found by using the library's default parsing entry points for a
fault site that is only ever reached through a *reading* accessor, and getting `callers: []` for
a method with two real, direct callers. Both dimensions need a graph rooted at a REAL usage path
into the fault site, and S9's `entryPoint` is the only thing that reliably supplies one.

**S8a — Initial ranking.** `rank_importance` over `faultLineCoverage` only — the one dimension
genuinely available with no per-bug context. If this composite is degenerate (many bugs tied
because only one dimension was ever Known), say so.

Take the **candidate superset** S_c := the top `min(|F_b|, 10)` bugs by this initial ranking —
wider than the final deep set on purpose, so the bugs S9 spends its budget on are not yet
final. **If the rank-10 cutoff falls inside a tie, widen S_c to the whole tie group rather than
cutting mid-tie.** Coverage is frequently Unknown for several bugs at once (a pure addition has
no old-side lines to measure; some lines are legitimately not independently executable), and
those bugs tie at NO composite, not a low one — nothing distinguishes which of them "deserves"
the cut, so keeping some and dropping others is not a ranking decision, it's an alphabetical
accident of `rank_importance`'s own tiebreak. Found live: with 5 of 13 bugs tied at no
composite, a flat top-10 cut kept 2 of the 5 and dropped 3 — including two bugs already known,
from earlier work on this exact repository, to have excellent, fully OBSERVED paths. Name the
cutoff, how many bugs it included, and whether the tie rule widened it past 10.

**S9 — Symptom.** Dispatch `symptom-characterizer` over S_c (the superset, not F_b and not yet
the final deep set), waves of at most 5. Its `entryPoint` output is what `/mine-trace` aims
reproduction at, and what both remaining ranking dimensions are about to be measured from — it
does not explain the cause, only what was observed and where.

**S8b — Final ranking.** For each bug in S_c, build (or reuse, if still resident — the cache is
bounded to two, and by now something else has likely built a graph since) a call graph at
`parent(f)` with S9's `entryPoint` as an explicit root, alongside the library's usual entry
points rather than instead of them. From that graph: `jvm_callgraph_callers` on the fault-site
method for `blastRadius`, and `jvm_callgraph_paths` from `entryPoint` to the fault-site method
for `symptomDistance` (the returned path length). An empty `jvm_callgraph_paths` result is a
real DISCONNECTED answer, not an error — record it as Unknown for that bug, never as a guessed
distance. Re-run `rank_importance` over S_c with all three dimensions now supplied wherever
Known. Take the top `min(|S_c|, 5)` bugs by this final ranking as the deep set that `/mine-trace`
will actually work on.

**Persist per bug, once, after S8b:** `graph_upsert_bug` with `{runId, record: {repo, fixSha,
importance, scoredOn}}` for every bug in S_c — not just the final deep set, so a bug that
S8b's ranking dropped still carries the fuller score it earned, rather than reverting to S8a's
partial one. The tool takes `runId` and a `record` object, not these fields at the top level.

**Checkpoint under stage `S8_RANKING`** for both passes, distinguished by `key` (e.g.
`"initial"` and `"final"`), not by two different stage names — `/mine-trace`, `/resume`, and
`/promote-bug` all check `S8-S9 DONE` as a precondition by that exact stage string, and a new
stage name here would silently strand them. Checkpoint `S9_SYMPTOM` after S9 the same way as
always.

---

## What this phase did
State both ranking passes separately, since they ran over different dimensions and different
bug sets: S8a's composite and superset cutoff (which bugs made S_c, and by what score), S9's
symptom class per bug in S_c, and S8b's final composite and `scoredOn` for the deep set actually
selected. If either composite was degenerate, say so for that pass specifically — S8a being
degenerate does not imply S8b is too, and the reverse. Every number from a tool response.

## Look deeper
```
/inspect-bugs <runId>              — importance and symptom class for every bug
/inspect-bug <runId> <fixSha>      — one bug's full symptom description and entry point
```

## Next
```
Run: /mine-trace <runId>
```
