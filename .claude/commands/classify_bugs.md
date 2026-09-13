---
description: Ask how many bug fixes to find, then work through an enriched evidence file's commits in batches until that target is hit.
---

Run the Causeway Mini bug-classification flow. This reads an `/inspect_commits` enriched evidence file, has `bug-classifier` subagents score commits in batches of 5, and you (the orchestrator) render a verdict per commit, stopping as soon as you've found enough genuine bug fixes.

## 1. Find the enriched evidence file

If this conversation already knows the enriched evidence file path from an `/inspect_commits` run earlier in this session, use it directly.

Otherwise, run:

```bash
tools/causeway list-runs --stage inspect-commits --limit 4
```

This is a pure database read, no network call, no writes, don't run any SQL of your own here. Read each `RUN run_id=... owner=... repo=... window=... since=... createdAt=... sourceFile=...` line. If `SHOWN=1`, just use that one's `sourceFile`. If more than one, use the AskUserQuestion tool, header `"Evidence file"`, one option per run labeled `owner/repo (window)` (not the bare filename, that tells you nothing), most recent first, to ask which to use, the tool's custom-answer option still lets the person name a `sourceFile` path directly. If `SHOWN=0`, tell the user plainly that no `/inspect_commits` run has been recorded yet and to run that first.

## 2. Ask for the bug target

Tell the user how many commits are in the evidence file's `commits` array (its `enrichedCommitCount`). Then use the AskUserQuestion tool, header `"Bug target"`, to ask how many genuine bug fixes they'd like to find, at most (call this the bug target). Build 3 preset options scaled to `enrichedCommitCount` (e.g. roughly a fifth, roughly half, and all of them, capped at something reasonable if the count is large), the same way `/inspect_repo`'s scan-limit question scales its own presets. The tool's automatic custom-answer option covers any exact number.

This is a different question from the scan commit limit asked back in `/inspect_repo`. That one bounded how many raw commits got looked at closely. This one bounds how many of those, once actually classified, should count as accepted results.

## 3. Split commits into batches of 5

Read the enriched file's `commits` array (already sorted newest first). Split it into consecutive batches of exactly 5 commits each (the last batch may have fewer).

For each commit, you only need these fields for the subagent: `sha`, `shortMessage`, `fullMessage`, `pullRequests`, `diff`. Use a short Python snippet (or equivalent) to read the file and print each batch's slice as its own JSON array, this avoids retyping or mistranscribing commit data by hand.

## 4. Work through batches in order, stopping once you hit the target

Process batches one at a time, in order (newest commits first), not in parallel. Keep a running count of how many commits you've judged to be genuine bug fixes so far, starting at 0.

For each batch, in turn:

1. Invoke the `bug-classifier` agent (`subagent_type: "bug-classifier"`) with that batch's JSON array pasted directly into the prompt. Tell it plainly how many commits are in this batch and to return one scored object per commit, in order. Run it in the foreground, you need its result before deciding whether to continue.
2. If the agent call fails outright, or returns something that doesn't parse as the expected JSON array, report that plainly for the affected commits (don't fabricate scores) and treat them as not classified rather than guessing.
3. For each commit in this batch, read its three scores and three explanations (message, diff, PR/issue) yourself and decide: is this a genuine bug fix, yes or no? This is your judgment call, not a formula, a commit with a mediocre message score but a very convincing diff and a linked bug-report issue can still be a clear bug fix, and vice versa. Write one or two sentences of rationale for your verdict, referencing whichever signal(s) actually drove your decision.
4. Add this batch's genuine-bug-fix count to your running total.
5. If the running total is now at or above the bug target, you're done, don't process any more batches. If this batch's own bug fixes pushed you past the target (e.g. target was 3, this batch brought you from 1 to 4), keep only your most confident ones from this batch, by your own judgment of which are the clearest bug fixes, until you're exactly at the target. Mark the rest of this batch's genuine bug fixes as found but not counted toward the target, don't discard their scores or your rationale, just note plainly that they were extra.
6. If the running total is still below the target and batches remain, continue to the next batch.
7. If you run out of batches before reaching the target, stop there, that evidence file simply doesn't contain that many genuine bug fixes among its scanned commits.

## 5. Show the user a table

Render a markdown table, one row per commit you actually examined (not commits you never got to), with columns: short sha (first 10 chars), message score, diff score, PR score, verdict (bug fix, not a bug, or extra meaning found but past the target), and a brief reason.

## 6. Write the final JSON file

Write a new file next to the enriched evidence file (same directory, same base name with `_enriched` replaced by `_classified`, e.g. `run_<id>_classified.json`), containing:
- The same top-level `runId`/repo/window/repoSnapshot/scanCommitLimit fields as the enriched file, carried over exactly as-is (`runId` in particular must be carried forward unchanged, it's how this run gets linked back to its `/inspect_commits` and `/inspect_repo` runs later)
- `bugTarget`
- `examinedCommitCount` (how many commits you actually classified before stopping)
- `scannedCommitCount` (the evidence file's `enrichedCommitCount`, for comparison)
- `stoppedEarly` (true if you stopped before working through every batch)
- A `classifications` array, one entry per examined commit: `sha`, `shortMessage`, `messageScore`, `messageExplanation`, `diffScore`, `diffExplanation`, `prScore`, `prExplanation`, `verdict` (boolean), `verdictRationale`, `countsTowardTarget` (boolean, true only for the accepted bug fixes up to the target)

## 7. Store the classifications in the SQLite catalog

```bash
tools/causeway store --file <classified-file>
```

This upserts this run and every examined commit's classification into `workspace/causeway.db`, linked back to the `/inspect_commits` run via the shared `runId`.

If this fails, report it plainly but don't treat it as blocking, the classified file itself is still valid and is Causeway Mini's final output.

## 8. Final report

After the table, state plainly:
- How many commits you examined, out of how many were available to scan
- How many genuine bug fixes you found, against the target (if you found fewer than the target even after examining every scanned commit, say so plainly, e.g. "found 2 of your target 5, that's every genuine bug fix among the 40 commits scanned")
- If you stopped early, say so, and note that only the most recent examined commits were looked at, the rest of the scanned commits were never classified
- Where the final JSON file was written

Tell the user this is as far as Causeway Mini currently goes, there's no next command yet.

End the message with: "If anything above is wrong, just run /classify_bugs again to start over."
