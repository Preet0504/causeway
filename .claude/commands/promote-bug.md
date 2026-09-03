---
description: Push one admitted bug outside the original deep set through symptom, reproducer, and path
argument-hint: <runId> <fixSha>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above. No `Read`, `Glob`, `Grep`,
`Edit`, `Write`, or `Bash`.

Promote: $ARGUMENTS

For someone browsing `/inspect-bugs` who sees a bug that never made the ranked deep set but
looks worth the full treatment anyway. Runs S9 through S11 for exactly this one bug, the same
work `/mine-select` and `/mine-trace` do for the deep set, applied to a set of one.

Call `graph_bug_detail` first. If `admitted` is not true, STOP — a bug that failed the build gate
cannot be reproduced or traced regardless of how interesting it looks; there is nothing S9-S11
can do with it.

**S9.** Dispatch `symptom-characterizer` for this bug alone. Persist nothing new here; the
symptom finding itself is the record.

**S10.** Walk the reproducer ladder exactly as `/mine-trace` specifies. Persist
`{repo, fixSha, reproducerTier}`.

**S11.** Dispatch `path-tracer` for this bug alone. Persist `{repo, fixSha, pathFidelity}`.

---

## What this did
The symptom class, reproducer tier, and path tier this bug ended up at — the same shape
`/mine-trace`'s closing block reports, for one bug instead of the whole deep set.

## Related
```
/inspect-bug <runId> <fixSha>      — the full detail, same as any other traced bug now
```

## Next
```
Nothing further is required. This bug is now on equal footing with the original deep set.
```
