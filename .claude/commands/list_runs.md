---
description: List past mining runs with how many bug fixes each found
argument-hint: "[limit]"
allowed-tools: AskUserQuestion, Bash(tools/causeway:*)
---

Run `tools/causeway list-runs --limit ${ARGUMENTS:-20}` and render `runs` as a table, newest first: date, run id, repo, short pinned sha, commits, classified, **bug fixes**, enriched (yes/no). If empty, say no runs exist and suggest `/inspect_repo`.

Then ask which run to continue (AskUserQuestion, options for the 3 newest, plus "None"). For a chosen run: if it has unclassified commits suggest `/classify_bugs <run>`, else offer `tools/causeway show-evidence --run <run> --only-bug-fixes`.
