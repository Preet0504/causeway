---
description: Find GitHub repositories worth mining, from a plain-language description
argument-hint: "<what you are looking for, e.g. active Scala CLI tools with 200+ stars>"
allowed-tools: Task, AskUserQuestion, Bash(tools/causeway:*)
---

Step 1 of Causeway Mini: discover candidate repositories and filter them down to ones worth mining.

1. If `$ARGUMENTS` is empty, ask the user (free text) what kind of repositories they want: language, popularity, how recently active, size, topics.
2. Hand the request, word for word, to the `repo-discovery` agent. It runs `tools/causeway search-repos` and stores the results; do not run the search yourself.
3. Show the user the agent's table of qualifying repositories and the rejection summary.
4. Offer `/inspect_repo <url>` for one of them as the next step. Do not start it unprompted.
