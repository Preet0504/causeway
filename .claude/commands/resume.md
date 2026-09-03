---
description: Continue whatever mining run you were last working on, without having to remember which phase
argument-hint: [runId, optional]
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Resume: $ARGUMENTS

**If a runId was given**, `graph_resume_state` REQUIRES `repoUrl` — it does not accept a runId
alone, even though it also takes one. Call `graph_list_runs`, find this runId's `repo`, then
call `graph_resume_state` with that repoUrl and this runId together.

**If no runId was given**, call `graph_list_runs`. If there are none, tell the user there is
nothing to resume and suggest `/mine-init`. If there is exactly one incomplete run, use it. If
there is more than one incomplete run, do NOT guess which one the user means — list them all with
their repo, last-seen time, and current stage, and stop, asking the user to name one explicitly
or run `/list-runs` for the full picture.

**Once a runId is settled**, map its furthest `DONE` stage to a phase command, using this table:

```
S0-S3  done, S4-S5 not     → run /mine-adjudicate <runId>
S4-S5  done, S6-S7 not     → run /mine-admit <runId>
S6-S7  done, S8-S9 not     → run /mine-select <runId>
S8-S9  done, S10-S11 not   → run /mine-trace <runId>
S10-S11 done, S12-S14 not  → run /mine-finalize <runId>
S12-S14 done               → the run is complete; nothing to resume
```

State which stage the run reached and which phase command covers what comes next, then actually
invoke that phase command yourself rather than only naming it — the whole point of this command
is removing the step of reading a suggestion and copying it.

---

## What this did
Name the run, its repository, the furthest completed stage, and which phase command you then
ran on the user's behalf.

## Next
Whatever the invoked phase command's own closing block says — do not print a second, different
one here.
