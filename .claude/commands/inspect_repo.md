---
description: Clone a repo, let the user choose its remote and branch, and extract every commit within a window as evidence, via JGit.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini repo-inspection flow. Nothing about which remote or which branch to mine is assumed, both are discovered and shown to the user, who chooses. Asks for a scan commit limit only after the user has picked a remote and branch and can see how many commits actually fall in the window.

## 1. Gather repo and window

If a repo URL is already known from earlier in this session (e.g. picked from a `/discover_repos` run), use it directly, don't re-ask. Otherwise ask, verbatim: "What is the url of the repo?"

If a window is already known from earlier in this session, use it directly. Otherwise use the AskUserQuestion tool, header `"Window"`, question along the lines of "How far back should Causeway Mini look for candidate bug-fixing commits?", with these options:
- **Past 1 month** — description: "Fastest to mine, but may miss older bugs."
- **Past 3 months** — description: "A reasonable default for most repos."
- **Past 6 months** — description: "Finds more history, but takes longer to mine."

Don't add a fourth "Other" option yourself, the tool already offers one automatically for anything else (e.g. "past 2 weeks", "since v2.0", "all time").

## 2. Discover and choose the remote

Run:

```bash
tools/causeway inspect-repo --repo-url <repo-url> --mode list-remotes
```

This clones the repo (or opens an existing clone already on disk) and prints one `REMOTE name=<name> url=<url>` line per remote configured there. There's no separate step to check the URL is real first, this clone attempt is the check: a malformed or nonexistent repo URL fails right here. A fresh clone always has exactly one remote (`origin`, pointing at exactly the URL given); a reused directory could have more, for example a fork's `origin` pointing at the fork and `upstream` pointing at the original repository.

- If exactly one remote is listed, state it plainly to the user (name and URL) and proceed with it, there's nothing to choose between.
- If more than one is listed, use the AskUserQuestion tool, header `"Remote"`, one option per remote (label the remote's name, description its URL), asking which to treat as the source of truth for this run. If there are more than 4 remotes (rare), pick the 3 most likely candidates as options (`origin` and `upstream` first if present) and note plainly in your question text that others exist by name, the person can still name one directly since the tool always offers a custom-answer option too.

Remember the chosen remote's name, you'll need it in every step below.

If the command fails, that means the URL couldn't be resolved as a real GitHub repository. Tell the user plainly, ask again for the URL (keep the already-chosen window), and retry this step, up to 3 total attempts. If it still fails after 3 attempts, stop the flow and tell the user plainly.

## 3. Discover and choose the branch

Run:

```bash
tools/causeway inspect-repo --repo-url <repo-url> --mode list-branches --remote-name <chosen-remote>
```

This fetches from the chosen remote and prints one `BRANCH name=<name> sha=<sha> isDefault=<true|false>` line per branch GitHub reports for that repository.

Use the AskUserQuestion tool, header `"Branch"`, to ask which branch to mine. A repo can have many branches, more than the tool's 4-option limit, so build the options list as: the default branch first (label it clearly as the default), then up to 3 more of the most relevant others (recently-touched branches are a reasonable choice if you have no better signal, otherwise just the next few from the `list-branches` output). Mention in the question text itself that more branches exist beyond what's shown, and that the person can type any branch name directly, the tool's automatic custom-answer option covers that, you don't need to enumerate every branch as an option. If they don't express a preference, use the default branch.

Remember the chosen branch's name and its SHA (from the matching `BRANCH` line), you'll need both below.

If the command fails, report the failure plainly and stop.

## 4. Resolve the window to a concrete date

Convert the chosen window into an ISO `since-date` yourself, using today's date:
- Past 1 month → today minus 1 month
- Past 3 months → today minus 3 months
- Past 6 months → today minus 6 months
- Anything else (a custom answer, e.g. "past 2 weeks", "since v2.0", "all time") → do your best to resolve it to a concrete ISO date (a duration like "past 2 weeks" is exact; something like "since v2.0" is approximate, note in your final report if you had to approximate)

Also produce a filesystem-safe slug of the window label: lowercase, spaces replaced with hyphens (e.g. `past-3-months`). Every tool call below breaks if the label contains raw spaces or quotes, always pass the slug, never the original text.

## 5. Preview the commit count

```bash
tools/causeway inspect-repo --repo-url <repo-url> --mode count --remote-name <chosen-remote> --branch-name <chosen-branch> --branch-sha <chosen-branch-sha> --since-date <iso-date> --window-label <slug>
```

This is a cheap preview from the exact commit already chosen in step 3, it writes nothing. Read `WINDOW_COMMIT_COUNT` from stdout.

If the command fails, report the failure plainly and stop, don't guess at a number.

## 6. Ask for the scan commit limit

Tell the user how many commits fall within the chosen window (the `WINDOW_COMMIT_COUNT` you just got). Then use the AskUserQuestion tool, header `"Scan limit"`, to ask how many of these commits should be scanned in detail (fetching their GitHub context and code changes) in the next step — this is not asking how many bugs to find, it's just how many commits to carry forward for closer inspection, since none of them have been examined for being a bug fix yet.

Build 3 preset options scaled to `WINDOW_COMMIT_COUNT`, not a fixed list that might not fit (offering "20/50/100" when only 8 commits are in the window would be useless): pick small/medium/all-of-them style values that make sense relative to the actual count, e.g. roughly a fifth, roughly half, and the full count if it's a reasonable single batch (cap the "all of them" option if the count is very large, a few hundred, so nobody accidentally commits to scanning an unreasonable number in one go). The tool's automatic custom-answer option covers any exact number that doesn't match a preset.

## 7. Extract and write the evidence file

```bash
tools/causeway inspect-repo --repo-url <repo-url> --mode write --remote-name <chosen-remote> --branch-name <chosen-branch> --branch-sha <chosen-branch-sha> --since-date <iso-date> --window-label <slug> --scan-commit-limit <limit>
```

This does not fetch again, it trusts the exact commit already chosen in step 3. It writes every commit within the window (sha, short/full message, commit date, parent shas), plus the chosen remote, branch, SHA, and the scan commit limit, to a JSON evidence file under `workspace/exports/<owner>-<repo>/json/`, then stores that same data into `workspace/causeway.db` itself, no separate step needed. Running this again, for the same repo and window, updates existing rows rather than duplicating them.

Read the tool's stdout for:
- `WINDOW_COMMIT_COUNT`, should match step 5's preview
- `EVIDENCE_FILE`, path to the JSON evidence file
- `RUN_ID`, the run's identifier
- `DATABASE`, printed once the catalog write succeeds. If it's missing and a `WARN: could not store the evidence file in the database` line appears on stderr instead, mention that plainly but don't treat it as blocking, the evidence file itself is still valid and `/inspect_commits` only reads that.

If the command fails, or doesn't print an `EVIDENCE_FILE` line, report the failure plainly to the user, don't claim success.

`tools/causeway` rebuilds automatically the first time it's run after a source change (you'll see "source changed, rebuilding ..." on stderr), every other invocation runs the already-compiled code directly with no sbt involved. If something still behaves unexpectedly right after a code change, delete `target/causeway-classpath.txt` to force a fresh rebuild on the next call.

## 8. Final report

Produce one final message stating, plainly:
- The repo (`owner/repo`)
- The remote chosen (name and URL), and the branch chosen (name and its pinned SHA, 10 characters is fine)
- The window chosen, and the resolved since-date (note if it was an approximation)
- How many commits fall within the window
- The scan commit limit chosen
- Where the evidence file was written
- The run id (`RUN_ID` from step 7's output), stated plainly as its own line, e.g. "Run ID: `<id>`, save this if you want to come back to this exact run later via `tools/causeway list-runs --run-id <id>`"

Then tell the user to run `/inspect_commits` next, which will fetch each commit's linked GitHub PRs/issues and its JGit diff content.

End the message with: "If anything above is wrong, just run /inspect_repo again to start over."
