---
description: The full narrative summary of a run, callable any time — not only at the end
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Report on: $ARGUMENTS

This is exactly `/mine-finalize`'s S13 step, made callable on its own, at any point in a run —
useful for checking progress mid-run, not only for the final summary. `graph_resume_state`
REQUIRES `repoUrl`, not just a runId — call `graph_list_runs` first to resolve this runId's
`repo`, then call `graph_resume_state`, `graph_bug_list`, and `graph_bug_detail` as needed to
assemble:

- candidates examined per net, adjudication precision, estimated recall from the control net
  (absent if S2-S4 have not run)
- bugs admitted vs rejected, with the `rejectedFor` breakdown (absent if S6 has not run)
- reproducer tier distribution and path tier/fidelity distribution (absent if S10-S11 have not run)
- **fraction of path steps observed rather than static** — the headline number, if computable
- Unknown breakdown by reason, in full

If a stage has not run yet, state that plainly for that section rather than omitting it or
filling it with zeros that would read as a measured absence of Unknowns rather than the stage
never having executed.

---

## Related
```
/inspect-bugs <runId>              — the raw per-bug list behind these numbers
/export-dataset <runId>            — write bugs.csv and the rest to disk, at whatever state the run is in
```
