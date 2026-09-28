# Genesis prompts

This project was not built from a single upfront specification. It grew prompt by prompt, across one continuous Claude Code session (2026-09-05 through 2026-09-27), each phase triggered by a specific gap, a professor's feedback, or a bug found while working on something else, not a plan written in advance.

What follows is a reconstruction, not a transcript. Each entry below consolidates a phase's real opening prompt with everything that got added, corrected, or removed in the messages that followed it, folded into one dense paragraph, the way a planned genesis script reads, even though these were not planned that way. The actual, unedited opening line for the whole project is Phase 0, quoted exactly. Every phase after that is a synthesis, built from `DECISIONS.md`'s own record of what was asked and why. Where a phase's real opening words survive recognizably, they are kept; where a phase is a consolidation of many small follow-ups, that is stated.

For the actual, unedited history of decisions as they were made, day by day, see `DECISIONS.md`. This document exists to answer a different question: not "what happened," but "what would you have to ask for, in one shot, to get this."

## Phase 0 - Plant the seed (2026-09-05)

The real, unedited first message of the whole project:

> "create a branch called 'causeway-mini', because we are going to create a smaller version of causeway, and the scope is going to be really small. I'll tell you more later. Make sure that the branch is empty, and in this session we will be working in that branch. How does that sound?"

Nothing else was specified. Everything below was figured out afterward, one phase at a time.

## Phase 1 - Correctness before features (2026-09-09/11)

Fix `bugCap`, a single field asked during repo inspection that is misnamed for what it actually does: split it into `scanCommitLimit` (how many commits get scanned in detail, asked before anything is known to be a bug) and `bugTarget` (how many accepted results to stop at, asked only once commits are actually being classified), two genuinely different questions that had been conflated into one. Add a real test framework (MUnit) since none exists yet, and hold every fix from here on to a standard: don't just claim a fix works, write a regression test, then prove the test actually catches the bug by temporarily reintroducing it and watching the test fail, before trusting a passing suite. Apply that standard immediately: remove the early-exit optimization in the commit-window walk, since it silently assumes commit timestamps are monotonic, which is false for real Git histories (rebases, clock skew, merges of independently-developed branches), and prove the fix with a hand-built repo containing a deliberately backdated commit.

## Phase 2 - Pin down what was actually examined (2026-09-11)

A repo URL alone does not define repository state. Fetch from the remote and record the exact commit examined (a `repoSnapshot`: remote name, branch, head SHA, retrieval time) on every run, instead of silently trusting whatever a local clone happens to contain. Do not assume `origin` is the right remote, a fork's `origin` could point at the fork while `upstream` points at the actual project being studied, so list every configured remote and every branch and let the person choose explicitly, rather than validating an assumed choice and failing on mismatch.

## Phase 3 - One CLI, not scattered `sbt runMain` calls (2026-09-11)

Per the professor's own suggestion: package a unified, compiled `causeway` CLI instead of having agents repeatedly invoke `sbt runMain` on individual classes as the number of Scala tools grows. Build a launcher that caches its own classpath and only re-invokes sbt when a source file has actually changed, so a stable, predictable command surface exists that does not depend on sbt's build-server state at call time.

## Phase 4 - A real database, not just JSON files (2026-09-11/13)

Add a SQLite repository catalog representing, at minimum, repositories, repository snapshots, commits, issues, pull requests, and issue-commit relations, each preserving its source run's identifier, with repeated operations upserting rather than duplicating. Distinguish relationship types explicitly rather than one generic "commit relates to issue" fact: a commit belonging to a PR, that PR closing an issue, a commit's message merely mentioning an issue number, and their GitHub-confirmed counterparts, are different claims with different confidence, and collapsing them loses exactly the distinction future bug-fix-evidence scoring would need. Every pipeline stage should store its own run into the catalog directly as it completes, not depend on a separate step someone might forget to run, and a database write failing should never fail a command whose JSON file already wrote successfully. Keep the JSON files as the pipeline's real interchange format regardless, the database is additive, not a replacement.

## Phase 5 - Find a repo without already knowing its URL (2026-09-11/12)

Let a person describe what they want in plain language (language, popularity, activity, size, topics, forks, age) and translate that into GitHub's structured search API through a dedicated agent, one that does the fuzzy translation and nothing else, no direct database or git access. Cheaply qualify every candidate before anything clones it: issues enabled and at least one closed issue or merged PR are hard requirements (a repo that could never produce commit-to-issue evidence, cloning it changes nothing), while evidence of tests is recorded but never itself disqualifying. Give the accumulated catalog of past searches its own browsable view, not just the most recent search's results, and give a single repository its own detail lookup for the moment someone actually picks it to mine. Agents never query the database directly, only the compiled CLI does, so there is exactly one place database access can go wrong.

## Phase 6 - Every question a real choice, not free text (2026-09-12)

Wherever a command asks the user something, make it a real multiple-choice question wherever the answer space is small enough, including numeric caps that might seem free-text-only (scan limit, bug target, max results), scaled to whatever context is already known rather than a fixed list that might not fit. Leave genuinely open-ended things (a repo URL, a plain-language description of what to search for, a branch list longer than four) as free text, since a fixed menu cannot represent them honestly.

## Phase 7 - Don't let the schema accumulate speculative weight (2026-09-12, recurring)

Periodically audit the schema for tables, columns, and booleans that nothing reads back, and remove them rather than letting them accumulate: author/committer fields nobody used, a `qualifies` boolean that was always exactly `rejection_reason IS NULL` and could never independently disagree with it, three stub tables built for capabilities that do not exist yet, a `commit_parents` table nothing ever queried. Where two things look redundant, check field by field before merging them, since some apparent duplicates (a per-search historical snapshot versus a repository's current identity) are genuinely different facts that only look alike from a distance. Prefer real counts over booleans where the underlying data already exists for free (closed issues, merged PRs, test file count), and periodically capture more of what an existing API call already returns rather than the minimum needed for the immediate question.

## Phase 8 - Centralize GitHub access and credentials (2026-09-11/12)

Credential handling had fragmented, one command file ran its own raw `curl`, another separately sourced `.env`. Move all of it into the CLI launcher itself: source `.env` once, there, before invoking anything, so every subcommand's process environment already has `GITHUB_TOKEN` and no chat command instructions need to know or say anything about where the credential lives.

## Phase 9 - Document what the workflow does, with real output (2026-09-12)

Rewrite the README to describe what each piece does, not the reasoning behind why it is built that way, and replace every illustrative or hand-written example with output captured from an actual live run against a real repository. Structure it for a reader who does not already know what is going on: a pipeline-at-a-glance table before any deep-dive content, bullets and tables instead of dense paragraphs, and a walkthrough that shows each stage's own real result rather than one compressed transcript.

## Phase 10 - Consistency and discoverability of past runs (2026-09-13)

Every pipeline stage should self-store the same way, not just some of them, if search and qualify already write straight to the database with no intermediate step, the mining stages should too. Once storage is consistent, close the resulting gap: a person choosing which evidence or enriched file to reuse should be able to ask the database, which already records exactly that (owner, repo, window, exact file path) for every run, instead of guessing from a bare UUID filename in a flat folder.

## Phase 11 - Agents should not be able to touch what they are not supposed to (2026-09-15)

Check directly whether any subagent could reach the database or run arbitrary commands, rather than assuming the agents' own written instructions are enough. Where a tool restriction can be enforced by the platform itself (an explicit deny-list), use that instead of prose claiming "no access." Where an agent genuinely needs a broad tool like Bash for one specific purpose, build a real enforcement mechanism, not just an instruction, and make that mechanism generic (declared per-agent, not hardcoded to one agent's name) so a future agent can be restricted the same way without a code change to the enforcement itself.

## Phase 12 - Remove duplicated knowledge between a command and its agent (2026-09-15)

Where a chat command and the agent it calls both know the same taxonomy (what search dimensions exist, what fields a result needs), keep exactly one of them as the source of truth and have the other simply pass through what it does not need to understand itself. Fix the same class of gap at its root when a data field a report depends on is only ever "probably" reported by an agent's own free-text summary, guarantee it comes from the deterministic tool instead.

## Phase 13 - Organize the workspace and make run history genuinely browsable (2026-09-16)

Give clones and exported JSON files a structure that scales past a handful of runs (`owner-repo` folders, not one flat pile), and give run history a real browsing experience, not just an exact lookup: one row per mining pass, newest first, showing how far each pass actually got, the way `git log` shows commit history, reachable from a chat command of its own, not only the raw CLI. Every final report should also state its own run id plainly, since a run id that only exists inside a file path is not something a person can actually use later.

## Phase 14 - Consider packaging, then don't, because it solves a problem nobody has yet (2026-09-16)

Evaluate whether a packaged, pre-built distribution (removing the sbt/bash prerequisite for someone not editing the Scala code) is worth building, build and fully verify it if so, but revert it if it turns out to solve a problem nothing in the actual workflow has, a packaged binary that no chat command ever references does not help the people who actually use this project day to day. Prefer the smaller, already-working thing over a general-sounding fix for a scenario that was never confirmed to be real.

## Phase 15 - Write a getting-started guide, and verify every line of it for real (2026-09-16/17)

Write a guide that actually walks a new user through prerequisites and exact terminal commands, researched against real tutorial-writing practice rather than freehanded: state the destination up front, one linear path, a checkable outcome after every step, explanation deferred elsewhere. Then run every single command in it, in sequence, without cheating, on every platform it claims to support, not just the one it happened to be written on. Add a troubleshooting section matching exact error text to a fix, built from errors actually hit during that verification, not hypothetical ones.

## Phase 16 - Make cross-platform claims true, not assumed (2026-09-16/27)

Where a guide claims to support more than one platform, prove it on each one independently: a `.env` file with Windows line endings breaks on real Linux, a pipe pattern that works fine on Windows aborts under Linux's stricter pipefail behavior, a hardcoded classpath separator and cache path are silently wrong on any platform they were not written for. When an external reviewer reports a platform gap firsthand (Mac, in this case), take their fix rather than guessing blind at behavior with no way to verify it, and when a claim turns out to be wrong after being stated to that reviewer (an assumption about how the project's history unfolded), correct it rather than letting it stand. When a reviewer's question exposes that an answer described a mechanism but not the actual confidence behind it, document the honest version, including where there is no ground truth and the real answer is "reviewable, not proven," in the project's own documentation, not only in a private reply.
