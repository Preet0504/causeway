---
description: Classify a run's commits as bug fixes, with evidence, until a target is reached
argument-hint: "<run id>"
allowed-tools: Task, AskUserQuestion, Bash(tools/causeway:*)
---

Step 4 of Causeway Mini.

1. **Run.** Use `$ARGUMENTS`, else list the 4 most recent with `tools/causeway list-runs --limit 4` and ask which one. Run `tools/causeway run-status --run <run>`. If `unclassified` is 0, skip to step 4. If the run is not enriched (`enriched: false`), tell the user the linked-PR/issue signal will be missing and offer `/enrich_commits` first.
2. **Target.** Ask (AskUserQuestion) how many bug fixes to find: 5 / 10 / 25 / 50. Scale the options down if `unclassified` is smaller than the largest.
3. **Loop** until `bug_fixes` in the run status reaches the target or `remaining` is 0:
   1. `tools/causeway next-batch --run <run>` → JSON with up to 5 commits.
   2. Invoke the `bug-classifier` agent. Its prompt is the batch JSON, verbatim and nothing else.
   3. Store its reply by piping it to the CLI. Do not edit the reply, even to fix it:
      ```
      tools/causeway record-classification --run <run> <<'JSON'
      <the agent's reply>
      JSON
      ```
   4. If the result lists `rejected` entries, send just those commits back to the agent once. Entries still rejected after that are left unclassified; mention them at the end.
   5. Read `status.bug_fixes` from the result to decide whether to continue. Never decide a verdict yourself.
4. **Evidence.** Run `tools/causeway show-evidence --run <run> --only-bug-fixes`. For each commit show: short sha, subject, the combined score, and the three signals, each as `score — explanation`, then its linked PRs/issues. Finish with a line such as "7 bug fixes in 35 commits classified". If the user asks about a rejected call, run `show-evidence` without the flag.
