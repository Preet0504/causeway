---
description: Enrich each commit in an evidence file with its GitHub PRs/issues and JGit diff hunks.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini commit-enrichment flow. This adds, to each commit already extracted by `/inspect_repo`, the pull requests and issues GitHub associates with it, and the old/new-side diff hunks from JGit.

## 1. Find the evidence file

If this conversation already knows the evidence file path from a `/inspect_repo` run earlier in this session, use it directly.

Otherwise, ask the user for the path to the evidence file `/inspect_repo` produced (or list `workspace/exports/*.json` — excluding any `*_enriched.json` files — and ask them to confirm which run to enrich if more than one exists).

## 2. Make sure GITHUB_TOKEN is available

This step calls GitHub's GraphQL API, which requires authentication. Before running the tool, source the repo's `.env` file so `GITHUB_TOKEN` is set in the shell:

```bash
set -a && . ./.env && set +a
```

## 3. Run the enrichment tool

From the repository root:

```bash
tools/causeway inspect-commits --evidence-file <path-to-evidence-file>
```

This does two things, both bounded by the evidence file's own `scanCommitLimit` (only that many of the window's commits, the most recent ones, get enriched; if `scanCommitLimit` is null, every window commit is enriched):
- Fetches PRs and their closing issues for every commit in one batched GraphQL request per ~20 commits (not one request per commit).
- Diffs every non-merge commit against its first parent using JGit, in parallel across a local thread pool (no network involved) — merge commits are recorded with `isMergeCommit: true` and no diff, since a merge's diff against any one parent doesn't represent the merge's own work.

Read the tool's stdout for:
- `ENRICHED_COUNT` — how many commits got enriched
- `ENRICHED_FILE` — path to the new evidence file (the original from `/inspect_repo` is left untouched)
- Up to three `SAMPLE sha=... prCount=... diffFileCount=... isMergeCommit=...` lines — use these directly for the final report's examples, don't re-read the file just to find them

If the command fails, or doesn't print an `ENRICHED_FILE` line, report the failure plainly — don't guess at numbers or claim success. A GraphQL request that fails for one batch is logged as a warning to stderr and that batch's commits simply get no PR/issue data (empty list) rather than aborting the whole run — mention this if you see such a warning.

`tools/causeway` rebuilds automatically the first time it's run after a source change, every other invocation runs the already-compiled code directly with no sbt involved. If something still behaves unexpectedly right after a code change, delete `target/causeway-classpath.txt` to force a fresh rebuild on the next call.

## 4. Store the enrichment in the SQLite catalog

```bash
tools/causeway store --file <enriched-file>
```

This upserts this run, plus every enriched commit's PRs, issues, and issue-commit links, into `workspace/causeway.db`, linked back to the `/inspect_repo` run that discovered these commits via the shared `runId`. It won't overwrite the author/committer data already stored for these commits (the enriched file doesn't carry that data itself).

If this fails, report it plainly but don't treat it as blocking, the enriched file itself is still valid and `/classify_bugs` only reads that.

## 5. Final report

Produce one final message stating, plainly:
- How many commits were enriched, out of how many were in the window
- Where the new enriched evidence file was written
- Three example commits from the `SAMPLE` lines, each showing: its short sha, how many PRs were found, how many files it touched in its diff, and whether it was a merge commit (and therefore skipped for diffing)

Then tell the user to run `/classify_bugs` next, which will score each commit on whether it's a genuine bug fix.

End the message with: "If anything above is wrong, just run /inspect_commits again to start over."
