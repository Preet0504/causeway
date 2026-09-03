---
description: Queue mining across a list of repositories, one after another
argument-hint: <repo-url-1> <repo-url-2> ... [--window 12m] [--cap 60] [--deep 5]
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Mine batch: $ARGUMENTS

Run `/mine-repo` for each URL given, in order, one at a time — never in parallel. This project's
own history includes a real failure mode of overlapping containerized builds fighting over the
same resources; running repositories sequentially is deliberate, not a missed optimization.

Before starting each repository, call `graph_resume_state` for it; if an incomplete run already
exists, resume that one via the appropriate phase command rather than starting a duplicate.

If one repository's ecosystem is unsupported or its build recipe never resolves, record that
outcome for it and move on to the next URL rather than stopping the whole batch — a batch is
explicitly for cases where some repositories in the list may not work out.

---

## What this did
A table: repository, outcome (completed / stopped at which stage / unsupported), bug count and
observed-path fraction for anything that reached `/mine-finalize`.

## Related
```
/list-runs                         — every run this batch produced, individually resumable
/inspect-bugs <runId>               — the bugs from any one repository in the batch
```
