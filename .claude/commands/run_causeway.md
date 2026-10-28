---
description: Run the whole Causeway Mini pipeline for one repository
argument-hint: "<github url or owner/repo>"
allowed-tools: Task, AskUserQuestion, Bash(tools/causeway:*)
---

Run steps 2 to 4 for `$ARGUMENTS` (if empty, ask for a URL, or offer `tools/causeway list-repos --limit 4`), asking each question once up front:

1. Scan limit (50 / 100 / 250 / 500) and bug-fix target (5 / 10 / 25 / 50).
2. Follow `/inspect_repo` to create the run, with those answers and the default branch tip.
3. Follow `/enrich_commits` for that run.
4. Follow `/classify_bugs` for that run, with the target from step 1 (do not ask again).

Stop and report if any step fails; do not continue with partial data. End with the bug-fix evidence and the run id so it can be found later in `/list_runs`.
