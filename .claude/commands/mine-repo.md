---
description: Mine a public GitHub repository end to end, unattended, all six phases
argument-hint: <github-url> [--window 12m] [--cap 60] [--deep 5] [--resume <runId>]
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`, and never read or modify this project's own source. If something
feels like it needs raw file access, call the matching causeway tool instead.

Mine bug paths from: $ARGUMENTS

This is the unattended, no-pauses version of the pipeline. It runs the same six phases a person
can also run one at a time — `/mine-init`, `/mine-adjudicate`, `/mine-admit`, `/mine-select`,
`/mine-trace`, `/mine-finalize` — back to back, in this order, with no checkpoint stops between
them. Follow each phase's own instructions exactly as that command states them; this file adds
nothing to what they already do, it only removes the pause.

If you want to see intermediate results as they land, or want to stop and resume later at a
natural boundary, use the six phase commands directly instead of this one — that is what they
are for.

If `--resume <runId>` is given, resolve its repository first — `graph_resume_state` REQUIRES
`repoUrl`, not just a runId — by calling `graph_list_runs` and finding this runId's `repo`, then
call `graph_resume_state` with both and start at whichever phase covers the first non-`DONE`
stage, rather than repeating work already complete. `/resume` does
this same detection more conveniently if you do not already know the runId.

When all six phases finish, present the S13 report exactly as `/mine-finalize` specifies, and
report the dataset files `/mine-finalize`'s S14 step wrote. Do not summarize the whole run
differently here — one canonical report shape, produced by one phase, regardless of which
command triggered it.
