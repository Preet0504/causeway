---
description: Run the entire Causeway Mini pipeline in one go, from a repo URL to classified bug-fix commits.
model: claude-haiku-4-5-20251001
---

Run the full Causeway Mini pipeline end to end, in one continuous flow: pick a repo and window, mine its commits, enrich them, and classify bug fixes. Ask exactly the same questions each stage asks on its own, in the same order, just without stopping in between to tell the user to run the next command themselves.

## 1. Inspect the repo

Follow `/inspect_repo`'s flow in full, its steps 1 through 8: gather the repo URL and window, discover and choose the remote, discover and choose the branch, resolve the window to a date, preview the commit count, ask for the scan commit limit, write the evidence file, and store it. Skip its own step 9 (final report) and closing line, fold everything it would have reported into this command's own final report (step 4 below) instead.

## 2. Enrich the commits

Follow `/inspect_commits`'s flow, its steps 2 through 3: run the enrichment tool, then store the result. Its step 1 doesn't apply, you already know the evidence file path from step 1 above. Skip its own step 4 (final report) the same way.

## 3. Classify bug fixes

Follow `/classify_bugs`'s flow, its steps 2 through 7: ask for the bug target, split commits into batches, work through them, show the results table, write the final JSON file, and store it. Its step 1 doesn't apply, you already know the enriched file path from step 2 above. Skip its own step 8 (final report) the same way.

## 4. Final report

Produce one combined final message covering the whole run:
- The repo (`owner/repo`), the remote and branch chosen
- The window chosen and its resolved since-date
- How many commits fell within the window, and the scan commit limit chosen
- How many commits were enriched
- The bug-classification table from step 3, and how many genuine bug fixes were found against the target
- Where the evidence, enriched, and classified JSON files were written

End the message with: "If anything above is wrong, just run /run_causeway again to start over."
