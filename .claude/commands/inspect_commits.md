---
description: Enrich each commit in an evidence file with its GitHub PRs/issues and JGit diff hunks.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini commit-enrichment flow. This adds, to each commit already extracted by `/inspect_repo`, the pull requests and issues GitHub associates with it, and the old/new-side diff hunks from JGit.

## 1. Find the evidence file

If this conversation already knows the evidence file path from a `/inspect_repo` run earlier in this session, use it directly.

Otherwise, run:

```bash
tools/causeway list-runs --stage inspect-repo --limit 4
```

This is a pure database read, no network call, no writes, don't run any SQL of your own here. Read each `RUN run_id=... owner=... repo=... window=... since=... createdAt=... sourceFile=...` line. If `SHOWN=1`, just use that one's `sourceFile`. If more than one, use the AskUserQuestion tool, header `"Evidence file"`, one option per run labeled `owner/repo (window)` (not the bare filename, that tells you nothing), most recent first, to ask which to enrich, the tool's custom-answer option still lets the person name a `sourceFile` path directly. If `SHOWN=0`, tell the user plainly that no `/inspect_repo` run has been recorded yet and to run that first.

## 2. Run the enrichment tool

From the repository root:

```bash
tools/causeway inspect-commits --evidence-file <path-to-evidence-file>
```

This does two things, both bounded by the evidence file's own `scanCommitLimit` (only that many of the window's commits, the most recent ones, get enriched; if `scanCommitLimit` is null, every window commit is enriched):
- Fetches PRs and their closing issues for every commit in one batched GraphQL request per ~20 commits (not one request per commit).
- Diffs every non-merge commit against its first parent using JGit, in parallel across a local thread pool (no network involved) — merge commits are recorded with `isMergeCommit: true` and no diff, since a merge's diff against any one parent doesn't represent the merge's own work.

This tool also stores the enrichment into `workspace/causeway.db` itself right after writing the enriched file, no separate step needed, linked back to the `/inspect_repo` run that discovered these commits via the shared `runId`.

Read the tool's stdout for:
- `ENRICHED_COUNT` — how many commits got enriched
- `ENRICHED_FILE` — path to the new evidence file (the original from `/inspect_repo` is left untouched)
- Up to three `SAMPLE sha=... prCount=... diffFileCount=... isMergeCommit=...` lines — use these directly for the final report's examples, don't re-read the file just to find them
- `DATABASE`, printed once the catalog write succeeds. If it's missing and a `WARN: could not store the enriched file in the database` line appears on stderr instead, mention that plainly but don't treat it as blocking, the enriched file itself is still valid and `/classify_bugs` only reads that.

If the command fails, or doesn't print an `ENRICHED_FILE` line, report the failure plainly — don't guess at numbers or claim success. A GraphQL request that fails for one batch is logged as a warning to stderr and that batch's commits simply get no PR/issue data (empty list) rather than aborting the whole run — mention this if you see such a warning.

`tools/causeway` rebuilds automatically the first time it's run after a source change, every other invocation runs the already-compiled code directly with no sbt involved. If something still behaves unexpectedly right after a code change, delete `target/causeway-classpath.txt` to force a fresh rebuild on the next call.

## 3. Final report

Produce one final message stating, plainly:
- How many commits were enriched, out of how many were in the window
- Where the new enriched evidence file was written
- Three example commits from the `SAMPLE` lines, each showing: its short sha, how many PRs were found, how many files it touched in its diff, and whether it was a merge commit (and therefore skipped for diffing)

Then tell the user to run `/classify_bugs` next, which will score each commit on whether it's a genuine bug fix.

End the message with: "If anything above is wrong, just run /inspect_commits again to start over."
