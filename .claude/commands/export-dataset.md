---
description: Write bugs.csv, path_hops.csv, findings.csv, evidence.csv, run_metadata.json and the codebook, right now
argument-hint: <runId>
allowed-tools: Task, mcp__causeway__*
---

While this command is active you have ONLY the tools listed above.

Export dataset for: $ARGUMENTS

Call `export_dataset` directly. This is the same tool `/mine-finalize`'s S14 step calls, exposed
standalone so it can be run mid-run to check partial progress, not only once at the very end —
`export_dataset` handles absent later-stage fields the same way `/inspect-bugs` does, as an
honest gap rather than an error, so exporting early is always safe.

Report the directory and every file written, with byte sizes, directly from the tool's response
— never describe the files' contents from memory or from a prior export, since a second call can
have written different numbers than the first.

---

## What this did
The directory, the six files, and the bug/admitted counts from the tool's response.

## Related
```
/generate-report <runId>           — the same numbers as prose instead of files
/cite-bug <runId> <fixSha>         — a citation for one bug from this export
```
