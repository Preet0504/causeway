---
description: Re-run candidate discovery on an already-mined repo with a bigger window, without touching existing bugs
argument-hint: <runId> --window 24m
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Widen window: $ARGUMENTS

**Dedup first, always.** Call `graph_bug_list` for this run's repository and collect every
`fixSha` already recorded — admitted, rejected, or still undecided. This set must be excluded
from anything the wider window returns; the whole point of this command is adding new candidates,
never re-adjudicating or duplicating ones already processed.

**Run S2-S3 as `/mine-init` specifies**, with the new, wider `--window`, dispatching
`mining-strategist` again. Pass it the excluded-shas set explicitly and instruct it to report
candidates outside that set only. The random control net still runs, over the newly-widened
portion of history only — recomputing it over the whole window would double-count the original
sample.

**Do not proceed past S3 automatically.** New candidates need adjudication like any others; hand
off to `/mine-adjudicate` rather than silently running the rest of the pipeline for them here.

---

## What this did
How many new candidates were found beyond the excluded set, per net, and the new K.

## Next
```
Run: /mine-adjudicate <runId>
```
This will adjudicate BOTH any candidates from the original run that are still pending AND the
newly-found ones together — say which is which in the phase's own report when you get there.
