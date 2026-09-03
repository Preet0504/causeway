---
description: Phase 2 of 6 — decide which candidates are genuine bug fixes, locate the fault
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. You do NOT have `Read`,
`Glob`, `Grep`, `Edit`, `Write`, or `Bash`, and must never read or modify this project's own
source. If something feels like it needs raw file access, call the matching causeway tool
instead.

Adjudicate candidates for run: $ARGUMENTS

This is phase 2 of 6. **Before doing anything else**, resolve the repository and check state:
`graph_resume_state` REQUIRES `repoUrl`, not just a runId, and this command only receives a
runId as its argument — so first call `graph_list_runs`, find this runId's `repo`, then call
`graph_resume_state` with that repoUrl and this runId together. (Found by actually running this
stage: passing only the runId is rejected by the schema before the handler ever sees it.)
If S0-S3 are not `DONE`, STOP and tell the user to run `/mine-init` first — do not attempt to
reconstruct candidates from anywhere else.

**S4 — Adjudication.** Partition K into batches of ~15. Dispatch `bugfix-adjudicator` in waves
of at most 5 concurrent. Control candidates stay interleaved into the same batches as everyone
else, never adjudicated separately — the adjudicator must not be able to treat them differently.
Each verdict is `BUG_FIX`, `NOT_BUG_FIX`, or `UNDECIDED`, recorded via `findings_record`.
`UNDECIDED` is a real answer; forcing a binary choice on a genuinely ambiguous commit pollutes
the dataset invisibly. F := candidates verdicted `BUG_FIX`.

**S5 — Structure.** For each f in F: `history_diff`, `jvm_method_at_line` on old-side ranges,
`szz_baseline`. Deterministic, no agent. SZZ runs for every f — blame needs no build, so bugs
that fail the build gate next phase still carry real lineage data.

**Persist as you go, per bug, right after S5 computes it — do not wait for a later phase:**
call `graph_upsert_bug` with `{runId, record: {repo, fixSha, parentSha, oldRanges, newRanges}}`
for every f — the tool takes `runId` and a `record` object, not these fields at the top level.
This is what lets `/inspect-bug` show a user exactly which lines to look at, and what a fresh
`/mine-admit` invocation reads to find the parent revision to build. Every field on `record` is
optional and merges into whatever an earlier phase already wrote — this call only sets what S5
determined, nothing else.

`oldRanges`/`newRanges` are arrays of `{file, start, end}`. Never swap them: `oldRanges` is
where the fault lives, in the PARENT revision's coordinates; `newRanges` is where the fix lives,
in the FIX revision's coordinates. Querying the parent with a new-side line number returns a
different, unrelated line — plausibly, and with nothing to signal the mistake.

---

## What this phase did
State: candidates adjudicated, the verdict breakdown (BUG_FIX / NOT_BUG_FIX / UNDECIDED),
adjudication precision on K, and separately the control net's fix rate with what it implies
about the unmatched stratum. Every number from a tool response, never estimated.

## Look deeper
```
/inspect-bugs <runId>              — every candidate's verdict, one line each
/inspect-bug <runId> <fixSha>      — one bug's fault location and rationale in full
```

## Next
```
Run: /mine-admit <runId>
```
