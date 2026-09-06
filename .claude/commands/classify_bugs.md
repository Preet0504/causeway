---
description: Fan out bug-classifier subagents over an enriched evidence file's commits, then synthesize a verdict for each.
---

Run the Causeway Mini bug-classification flow. This reads an `/inspect_commits` enriched evidence file, has `bug-classifier` subagents score each commit on three independent signals, and you (the orchestrator) render a final verdict per commit.

## 1. Find the enriched evidence file

If this conversation already knows the enriched evidence file path from an `/inspect_commits` run earlier in this session, use it directly.

Otherwise, ask the user for the path, or list `workspace/exports/*_enriched.json` and ask them to confirm which one if more than one exists.

## 2. Split commits into batches of 5

Read the enriched file's `commits` array. Split it into consecutive batches of exactly 5 commits each (the last batch may have fewer). The number of subagents you'll spin up is the number of batches — i.e. `ceil(commit count / 5)`.

For each commit, you only need these fields for the subagent: `sha`, `shortMessage`, `fullMessage`, `pullRequests`, `diff`. Use a short Python snippet (or equivalent) to read the file and print each batch's slice as its own JSON array — this avoids retyping or mistranscribing commit data by hand.

## 3. Fan out the classifier subagents

For each batch, invoke the `bug-classifier` agent (`subagent_type: "bug-classifier"`), with that batch's JSON array pasted directly into the prompt. Tell it plainly how many commits are in this batch and to return one scored object per commit, in order.

Issue all these agent calls together, in parallel, in the same turn — they're independent of each other. Run them in the foreground (not backgrounded): you need every batch's results before you can continue to step 4.

If any batch's agent call fails outright, or returns something that doesn't parse as the expected JSON array, report that plainly for the affected commits (don't fabricate scores) and continue with the batches that did succeed.

## 4. Render your own verdict per commit

For each commit, you now have three scores and three explanations (message, diff, PR/issue) from its batch's subagent. Read all of them yourself and decide: is this commit a genuine bug fix, yes or no? This is your judgment call, not a formula — a commit with a mediocre message score but a very convincing diff and a linked bug-report issue can still be a clear bug fix, and vice versa. Write one or two sentences of rationale for your verdict, referencing whichever signal(s) actually drove your decision.

## 5. Show the user a table

Render a markdown table, one row per commit, with columns: short sha (first 10 chars), message score, diff score, PR score, verdict (✅/❌), and a brief reason (your rationale, trimmed to fit).

## 6. Write the final JSON file

Write a new file next to the enriched evidence file (same directory, same base name with `_enriched` replaced by `_classified`, e.g. `run_<id>_classified.json`), containing:
- The same top-level repo/window/bugCap fields as the enriched file, carried over
- `classifiedCount`
- A `classifications` array, one entry per commit: `sha`, `shortMessage`, `messageScore`, `messageExplanation`, `diffScore`, `diffExplanation`, `prScore`, `prExplanation`, `verdict` (boolean), `verdictRationale`

## 7. Final report

After the table, state plainly:
- How many commits were classified, and how many you judged to be genuine bug fixes
- Where the final JSON file was written

Tell the user this is as far as Causeway Mini currently goes — there's no next command yet.

End the message with: "If anything above is wrong, just run /classify_bugs again to start over."
