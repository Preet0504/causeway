---
description: Clone a repo, let the user choose its remote and branch, and extract every commit within a window as evidence, via JGit.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini repo-inspection flow. Nothing about which remote or which branch to mine is assumed, both are discovered and shown to the user, who chooses. This command needs a validated GitHub repo URL and a mining window up front, and asks for a scan commit limit only after the user has picked a remote and branch and can see how many commits actually fall in the window.

## 1. Gather repo and window

If this conversation already has a validated repo URL and window (e.g. from a `/run_causeway` run earlier in this session), use those directly and skip to step 2.

Otherwise, gather them the same way `/run_causeway` does:
- Ask for the repo URL, verbatim: "What is the url of the repo?" Validate it against GitHub (`curl -s -o /dev/null -w "%{http_code}" "https://api.github.com/repos/<owner>/<repo>"`, must return `200`), re-asking up to 3 total attempts on failure, same as `/run_causeway` step 2.
- Explain what "window" means (how far back into commit history to look for candidate commits), then offer: Past 1 month / Past 3 months / Past 6 months / Other.

## 2. Discover and choose the remote

Run:

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --mode list-remotes"
```

This clones the repo (or opens an existing clone already on disk) and prints one `REMOTE name=<name> url=<url>` line per remote configured there. A fresh clone always has exactly one (`origin`, pointing at exactly the URL given); a reused directory could have more, for example a fork's `origin` pointing at the fork and `upstream` pointing at the original repository, or a directory that turns out to be a clone of something else entirely.

- If exactly one remote is listed, state it plainly to the user (name and URL) and proceed with it, there's nothing to choose between.
- If more than one is listed, show all of them (name and URL each) and ask the user, via multiple choice, which one to treat as the source of truth for this run. Don't guess which one is "right", the whole point is that the tool can't know that.

Remember the chosen remote's name, you'll need it in every step below.

If the command fails, report the failure plainly and stop.

## 3. Discover and choose the branch

Run:

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --mode list-branches --remote-name <chosen-remote>"
```

This fetches from the chosen remote and prints one `BRANCH name=<name> sha=<sha> isDefault=<true|false>` line per branch GitHub reports for that repository.

Tell the user which branch GitHub reports as the default, and ask directly, in plain text, which branch they'd like to mine (a repo can have many branches, this is not a small enough set for multiple-choice, list at most a handful of names for reference alongside the default and mention there are more if there's a long tail). If they don't express a preference, use the default branch.

Remember the chosen branch's name and its SHA (from the matching `BRANCH` line), you'll need both below.

If the command fails, report the failure plainly and stop.

## 4. Resolve the window to a concrete date

Convert the chosen window into an ISO `since-date` yourself, using today's date:
- Past 1 month → today minus 1 month
- Past 3 months → today minus 3 months
- Past 6 months → today minus 6 months
- Other → do your best to resolve it to a concrete ISO date (a duration like "past 2 weeks" is exact; something like "since v2.0" is approximate, note in your final report if you had to approximate)

Also produce a filesystem-safe slug of the window label: lowercase, spaces replaced with hyphens (e.g. `past-3-months`). Every tool call below breaks if the label contains raw spaces or quotes, always pass the slug, never the original text.

## 5. Preview the commit count

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --mode count --remote-name <chosen-remote> --branch-name <chosen-branch> --branch-sha <chosen-branch-sha> --since-date <iso-date> --window-label <slug>"
```

This is a cheap preview from the exact commit already chosen in step 3, it writes nothing. Read `WINDOW_COMMIT_COUNT` from stdout.

If the command fails, report the failure plainly and stop, don't guess at a number.

## 6. Ask for the scan commit limit

Tell the user how many commits fall within the chosen window (the `WINDOW_COMMIT_COUNT` you just got), then ask directly, in plain text: how many of these commits should be scanned in detail (fetching their GitHub context and code changes) in the next step? This is not asking how many bugs to find, it's just how many commits to carry forward for closer inspection, since none of them have been examined for being a bug fix yet. Accept whatever number they give.

## 7. Extract and write the evidence file

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --mode write --remote-name <chosen-remote> --branch-name <chosen-branch> --branch-sha <chosen-branch-sha> --since-date <iso-date> --window-label <slug> --scan-commit-limit <limit>"
```

This does not fetch again, it trusts the exact commit already chosen in step 3. It writes every commit within the window, full commit message, author, committer, dates, parent SHAs, plus the chosen remote, branch, SHA, and the scan commit limit, to a JSON evidence file under `workspace/exports/`.

Read the tool's stdout for:
- `WINDOW_COMMIT_COUNT`, should match step 5's preview
- `EVIDENCE_FILE`, path to the JSON evidence file
- `RUN_ID`, the run's identifier

If the command fails, or doesn't print an `EVIDENCE_FILE` line, report the failure plainly to the user, don't claim success.

If the tool behaves unexpectedly right after a code change to it, the sbt background server may be stale, run `sbt -batch shutdown` once, then retry.

## 8. Final report

Produce one final message stating, plainly:
- The repo (`owner/repo`)
- The remote chosen (name and URL), and the branch chosen (name and its pinned SHA, 10 characters is fine)
- The window chosen, and the resolved since-date (note if it was an approximation)
- How many commits fall within the window
- The scan commit limit chosen
- Where the evidence file was written

Then tell the user to run `/inspect_commits` next, which will fetch each commit's linked GitHub PRs/issues and its JGit diff content.

End the message with: "If anything above is wrong, just run /inspect_repo again to start over."
