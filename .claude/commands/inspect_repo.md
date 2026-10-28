---
description: Clone a repo at a pinned commit and record the commits to mine
argument-hint: "<github url or owner/repo>"
allowed-tools: AskUserQuestion, Bash(tools/causeway:*)
---

Step 2 of Causeway Mini. The target is `$ARGUMENTS`; if empty, offer the top results of `tools/causeway list-repos --limit 4` as choices, with "Other" for a URL.

Ask with AskUserQuestion, using these defaults, each as a multiple-choice question:
- **Scan limit** (how many recent non-merge commits to record): 50 / 100 / 250 / 500.
- **Window**: whole history / last 6 months / last 12 months. Translate a window into `--since YYYY-MM-DD` using today's date.
- **Version**: tip of the default branch / a specific branch or commit (ask for it as free text, then pass `--branch` or `--commit`).

Then run:

```
tools/causeway inspect-repo --url <url> --scan-limit <n> [--since <date>] [--branch <b>] [--commit <sha>]
```

Report the `run` id, the repo, the **pinned commit** (`pinned_sha`, the exact state everything later is anchored to), and how many commits were recorded. Suggest `/enrich_commits <run>` next.
