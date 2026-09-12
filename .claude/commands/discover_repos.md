---
description: Describe the kind of repository you're looking for in plain language, and find candidates via GitHub's search API.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini repository discovery flow. This is for when you don't already have a specific repo in mind, `/run_causeway` is still the right command once you know exactly which repo to mine.

## 1. Ask what kind of repository to look for

Ask directly, in plain text: "What kind of repository are you looking for?" Give a few examples so the question isn't too open-ended to answer usefully, for instance: language, how popular (star count), how actively maintained, roughly how big, whether forks or archived repos should be included, topics/tags, license. The person doesn't need to address every one of these, only whatever actually matters to them, most searches will only specify two or three.

If their answer doesn't mention how many candidate repositories they want back, ask directly: "How many candidates would you like, at most?"

## 2. Hand off to the repo-discovery agent

Invoke the `repo-discovery` agent (`subagent_type: "repo-discovery"`) with the person's requirements and the max result count, in plain language, embedded in your prompt to it. That agent translates plain language into `tools/causeway search-repos`'s structured flags and runs it, that translation is its job, not yours, don't attempt to construct the flags yourself first. `GITHUB_TOKEN` doesn't need to be prepared beforehand, `tools/causeway` sources `.env` itself before running anything.

If the agent reports a failure, or that the requirements were too vague to translate into a meaningful search, relay that plainly and ask a clarifying question rather than guessing at flags yourself.

## 3. Qualify the results before showing them

Read the `SEARCH_RUN_ID` the agent reported, then run this yourself directly, don't delegate it to the agent, there's no translation judgment call involved here, just a fixed check:

```bash
tools/causeway qualify-repos --search-run-id <search-run-id>
```

This cheaply checks each discovered repository against GitHub (whether issues are enabled, whether it has any closed issue or merged pull request, and whether its file tree looks like it contains tests) *before* anyone clones anything. A repository with issues disabled, or with no closed issue and no merged pull request ever, cannot produce the kind of evidence this pipeline looks for no matter how it's mined, that's a hard rejection. Whether it appears to have tests is a much softer, pattern-matched signal (real tests can live somewhere this doesn't recognize), it's shown for information but never causes a rejection on its own.

Read each `QUALIFY owner=... repo=... qualifies=true|false reason=...` line.

## 4. Show the results

Render a markdown table of what was found: owner/repo, stars, language, qualifies (yes/no), and, for anything that didn't qualify, the reason why, using the search and qualification results directly, don't re-run anything to get this. State plainly which search flags were actually used (the agent should have told you), so the person can tell whether their requirements were translated the way they expected.

If the result count is small or zero, or nothing qualified, say so plainly and suggest, based on which flags were used, which one is most likely too narrow, don't present a thin, empty, or all-rejected result set as if it were a complete or expected answer.

## 5. Offer to continue

Ask whether they'd like to mine one of these repositories next, steering them toward the ones that qualified (mention plainly if they pick one that didn't, that it has no possible bug-fix evidence source or has issues disabled, but don't refuse if they insist). If they pick one, remember its URL for this session and tell them to run `/inspect_repo` (or `/run_causeway` if they also want to choose a different time window first), which will pick up that URL directly rather than asking for it again.

Every discovered repository (not just the one picked), and its qualification result, is already stored in `workspace/causeway.db`, tied to the exact search specification that found it, whether or not it's mined further right now.

End the message with: "If anything above is wrong, just run /discover_repos again to start over."
