---
description: Attach the pull requests and issues linked to a run's commits
argument-hint: "<run id>"
allowed-tools: AskUserQuestion, Bash(tools/causeway:*)
---

Step 3 of Causeway Mini. If `$ARGUMENTS` is empty, run `tools/causeway list-runs --limit 4`, and ask which run to enrich (AskUserQuestion, one option per run).

Run `tools/causeway enrich --run <run>`. Report how many commits have linked PRs/issues, and how many calls failed (`errors`; show `error_samples` if any, and say a 401 means `GITHUB_TOKEN` is missing or wrong). Suggest `/classify_bugs <run>` next.
