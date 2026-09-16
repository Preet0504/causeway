---
description: Browse past mining passes, most recent first with dates, and optionally pick one to continue mining.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini run-history flow. Every `/inspect_repo` run mints a run id that `/inspect_commits` and `/classify_bugs` carry forward unchanged, but nothing shows those past runs on their own, this command is how you browse them, like `git log` for this catalog, and pick one to pick up where it left off.

## 1. Ask how many to show

Use the AskUserQuestion tool, header `"How many"`, to ask how many past runs to show, at most. Offer a few sensible presets (e.g. 5 / 10 / 25). The tool's automatic custom-answer option covers any exact number.

## 2. Run the query

```bash
tools/causeway list-runs --limit <N>
```

This is a pure database read, no network call, no writes, don't run any SQL of your own here. Read each `RUN stage=pass run_id=... owner=... repo=... window=... since=... createdAt=... sourceFile=... stagesReached=...` line, plus `SHOWN`. One line is one mining pass, not one pipeline stage; `stagesReached` (a comma list, e.g. `inspect-repo,inspect-commits`) says how far that pass actually got.

If `SHOWN=0`, say plainly that no run has ever been recorded yet, and to run `/discover_repos` or `/inspect_repo` first.

## 3. Show the results

Render a markdown table, one row per run, already newest first (don't re-sort): run id, owner/repo, window, date (`createdAt`), and stages reached (rendered readably, e.g. "repo → commits → bugs" for all three, "repo → commits" for two, "repo" for just one).

## 4. Offer to continue

Use the AskUserQuestion tool, header `"Pick a run"`, to ask whether they'd like to pick up one of these. Offer up to 4 options, most recent first, labeled `owner/repo (window) — <date>`, description the stages reached. The tool's automatic custom-answer option covers naming a run id directly, including one from beyond the shown limit, or declining for now.

If they pick one, run `tools/causeway list-runs --run-id <id>` to get that run's full per-stage detail (don't just reuse the summary row from step 3, this call also gives you each stage's exact `sourceFile`). Then, based on the furthest stage it actually reached:

- **Only `inspect-repo`**: remember its evidence file (the `sourceFile` from that one row) for this session, state plainly where it is, and tell them to run `/inspect_commits` next, it'll pick up this evidence file directly rather than asking which one to use.
- **Through `inspect-commits`, not `classify-bugs`**: remember its enriched file, state plainly where it is, and tell them to run `/classify_bugs` next, same reasoning.
- **Through `classify-bugs`**: this pass already has a classified result. State plainly where the classified file is, and mention that `/classify_bugs` can also be run again against its enriched file (also remember that path) to try a different bug target, if they want a second pass.

If they decline, don't push further, just stop there.

End the message with: "If anything above is wrong, just run /list_runs again to start over."
