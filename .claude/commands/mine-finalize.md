---
description: Phase 6 of 6 — metrics, the narrative report, and the researcher-facing dataset
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`, and never read or modify this project's own source. If something
feels like it needs raw file access, call the matching causeway tool instead.

Finalize run: $ARGUMENTS

This is phase 6 of 6, terminal — nothing follows it in the pipeline. **Before starting**,
resolve the repository and check state: `graph_resume_state` REQUIRES `repoUrl`, not just a
runId — call `graph_list_runs` first, find this runId's `repo`, then call `graph_resume_state`
with that repoUrl and this runId together. If S10-S11 are not `DONE`, STOP and tell the user to
run `/mine-trace` first.

**S12 — Metrics.** `metrics_churn`, `metrics_complexity_delta`, `metrics_coverage_impact`,
`metrics_bug_lifetime` over the admitted set. These are pure functions over what is already
persisted; nothing here is agent judgment.

**S13 — Report.** Present this as your response to the user, in your own words — there is no
file-writing tool for a narrative, because a narrative summary is not deterministic work with
one right rendering:

- candidates examined per net, adjudication precision, estimated recall from the control net
- bugs admitted vs rejected, with the `rejectedFor` breakdown
- reproducer tier distribution (T1/T2/T3/T4) and path tier/fidelity distribution
- **fraction of path steps observed rather than static** — the headline number
- Unknown breakdown by reason, in full — the dataset's honesty report

Every figure must come from a tool response or a graph query. If a stage produced nothing, say
so plainly rather than omitting it.

**S14 — Dataset.** `export_dataset`, once, with this runId. Writes `bugs.csv`, `path_hops.csv`,
`findings.csv`, `evidence.csv`, `run_metadata.json`, and a generated `CODEBOOK.md` under
`workspace/exports/<runId>/dataset/`. Report the directory and file list from the tool's
response, not from memory — the tool call is the only source for what it actually wrote.

---

## What this phase did
The full S13 report, plus confirmation that `export_dataset` wrote all six files with their
paths and sizes.

## Look deeper
```
/inspect-bugs <runId>              — the final state of every bug
/generate-report <runId>           — re-print this same narrative summary later, any time
/cite-bug <runId> <fixSha>         — a paste-ready citation for one specific bug
```

## Next
The pipeline is complete for this run. There is no further phase command to run. If you want to
extend the deep set, use `/promote-bug`; if you want to widen the candidate window on the same
repository, use `/widen-window`.
