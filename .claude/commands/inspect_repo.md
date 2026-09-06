---
description: Clone a repo and extract every commit within a window as evidence, via JGit.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini repo-inspection flow. This command needs two inputs up front — a validated GitHub repo URL and a mining window — and asks for a third, the bug cap, only after showing how many commits actually fall in that window.

## 1. Gather repo and window

If this conversation already has a validated repo URL and window (e.g. from a `/run_causeway` run earlier in this session), use those directly and skip to step 2.

Otherwise, gather them the same way `/run_causeway` does:
- Ask for the repo URL, verbatim: "What is the url of the repo?" Validate it against GitHub (`curl -s -o /dev/null -w "%{http_code}" "https://api.github.com/repos/<owner>/<repo>"`, must return `200`), re-asking up to 3 total attempts on failure — same as `/run_causeway` step 2.
- Explain what "window" means (how far back into commit history to look for candidate bug-fixing commits), then offer: Past 1 month / Past 3 months / Past 6 months / Other.

Do not ask for a bug cap yet.

## 2. Resolve the window to a concrete date

Convert the chosen window into an ISO `since-date` yourself, using today's date:
- Past 1 month → today minus 1 month
- Past 3 months → today minus 3 months
- Past 6 months → today minus 6 months
- Other → do your best to resolve it to a concrete ISO date (a duration like "past 2 weeks" is exact; something like "since v2.0" is approximate — note in your final report if you had to approximate)

Also produce a filesystem-safe slug of the window label: lowercase, spaces replaced with hyphens (e.g. `past-3-months`). Every tool call below breaks if the label contains raw spaces or quotes — always pass the slug, never the original text.

## 3. Preview the commit count

From the repository root, run:

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --since-date <iso-date> --window-label <slug> --count-only true"
```

This clones the repo (or reuses an existing clone already on disk) under `workspace/<owner>-<repo>/` and walks its commit history from HEAD, but writes nothing — it's a cheap preview. Read `WINDOW_COMMIT_COUNT` from stdout.

If the command fails, report the failure plainly and stop — don't guess at a number.

## 4. Ask for the bug cap

Tell the user how many commits fall within the chosen window (the `WINDOW_COMMIT_COUNT` you just got), then ask directly, in plain text: what should be the cap on the number of bugs? This is the maximum number of bugs Causeway Mini will mine in this run. Accept whatever number they give.

## 5. Extract and write the evidence file

Run the tool again, this time without `--count-only` and with the cap:

```bash
sbt -batch "runMain causeway.mini.InspectRepo --repo-url <repo-url> --since-date <iso-date> --window-label <slug> --cap <cap>"
```

The clone from step 3 is reused, so this should be fast. It writes every commit within the window — full commit message, author, committer, dates, parent SHAs, plus the bug cap you now have — to a JSON evidence file under `workspace/exports/`.

Read the tool's stdout for:
- `WINDOW_COMMIT_COUNT` — should match step 3's preview
- `EVIDENCE_FILE` — path to the JSON evidence file
- `RUN_ID` — the run's identifier

If the command fails, or doesn't print an `EVIDENCE_FILE` line, report the failure plainly to the user — don't claim success.

If the tool behaves unexpectedly right after a code change to it, the sbt background server may be stale — run `sbt -batch shutdown` once, then retry.

## 6. Final report

Produce one final message stating, plainly:
- The repo (`owner/repo`)
- The window chosen, and the resolved since-date (note if it was an approximation)
- How many commits fall within the window
- The bug cap chosen
- Where the evidence file was written

Then tell the user to run `/inspect_commits` next, which will fetch each commit's linked GitHub PRs/issues and its JGit diff content.

End the message with: "If anything above is wrong, just run /inspect_repo again to start over."
