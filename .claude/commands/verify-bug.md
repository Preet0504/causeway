---
description: A self-service integrity check on one bug, before you cite it anywhere
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Verify: $ARGUMENTS

Call `graph_bug_detail`. Check, and report each explicitly:

- **`findingCount` and `evidenceCount` are both greater than zero for an admitted bug.** Zero
  evidence on an admitted bug would mean nothing here is actually checkable, which should not be
  possible given `findings_record` refuses claims with no citation — if you see it, say so as a
  genuine anomaly, not a quiet pass.
- **`pathHopCount` matches the number of hops actually listed**, if a path exists.
- **Every hop carries at least one evidence id.** A hop with none is not backed by anything
  retrievable, regardless of what its `relation` field claims.
- **If `pathTier` is OBSERVED, `pathObservedSteps` should equal `pathHopCount`** — a hop marked
  unobserved inside an OBSERVED-tier path is a contradiction worth surfacing, not silently
  averaging away.

State plainly what this check can and cannot confirm: it verifies structural completeness (every
claim cites something, counts are internally consistent) using what `graph_bug_detail` already
returns. It does NOT re-fetch and re-verify each evidence payload's actual byte content against
its recorded hash — that would need a dedicated evidence-read tool that does not exist yet. Say
this limitation explicitly rather than implying a deeper check was performed.

---

## What this checked
Pass or fail on each item above, plus the stated limitation.

## Related
```
/inspect-bug <runId> <fixSha>      — the full record this check was run against
/cite-bug <runId> <fixSha>         — if verification passed, a citation ready to use
```
