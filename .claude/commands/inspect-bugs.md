---
description: Every bug in a run, one line each — works at any point, not only when finished
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

List bugs for: $ARGUMENTS

Call `graph_bug_list`. Render as a table: fix SHA (short), admitted, verdict, symptom class,
reproducer tier, path tier, path fidelity, importance. A bug that has not reached a later stage
yet shows blank cells for those columns — that is the honest state, not an error, and do not
fill blanks with a guess or a dash implying "not applicable" when the real reason is "not
reached yet".

If the run has not started adjudication (S4), the list is empty by definition; say that plainly
rather than showing an empty table with no explanation.

---

## What this showed
The count, how many admitted, and the tier distribution if any bugs have reached that far.

## Related
```
/inspect-bug <runId> <fixSha>      — full detail on one of these
/reproduce-bug <runId> <fixSha>    — try reproducing one that came back T4 or was never attempted
/generate-report <runId>           — the full narrative summary instead of a raw list
```
