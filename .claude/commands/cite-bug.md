---
description: A ready-to-paste citation block for one bug — repo, commit, permalink, path summary
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Cite: $ARGUMENTS

Call `graph_bug_detail`. Produce exactly this block, filled in, with nothing invented for a
field that came back absent — write "not determined" rather than guessing:

```
Repository:    <repo>
Fix commit:    <fixSha>   https://github.com/<repo>/commit/<fixSha>
Parent commit: <parentSha, or "not determined">
Changed lines: <old_ranges, or "not determined">

Symptom:  <symptomClass> — <one-sentence description>
Fault:    <symptomEntryPoint> -> ... -> the fault site, <pathHopCount> hops, tier <pathTier>
Evidence: <findingCount> findings, <evidenceCount> retrieved payloads
```

Follow it with one paragraph of plain-English summary suitable for pasting directly into a
paper or report, grounded only in fields that are actually present.

If `pathTier` is HYPOTHESIZED or the bug has no path at all, add a one-line caveat noting this
route is not execution-confirmed, since citing it without that caveat would overstate what is
known.

---

## Related
```
/verify-bug <runId> <fixSha>       — confirm this bug's claims are structurally sound first
```
