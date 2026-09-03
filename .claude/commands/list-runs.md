---
description: Every mining run you've started, which repo, and how far it got
argument-hint: (no arguments)
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

List runs.

Call `graph_list_runs`. Render as a table: runId, repository, last touched, complete or the
furthest stage reached, bug count, admitted count. If there are none, say so plainly rather than
printing an empty table.

---

## What this showed
The count and a one-line summary of the most recent run, if any.

## Related
```
/resume [runId]                    — continue an incomplete one
/inspect-bugs <runId>              — the bugs from a specific run
/generate-report <runId>           — the full narrative summary of a completed one
```
