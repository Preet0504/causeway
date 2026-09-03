---
description: The deep dive on one bug — symptom, fault location, reproducer, full path
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Inspect: $ARGUMENTS

Call `graph_bug_detail` with the runId and fixSha. If it returns `Unknown(NotApplicable)`, that
means this fixSha was never adjudicated for this run at all — say so, and suggest `/inspect-bugs`
to see what does exist, rather than presenting an empty report as if it were a real bug with
nothing found.

Present, in this order, omitting any section whose fields are entirely absent rather than
padding it with "not yet available":

1. **Identity.** repo, fixSha, parentSha (if known), admitted / admittedReason.
2. **Fault location.** oldRanges and newRanges, stated as "check out parentSha, these are the
   lines" — this is the section that answers "can I reproduce this in my own checkout".
3. **Verdict.** the adjudicator's rationale, in full, not truncated.
4. **Symptom.** class, description, entry point, and any gaps the characterizer recorded.
5. **Reproducer.** tier, test selector if T1, what differed and how.
6. **Path.** tier, fidelity, then every hop in order — from, to, relation, carrier, whether it
   was executed. If the tier is DISCONNECTED, say plainly that no route exists and why, rather
   than presenting a partial path as though it reached the fault.
7. **Provenance.** finding count and evidence count, so the reader knows how much is behind this.

---

## What this showed
One sentence naming the bug and its current furthest stage.

## Related
```
/verify-bug <runId> <fixSha>       — confirm every claim here actually cites real evidence
/explain-gap <runId> <fixSha>      — plain-language reasons for anything absent above
/cite-bug <runId> <fixSha>         — a paste-ready citation for this bug
/reproduce-bug <runId> <fixSha>    — (re-)attempt reproduction if the tier is T4 or absent
```
