---
description: Show every currently-qualifying repository ever discovered, not just the most recent search's results, and optionally pick one to mine.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini qualifying-repos listing flow. `/discover_repos` stores every repository it finds, not just the one you go on to mine, this command is how that accumulated catalog actually becomes useful: a repo you skipped last week is still here, still qualifying, still pickable.

## 1. Ask how many to show

Use the AskUserQuestion tool, header `"How many"`, to ask how many qualifying repositories to show, at most. Offer a few sensible presets (e.g. 10 / 25 / 50). The tool's automatic custom-answer option covers any exact number.

## 2. Run the query

```bash
tools/causeway list-qualifying-repos --limit <N>
```

This is a pure database read, no network call, no writes, don't run any SQL of your own here, the CLI owns database access the same way it owns GitHub access. Read each `REPO owner=... repo=... stars=... language=... sizeKb=... closedIssues=... openIssues=... mergedPrs=... openPrs=... testFiles=... alreadyMined=true|false url=...` line, plus `TOTAL_QUALIFYING` (how many qualify in total, regardless of the limit) and `SHOWN` (how many you actually got back).

If the command fails, report that plainly. If `TOTAL_QUALIFYING=0`, say so plainly too, that means nothing has ever qualified yet, run `/discover_repos` first.

## 3. Show the results

Render a markdown table: owner/repo, stars, language, size (the `sizeKb=...` reading, formatted human-readably: KB under 1024, otherwise MB to one decimal place), closed/open issues, merged/open PRs, test files, already mined (yes/no), url, most starred first (they're already returned in that order, don't re-sort). State plainly how many are shown against the total (e.g. "showing the top 10 of 37 qualifying repositories").

## 4. Offer to continue

Use the AskUserQuestion tool, header `"Pick a repo"`, to ask whether they'd like to mine one of these next. Offer up to 4 options: repos from the table, most starred first, prioritizing ones with `alreadyMined=false` (an already-mined repo is a legitimate pick too, e.g. to mine a different time window, but a not-yet-mined one is more likely what someone wants from this list). The tool's automatic custom-answer option covers naming any other repo from the table, or declining for now.

If they pick one, run `tools/causeway repo-details --owner <owner> --repo <repo>` before saying anything else, a last look at the fuller picture (description, topics, license, when it was created, archived/fork status) beyond what the table showed. Read `DESCRIPTION`, `TOPICS`, `REPO_CREATED_AT`, `CURRENT_LICENSE`, `CURRENT_ARCHIVED`, `CURRENT_FORK` from its output (skip any that print as empty or `?`) and show them plainly before moving on. Then remember its URL for this session and tell them to run `/inspect_repo` (or `/run_causeway` to run the whole pipeline through to classified bug fixes in one go), either of which will pick up that URL directly rather than asking for it again.

End the message with: "If anything above is wrong, just run /list_qualifying_repos again to start over."
