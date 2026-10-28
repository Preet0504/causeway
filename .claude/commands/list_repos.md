---
description: Show every qualifying repository found by any past search
argument-hint: "[limit]"
allowed-tools: Bash(tools/causeway:*)
---

Run `tools/causeway list-repos --limit ${ARGUMENTS:-20}` and render the `repos` array as a table: full name, language, stars, closed issues, merged PRs, last checked. If it is empty, say no search has found a qualifying repository yet and suggest `/discover_repos`.
