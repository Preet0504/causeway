---
description: Describe the kind of repository you're looking for in plain language, and find candidates via GitHub's search API.
model: claude-haiku-4-5-20251001
---

Run the Causeway Mini repository discovery flow. This is for when you don't already have a specific repo in mind, `/run_causeway` is still the right command once you know exactly which repo to mine.

## 1. Ask what kind of repository to look for

Ask, in plain text, along these lines: "What kind of repository are you looking for? For example: 'popular Java web frameworks that are still actively maintained', 'small, quiet C libraries, not forks', or 'JSON parsing libraries with an MIT license'." This has to stay a free-text question, requirements are too varied for a small multiple-choice menu, but showing a few complete example answers (not just a list of dimensions to consider) gives the person a concrete sense of the expected shape of a reply, so include at least two or three phrased as full example answers, not a bare list of category names like "language, stars, activity, size". Dimensions worth knowing about, to recognize in whatever they say: language, how popular (star count), how many times it's been forked (a separate popularity/adoption signal from star count), how actively maintained, roughly how big, how old or established the project is, whether forks or archived repos should be included, topics/tags, license. The person doesn't need to address every one of these, only whatever actually matters to them, most searches will only specify two or three.

If their answer doesn't mention how many candidate repositories they want back, use the AskUserQuestion tool, header `"Max results"`, with a few sensible preset counts (e.g. 5 / 10 / 25) to ask how many, at most. The tool's automatic custom-answer option covers any exact number.

## 2. Hand off to the repo-discovery agent

Invoke the `repo-discovery` agent (`subagent_type: "repo-discovery"`) with the person's requirements and the max result count, in plain language, embedded in your prompt to it. That agent translates plain language into `tools/causeway search-repos`'s structured flags and runs it, that translation is its job, not yours, don't attempt to construct the flags yourself first. `GITHUB_TOKEN` doesn't need to be prepared beforehand, `tools/causeway` sources `.env` itself before running anything.

If the agent reports a failure, or that the requirements were too vague to translate into a meaningful search, use the AskUserQuestion tool to ask the clarifying question the agent needed answered, don't guess at flags yourself. The agent only has Bash access, it can't ask the user anything directly, that's why this is your job whenever it reports needing more information.

## 3. Qualify the results before showing them

Read the `SEARCH_RUN_ID` the agent reported, then run this yourself directly, don't delegate it to the agent, there's no translation judgment call involved here, just a fixed check:

```bash
tools/causeway qualify-repos --search-run-id <search-run-id>
```

This cheaply checks each discovered repository against GitHub (whether issues are enabled, closed/open issue counts, merged/open PR counts, and how many files look like tests) *before* anyone clones anything. A repository with issues disabled, or with no closed issue and no merged pull request ever, cannot produce the kind of evidence this pipeline looks for no matter how it's mined, that's a hard rejection. The counts beyond that (open issues, open PRs, test file count) are informational activity signals, not disqualifiers on their own.

Read each `QUALIFY owner=... repo=... qualifies=true|false closedIssues=... openIssues=... mergedPrs=... openPrs=... testFiles=... reason=...` line.

## 4. Show the results

Render a markdown table of what was found: owner/repo, stars, language, size (the agent's `sizeKb=...` reading, formatted human-readably: KB under 1024, otherwise MB to one decimal place), closed/open issues, merged/open PRs, test files, qualifies (yes/no), and, for anything that didn't qualify, the reason why, using the search and qualification results directly, don't re-run anything to get this. State plainly which search flags were actually used (the agent should have told you), so the person can tell whether their requirements were translated the way they expected.

If the result count is small or zero, or nothing qualified, say so plainly and suggest, based on which flags were used, which one is most likely too narrow, don't present a thin, empty, or all-rejected result set as if it were a complete or expected answer.

## 5. Offer to continue

Use the AskUserQuestion tool, header `"Next repo"`, to ask whether they'd like to mine one of these repositories next. Offer up to 4 options: the qualifying repos with the most stars first (label each `owner/repo`), and, if there's room within the 4-option limit or nothing qualified at all, an explicit "Not right now" option. The tool's automatic custom-answer option covers naming any other repo shown in the table, or one that didn't qualify (if they pick one that didn't qualify this way, mention plainly that it has no possible bug-fix evidence source or has issues disabled, but don't refuse if they insist).

If they pick a repo, run `tools/causeway repo-details --owner <owner> --repo <repo>` before saying anything else, this is a last look at the fuller picture (description, topics, license, when it was created, archived/fork status) beyond what the table showed, not something to show for every candidate up front. Read `DESCRIPTION`, `TOPICS`, `REPO_CREATED_AT`, `CURRENT_LICENSE`, `CURRENT_ARCHIVED`, `CURRENT_FORK` from its output (skip any that print as empty or `?`, that just means it isn't known yet) and show them plainly before moving on. Then remember its URL for this session and tell them to run `/inspect_repo` (or `/run_causeway` to run the whole pipeline through to classified bug fixes in one go), either of which will pick up that URL directly rather than asking for it again.

Every discovered repository (not just the one picked), and its qualification result, is already stored in `workspace/causeway.db`, tied to the exact search specification that found it, whether or not it's mined further right now. Mention plainly that running `/list_qualifying_repos` any time later shows every repository that's ever qualified across every search so far, not just this one, in case they want to come back to one of today's other results (or an older search's) without searching again.

End the message with: "If anything above is wrong, just run /discover_repos again to start over."
