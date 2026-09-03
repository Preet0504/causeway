---
description: Self-service repair of a stranded checkpoint, without hand-written Cypher
argument-hint: <runId> <stage> <key>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Fix checkpoint: $ARGUMENTS

This exists for exactly the failure mode documented in this project's own history: a stage
failed under one key, succeeded under a different key on retry, and the failed row sat there
forever counting as outstanding work — every later resume re-did work that was actually
complete, because `graph_resume_state` counts every non-`DONE` row.

`graph_resume_state` REQUIRES `repoUrl`, not just a runId — call `graph_list_runs` first to
resolve this runId's `repo`, then call `graph_resume_state` with that repoUrl and this runId,
and show the user the exact checkpoint rows for this stage — every key, its status. Do NOT guess
which row is the stranded one; ask the user to confirm which
key is broken and what actually happened to it, since marking the wrong row would hide a
genuine failure instead of a stale one.

Once confirmed, there are exactly two legitimate outcomes, and both are explicit, deliberate
calls — never a default:

- **The work under this key genuinely finished**, just recorded under the wrong key. Call
  `graph_checkpoint` with `status: DONE` and the SAME key, so the row reflects reality.
- **The work under this key failed and was superseded by a later, successful attempt under a
  different key.** Call `graph_checkpoint` on the successful key with
  `supersedes: ["<broken key>"]` — this marks the broken row without deleting it, preserving it
  as a true record of what happened (the same "never delete, always supersede" rule bugs
  themselves follow).

Never mark a sibling bug's genuine failure as superseded just because another bug in the same
batch succeeded — a stage with one key per bug is supposed to show some DONE and some FAILED.

---

## What this did
Which row was fixed, how, and the resulting `resumeState` for this stage — confirm it now reads
correctly before ending.

## Next
```
Run: /resume <runId>
```
