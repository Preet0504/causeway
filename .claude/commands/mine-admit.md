---
description: Phase 3 of 6 — the hard build gate, coverage, and call graphs
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`, and never read or modify this project's own source. If something
feels like it needs raw file access, call the matching causeway tool instead.

Run the build gate for: $ARGUMENTS

This is phase 3 of 6, and the most expensive one — everything here runs in a container. **Before
starting**, resolve the repository and check state: `graph_resume_state` REQUIRES `repoUrl`, not
just a runId — call `graph_list_runs` first, find this runId's `repo`, then call
`graph_resume_state` with that repoUrl and this runId together. If S4-S5 are not `DONE`, STOP
and tell the user to run `/mine-adjudicate` first.

**S6 — Build gate (HARD).** For each f in F: `jvm_build_probe` on parent(f) and on f, each in
its own worktree, in containers. Both must succeed for the bug to be admitted. A failure is
classified as one of `DependencyResolution`, `JdkMismatch`, `MissingToolchain`, `CompileError`,
`Timeout`.

**Persist the result per bug, immediately, whichever way it goes:** call `graph_upsert_bug` with
`{runId, record: {repo, fixSha, admitted: true}}` on success, or `{runId, record: {repo, fixSha,
admitted: false, rejectedFor: <classification>}}` on failure — the tool takes `runId` and a
`record` object, not these fields at the top level. **Rejected bugs are recorded, never dropped**
— deleting them would hide the dataset's own selection bias behind a cleaner-looking file. F_b :=
bugs admitted.

**S7 — Coverage and call graph.** For f in F_b only: `jvm_coverage_run` at parent(f),
`jvm_callgraph_build` at parent(f). Coverage of the changed lines is queried with the **old-side**
ranges you persisted last phase — never the new-side ones, and never against the fix revision.
The server pools these; do not try to parallelize them yourself, and do not build more than two
call graphs before checking they are still queryable — the cache is bounded.

---

## What this phase did
State: how many of F were admitted vs rejected, the `rejectedFor` breakdown in full (a rejection
class with zero entries is worth saying explicitly, not omitting), and coverage of changed lines
across the admitted set. Every number from a tool response.

## Look deeper
```
/inspect-bugs <runId>              — admitted/rejected status for every bug
/inspect-bug <runId> <fixSha>      — one bug's coverage and build detail
```

## Next
```
Run: /mine-select <runId>
```

If F_b is empty, say so plainly and do not suggest `/mine-select` — there is nothing for it to
rank.
