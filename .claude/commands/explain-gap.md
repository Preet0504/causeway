---
description: Why is this field empty? A plain-language answer instead of digging through findings.csv yourself
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Explain gaps for: $ARGUMENTS

Call `graph_bug_detail`. For every field that is absent rather than populated, state the reason
in one plain sentence, not the raw `UnknownReason` token alone:

- `admittedReason` present → the build gate rejected it; name the classification and what it
  means in practice (for example `DependencyResolution`: an old dependency version has vanished
  from public repositories).
- `symptomGaps` non-empty → walk each `reason:detail` pair from `symptom-characterizer` and
  restate it in plain terms.
- `reproducerTier` absent → this bug's symptom was never characterized (not in the deep set,
  never promoted) or the reproducer ladder has not run yet for it.
- `pathTier` absent → S11 has not run for this bug, for the same two possible reasons.
- `pathTier` = DISCONNECTED → this is not a gap at all; state plainly that this is a positive
  finding about the defect's shape (it propagates through state rather than a call edge), not a
  failure to determine something.

If nothing is actually absent, say so — do not manufacture an explanation for a field that is
simply populated.

---

## Related
```
/inspect-bug <runId> <fixSha>      — the full record these gaps are drawn from
/reproduce-bug <runId> <fixSha>    — if the gap is a missing reproducer, close it directly
/promote-bug <runId> <fixSha>      — if the gap is a missing symptom/path entirely
```
