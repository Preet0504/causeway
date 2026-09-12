# Causeway Mini

## What is this?

Causeway Mini looks through a public GitHub project's history and figures out which past changes were actually bug fixes, as opposed to new features, cleanup, or documentation changes, and explains why it thinks so for each one.

You point it at a repo, tell it how far back in time to look, and it works through the history in stages. It finds the candidate commits, digs up extra context about each one from GitHub (was there a bug report, a pull request discussion), looks at the actual code that changed, and then has a few independent AI reviewers weigh in before settling on a final answer for each commit, stopping once it has found as many genuine bug fixes as you asked for. If you don't already know which repo you want, you can also describe what you're looking for in plain language (language, popularity, activity, and so on) and it searches GitHub for candidates first.

Everything is run through a handful of chat commands, typed one after another, each one picking up where the last left off.

## Tech stack

- **Scala 3**: the programming language the actual data-processing tools are written in.
- **sbt 2**: the build tool that compiles and runs the Scala code.
- **JGit**: a library that lets the Scala code read a git repository's history and compute diffs directly, without needing the `git` command line tool installed separately.
- **GitHub GraphQL API**: used to fetch the pull requests and issues linked to a commit, straight from GitHub, rather than guessing from the commit message alone.
- **GitHub REST search API**: used to find candidate repositories from structured criteria (language, stars, activity, and so on), separately from the GraphQL API used once a specific repo is already chosen.
- **upickle/ujson**: a small library for reading and writing the JSON files this project passes between its stages.
- **SQLite (via the `sqlite-jdbc` driver)**: a repository catalog, `workspace/causeway.db`, that every stage's JSON output also gets stored into, so the data can be queried directly (which commits, across every run ever done, are linked to a given issue?) instead of only ever being read one JSON file at a time.
- **Claude Code commands and subagents**: the chat commands (`/run_causeway`, etc.) and the AI reviewers are plain instruction files that Claude Code reads and follows, no extra framework needed.
- **The `causeway` CLI**: a small launcher script that runs the compiled Scala tools directly, no `sbt` involved at invocation time. sbt is still what compiles the code, the launcher just stops the chat commands from needing to go through sbt's build-tool machinery on every single call.

## Code files, and what they actually do

### `build.sbt` and `project/build.properties`

These two files are the project's setup. `build.sbt` says this is a Scala 3 project, lists the two outside libraries it needs (JGit and upickle), and turns on a compiler warning flag. `project/build.properties` pins the exact version of sbt to use, so the build behaves the same on any machine.

### `src/main/scala/causeway/mini/Causeway.scala`

The single compiled entry point for everything. Rather than each tool having its own `main` (which is how `sbt runMain causeway.mini.InspectRepo` used to work, and why sbt kept complaining about "multiple main classes"), this is the only class with a real `main`, and it dispatches on the first argument, `inspect-repo`, `inspect-commits`, `store`, to that capability's own logic. Adding a new capability later means adding one case here, the capability itself doesn't need to know it's part of a CLI.

### `tools/causeway`

The launcher script the chat commands actually call: `tools/causeway inspect-repo --mode list-remotes ...`. It runs `java` directly against already-compiled classes and a cached classpath, no `sbt` involved. The first time it's run after a source file or `build.sbt` changes, it notices (by comparing file timestamps against its cache), recompiles once, and refreshes the cache automatically, every other invocation just runs, typically under a second of pure JVM startup instead of sbt's several-second build-server round trip. It also sources `.env` itself, before running anything, so `GITHUB_TOKEN` is always available to whichever subcommand needs it, no chat command instructions have to prepare it (or make a raw network call of their own) beforehand.

### `src/main/scala/causeway/mini/SearchRepos.scala`

This is the tool behind the `search-repos` subcommand, for when you don't already know which repo you want. It takes structured flags (language, minimum stars, how recently active, size, fork/archived status, topics, license, and a result cap), builds them into GitHub's repository search qualifier syntax, and pages through results (100 at a time, GitHub's own maximum, up to its overall 1000-result ceiling regardless of what's asked for). Unlike the other three pipeline tools, it writes no JSON file: every discovered repository is inserted straight into `workspace/causeway.db` as it's found, tagged with the exact search specification (as a `search_repos_runs` row) that discovered it, so results are traceable back to precisely what was searched for even without a JSON file to point to. It does not do the job of turning a person's plain-language request ("popular, actively-maintained Java repos") into these flags, that translation is the `repo-discovery` agent's job, this tool only ever does exactly what its flags say.

### `src/main/scala/causeway/mini/QualifyRepos.scala`

This is the tool behind the `qualify-repos` subcommand, a cheap pre-clone check that runs on whatever `search-repos` just found. Cloning a repository (what `/inspect_repo` does) is the expensive step in this whole pipeline, so before anything gets to that point, this asks GitHub a handful of cheap questions about a repository instead: are issues even enabled (`has_issues` from the repo's own metadata), has it ever had a closed issue or a merged pull request (two calls to GitHub's search API, only the result counts are needed, not the actual items), and does its file tree look like it contains tests (one recursive tree listing, paths only, no file content, pattern-matched against common test-file conventions across several languages). A repository with issues disabled, or with no closed issue and no merged PR ever, is rejected outright, there's no way that repository could ever produce the kind of commit-to-issue evidence this pipeline looks for. Whether it appears to have tests is recorded but never causes a rejection by itself, it's a soft, pattern-matched heuristic that can easily miss real tests living somewhere it doesn't recognize. This check is fully mechanical (fixed thresholds, no language-model judgment involved), so it's invoked directly, not through an agent.

### `src/main/scala/causeway/mini/InspectRepo.scala`

This is the tool behind the `inspect-repo` subcommand. It doesn't auto-detect which remote or which branch to use, a repo URL alone doesn't define that, a clone can have more than one remote (a fork's `origin` pointing at the fork, `upstream` at the original), and the "default" branch isn't necessarily the one worth mining. Instead it runs as one of five modes, each a separate invocation, so the orchestrator can show real options to the user between them:

1. **`validate`**, given just a repo URL: checks whether it names a real GitHub repository (`VALID=true`/`VALID=false`), nothing is cloned. This is the network call `/run_causeway` used to make itself directly, with a raw `curl`, before GitHub access was centralized into this CLI.
2. **`list-remotes`**: downloads the repo with JGit if it hasn't already been downloaded, or opens the existing copy if it has, then prints every remote actually configured there, name and URL. Nothing is fetched yet.
3. **`list-branches`**, given a chosen remote name: fetches from that remote (a repo's default branch moves, so a reused local copy would otherwise silently serve whatever it looked like last time this tool touched it), reads that remote's own configured URL, and asks GitHub's REST API for every branch on that repository, each with its current commit SHA, flagging whichever one GitHub calls the default.
4. **`count`**, given a chosen remote, branch, and its exact SHA (from step 3's output): walks backward through commit history starting from that exact pinned commit, checking every commit's timestamp individually rather than assuming they only get older the further back it goes (a real Git history isn't guaranteed to be ordered that way, clock skew and rebases can produce a commit whose timestamp is later than its own parent's), and just prints how many fall in the window. Writes nothing, this is a cheap preview.
5. **`write`**, same inputs as `count` plus a scan commit limit: writes every commit it found, message, author, date, and so on, plus which remote, branch, and SHA were pinned, into a JSON file.

Every mode that talks to GitHub does so with `GITHUB_TOKEN` (better rate limits than an unauthenticated call), which it reads from its own process environment, populated by `tools/causeway` sourcing `.env` before this tool ever runs.

`count` and `write` never re-fetch or re-resolve the SHA they're given, they trust it outright, since that SHA is the exact thing a person already chose in step 3. Re-checking it against whatever's newest at that later moment would silently defeat the point of pinning.

### `src/main/scala/causeway/mini/EnrichCommits.scala`

This is the tool behind the `inspect-commits` subcommand. Internally it:

1. Reads the JSON file that `InspectRepo` produced.
2. Takes only as many commits as the scan commit limit allows (the most recent ones).
3. Asks GitHub's GraphQL API for the pull requests linked to those commits (and, for each PR, its own commit list, and which issues it closes), grouping up to 20 commits into a single request instead of sending one request per commit.
4. For every issue discovered that way, asks a second batched GraphQL query for that issue's own timeline (which commits GitHub itself confirms referenced it, which pull requests cross-reference it) and body text.
5. For every commit that isn't a merge commit, computes the exact before and after code using JGit, running several of these comparisons at once instead of one at a time.
6. Skips the code comparison for merge commits, since a merge doesn't have one clean "before and after" to point to.
7. Combines all of this into a new JSON file, leaving the original file from step 2 untouched. This file only fetches the raw data, `Store.scala` is what turns it into the typed relations described below.

### `src/main/resources/schema.sql`

The SQLite schema for the repository catalog: repositories, repository snapshots, one run table per pipeline stage (`search_repos_runs`, `inspect_repo_runs`, `inspect_commits_runs`, `classify_bugs_runs`), a `search_run_repositories` join table (a discovered repository's stars/language/activity/etc. at the moment a particular search found it, mirroring `run_commits`'s "the same thing can be rediscovered by a different run" design), a `repository_qualifications` table (one current row per repository, re-checking replaces it rather than layering history, since only the latest answer to "is this worth cloning" matters), commits, commit parents, a `run_commits` join table, pull requests, issues, a typed `relations` table (a `relation_type` column distinguishes, for example, a commit belonging to a PR from that PR closing an issue, rather than collapsing every commit/PR/issue connection into one generic fact, see "How a commit connects to a PR or an issue" below), and bug classifications, plus three empty stub tables (`test_observations`, `materialized_revisions`, `build_runs`) reserved for capabilities that don't exist yet. Every table with a natural key (a repo's `owner`+`repo`, a run's `run_id`, a commit's `sha`) is meant to be upserted into, never plain-inserted.

### `src/main/scala/causeway/mini/Store.scala`

This is the tool behind the `store` subcommand. It takes any one JSON file any of the three pipeline stages produced, figures out which of the three it is by which fields are present (no separate flag needed), and upserts its contents into `workspace/causeway.db`. Storing the same file twice, or storing two different runs against the same repository, updates or reuses existing rows rather than creating duplicates, one run mining the same repo with a different time window than an earlier run doesn't get merged with it or corrupt it, both runs' results sit side by side, sharing only the one `repositories` row underneath. This is additive alongside the JSON files, not a replacement for them, `/inspect_commits` and `/classify_bugs` still read the previous stage's JSON file directly and in full.

### `.claude/agents/repo-discovery.md`

The instructions given to the repository discovery agent. It has access to exactly one tool, Bash, and is told, explicitly, to use it for exactly one purpose: running `tools/causeway search-repos ...`. No `curl`, no `git clone`, no writing to the database directly, no classifying commits, no builds, those are all other tools' and agents' jobs. Its own job is entirely translation: turning a plain-language description ("popular, actively-maintained Java repos") into the tool's structured flags, filling in only what the description actually implies, and asking rather than guessing at a number when "popular" or "small" isn't given a concrete threshold.

### `.claude/commands/discover_repos.md`

Instructions that say: ask what kind of repository the user is looking for, in plain language, and how many candidates they want, hand that straight to the `repo-discovery` agent (the command itself never constructs search flags), then run `qualify-repos` directly (no agent involved, it's a fixed check, not a translation) on whatever was found before showing anything, so the results table already shows which candidates are worth pursuing and why the rest aren't, and offer to remember a chosen repo's URL for `/inspect_repo`. This is a separate, optional entry point, not a step in the middle of the usual flow, `/run_causeway` is still how you start once you already know exactly which repo you want.

### `.claude/commands/run_causeway.md`

Plain instructions for Claude to follow, not code. They say: ask the user for a repo URL, check it against GitHub directly, ask again (up to 3 times) if it isn't found, explain what a time window means, ask the user to choose one, and then point them to `/inspect_repo` next. It deliberately does not ask about limits or targets, those come later once there's something concrete to base them on.

### `.claude/commands/inspect_repo.md`

Instructions that say: reuse the repo and time window from the previous command if they're already known, list the repo's configured remotes and let the user choose one (skipping the question if there's genuinely only one), list that remote's branches via GitHub and let the user choose one (the actual default branch is suggested), turn the chosen time window into an actual date, run the Scala tool once in preview mode to get a count, tell the user that count and ask for a scan commit limit, run the tool again for real, store the resulting evidence file into the SQLite catalog, and point them to `/inspect_commits` next. The scan commit limit question is phrased carefully to avoid the word "bug", since at this point nothing has been examined closely enough to know what's a bug fix and what isn't, this number only decides how many commits get that closer look.

### `.claude/commands/inspect_commits.md`

Instructions that say: find the JSON file from the previous step, run the enrichment tool, store the resulting enriched file into the SQLite catalog, show a few example results, and point the user to `/classify_bugs` next. It doesn't load the GitHub access token itself, that's `tools/causeway`'s own job now.

### `.claude/commands/classify_bugs.md`

Instructions that say: find the enriched JSON file, ask the user how many genuine bug fixes they want to find (the bug target), then work through the commits in groups of 5, one group at a time, sending each group to its own AI reviewer and judging the results as they come back. It stops as soon as enough genuine bug fixes have been found, or once every group has been checked, whichever comes first. It shows the user a table, writes one final JSON file with everything in it, and stores that file into the SQLite catalog too.

### `.claude/agents/bug-classifier.md`

The instructions given to each AI reviewer. They describe the three things to look at (commit message, code diff, linked pull requests or issues), how to score each one from 0.0 to 1.0 with a short explanation, and the exact reply format to use so the results can be read back automatically.

## The commands, in order

Each command is typed into the chat, for example `/run_causeway`. Every command ends by telling you what to run next, and by telling you how to redo that step if something looks wrong.

### 0. `/discover_repos` (optional, only if you don't already know which repo to mine)

What happens, step by step:

The command asks what kind of repository you're looking for, in plain language, giving a few examples of what you can mention (language, popularity, activity, size, forks, archived status, topics, license), and how many candidates you'd like at most. It hands that description straight to the `repo-discovery` agent, which translates it into `search-repos`'s structured flags and runs it, GitHub's repository search API does the actual finding. Every result is inserted into `workspace/causeway.db` immediately, tagged with the exact search that found it. Before showing you anything, it then runs `qualify-repos` on those same results directly (no agent involved, it's a fixed check): whether each one has issues enabled, has ever had a closed issue or merged PR, and whether its file tree looks like it contains tests, all without cloning anything.

Example output:

```
What kind of repository are you looking for?
(e.g. language, popularity, how actively maintained, size, forks,
archived status, topics, license)
```

You answer, say, "Small, actively-maintained JSON parsing libraries in Java, not forks, at most 10." Then:

```
Searched with: language:Java topic:json pushed:>=2026-03-11 fork:false
Found 6 of up to 10 candidates, 5 qualified:

| repo                    | stars | language | qualifies | reason |
|-------------------------|-------|----------|-----------|--------|
| stleary/JSON-java       | 5400  | Java     | yes       |        |
| some/quiet-fork         | 12    | Java     | no        | issues are disabled on this repository |
| ...                     | ...   | ...      | ...       | |

Would you like to mine one of these next? If so, run /inspect_repo
(or /run_causeway to also pick a different time window), it'll use
that repo directly. I'd suggest one of the 5 that qualified.

If anything above is wrong, just run /discover_repos again to start over.
```

### 1. `/run_causeway`

What happens, step by step:

The command asks: "What is the url of the repo?" You type a GitHub URL. It checks that URL against GitHub right away. If GitHub says the repo doesn't exist, it tells you plainly and asks again, up to 3 times before giving up. Once a real repo is found, it explains what a time window means (how much history to search through), then offers a few choices: past 1 month, past 3 months, past 6 months, or your own custom period. It does not ask about any limits or targets yet, those come later once there's something concrete to base them on.

Example output, once a repo and window are settled:

```
Found the repo: stleary/JSON-java.
Window: past 3 months (since 2026-06-05).

Next, run /inspect_repo, which will clone the repo, show how many
commits fall in this window, and ask for a scan commit limit at
that point.

If anything above is wrong, just run /run_causeway again to start over.
```

### 2. `/inspect_repo`

What happens, step by step:

It downloads the repo (or opens an already-downloaded copy), then lists every remote actually configured there. If there's only one, it just tells you and moves on, nothing to choose. If there's more than one, for example a fork with `origin` pointing at the fork and `upstream` pointing at the original project, it shows you all of them and asks which one to use, rather than guessing.

Once a remote is settled, it fetches from it and lists every branch GitHub reports for that repository, pointing out which one GitHub calls the default, and asks which one you'd like to mine (the default, or any other by name). Only then does it count how many commits fall inside your chosen time window, as of that exact chosen branch, without writing anything yet, and shows you that number. It asks how many of those commits should be scanned in detail, being explicit that this is not about bugs yet, just about how many commits are worth a closer look. Once you answer, it writes out a JSON file listing every one of those commits, along with exactly which remote, branch, and commit were chosen, so the run's results are tied to a specific, recorded state of the repo rather than whatever it happens to look like if someone checks again later.

Example output:

```
This repo has one remote configured: origin
(https://github.com/stleary/JSON-java). Using that.

GitHub's default branch for this repo is "master". Which branch
would you like to mine? (default: master)
```

You accept the default. Then:

```
There are 28 commits in this window. How many of these commits
should be scanned in detail?
```

You answer, say, `6`. Then:

```
Repo: stleary/JSON-java
Remote: origin (https://github.com/stleary/JSON-java)
Branch: master @ 4f859fdf3b
Window: past 3 months (since 2026-06-05)
Commits in window: 28
Scan commit limit: 6
Evidence file: workspace/exports/run_230a05ac-5726-4bd4-be3d-9097d92fbd4e.json

Next, run /inspect_commits, which will fetch each commit's linked
GitHub PRs/issues and its JGit diff content.

If anything above is wrong, just run /inspect_repo again to start over.
```

The evidence file's `repoSnapshot` records exactly what was chosen:

```json
{
  "remoteName": "origin",
  "branch": "master",
  "remoteHeadSha": "4f859fdf3b5669894c5ed8a305ee5cd5f1fe2b7a",
  "retrievedAt": "2026-09-11T19:53:25.388268700Z"
}
```

One commit from inside that JSON file looks like this (trimmed):

```json
{
  "sha": "3dd0ec02f25c7c8f716fd62e1e3ffc6254004275",
  "shortMessage": "#1071: narrow tag-name check to XML metachars only",
  "fullMessage": "#1071: narrow tag-name check to XML metachars only\n\n...",
  "author": { "name": "...", "email": "...", "date": "2026-08-24T..." },
  "committer": { "name": "...", "email": "...", "date": "2026-08-24T..." },
  "commitDate": "2026-08-24T17:22:44Z",
  "parentShas": ["6b993e2e47b9a328a9d166a720b33fa7e3e58848"]
}
```

### 3. `/inspect_commits`

What happens, step by step:

It finds the JSON file from the previous step. For every commit up to the scan commit limit, it asks GitHub for linked pull requests and issues, in batches of up to 20 commits per request rather than one request each. For every non-merge commit, it computes the actual code change using JGit. It writes a new JSON file with all of this added, and shows you a few examples directly.

Example output:

```
Enriched 6 of 28 window commits.
Evidence file: workspace/exports/run_230a05ac..._enriched.json

Examples:
- 4f859fdf3b: 1 linked PR, 0 files changed (merge commit, skipped)
- 3dd0ec02f2: 1 linked PR, 3 files changed
- 6c140480: 1 linked PR, 0 files changed (merge commit, skipped)

Next, run /classify_bugs, which will score each commit on whether
it's a genuine bug fix.

If anything above is wrong, just run /inspect_commits again to start over.
```

The same commit from before now has two new fields added, `pullRequests` and `diff` (the diff is shortened here, the real one includes the full code change):

```json
{
  "sha": "3dd0ec02f25c7c8f716fd62e1e3ffc6254004275",
  "shortMessage": "#1071: narrow tag-name check to XML metachars only",
  "fullMessage": "#1071: narrow tag-name check to XML metachars only\n\n...",
  "pullRequests": [
    {
      "number": 1072,
      "title": "Reject invalid XML element names",
      "state": "MERGED",
      "closingIssues": [
        { "number": 1071, "title": "XML.toString: unescaped keys allow XML element injection (CWE-91)" }
      ],
      "commitShas": ["e2cfb5a64150455bf63ad334da9827e7671c9133", "6b993e2e47b9a328a9d166a720b33fa7e3e58848", "3dd0ec02f25c7c8f716fd62e1e3ffc6254004275"],
      "commitTotalCount": 3
    }
  ],
  "diff": {
    "isMergeCommit": false,
    "parentSha": "6b993e2e47b9a328a9d166a720b33fa7e3e58848",
    "files": [
      {
        "path": "src/main/java/org/json/XML.java",
        "changeType": "MODIFY",
        "patch": "@@ -233,80 +233,27 @@\n-    static void mustBeXmlName(String string) ...\n+    static void noXmlMetachars(String string) ..."
      }
    ]
  }
}
```

Alongside the commit list, the enriched file also carries a top-level `issueTimelines` array, one entry per issue discovered via `closingIssues` above, with data fetched from the issue's own side rather than through a commit or PR:

```json
{
  "number": 1071,
  "body": "... the issue's own description text ...",
  "referencedCommits": [
    { "sha": "4f859fdf3b5669894c5ed8a305ee5cd5f1fe2b7a", "isCrossRepository": false },
    { "sha": "3dd0ec02f25c7c8f716fd62e1e3ffc6254004275", "isCrossRepository": true }
  ],
  "crossReferencingPRs": [
    { "number": 1072, "title": "Reject invalid XML element names", "url": "https://github.com/stleary/JSON-java/pull/1072" }
  ]
}
```

### 4. `/classify_bugs`

What happens, step by step:

It finds the enriched JSON file and tells you how many commits are in it, then asks how many genuine bug fixes you'd like to find, at most. This is the bug target, and it's a different number from the scan commit limit you gave earlier: that one bounded how many commits get looked at, this one bounds how many accepted results you end up with.

It then works through the commits in groups of 5, newest first, one group at a time. For each group, it sends the commits to their own AI reviewer, who scores every commit in the group on three separate things, each from 0.0 to 1.0 with a short explanation: how the commit message reads, what the actual code change looks like, and what any linked pull requests or issues say. Once a group's scores come back, the orchestrator (not the reviewer) reads all three scores and explanations for every commit in that group and decides, for each one, bug fix or not, with its own short reasoning.

After each group, it checks its running total of genuine bug fixes found so far. If that total has reached the bug target, it stops there and does not look at any remaining groups. If not, and groups remain, it moves on to the next one. If it runs out of groups before reaching the target, it stops anyway and says plainly that the window simply didn't contain that many genuine bug fixes among the commits scanned.

Because groups are processed newest first and stop as soon as the target is reached, a small bug target means only the most recent commits get examined closely, older scanned commits may never be looked at.

It shows you a table of everything it actually examined, and writes one final JSON file with everything in it.

Example output, if the bug target was 1 and the first group already found it:

```
Enriched commits available: 6
How many genuine bug fixes would you like to find, at most?
```

You answer, say, `1`. Then:

| sha        | message | diff | PR/issue | verdict | reason |
|------------|---------|------|----------|---------|--------|
| 4f859fdf3b | 0.85    | n/a  | 0.90     | not counted (merge commit) | Merge commit, no diff to examine |
| 3dd0ec02f2 | 0.75    | 0.80 | 0.85     | bug fix | Rewrites the tag name check to close a real XML injection issue (CWE-91), confirmed by the linked issue and PR |

```
Examined 2 of 6 scanned commits, stopped early after reaching your
target of 1 genuine bug fix.
Final file: workspace/exports/run_230a05ac..._classified.json

This is as far as Causeway Mini currently goes, there's no next
command yet.

If anything above is wrong, just run /classify_bugs again to start over.
```

## The SQLite catalog

Every command above, after writing its own JSON file, also stores that file's contents into `workspace/causeway.db`. This happens automatically, there's nothing extra to type. It's additive: the JSON files are still what each command actually reads from the step before it, the database exists so the data can also be queried directly, across every run that's ever been done, rather than only ever being read one JSON file at a time.

Repeating a step (say, re-running `/inspect_repo` for the same repo and window) updates the existing rows rather than creating duplicates. Mining the same repo again with a *different* window creates a second, separate run, but both runs still share the one row for the repository itself, and a commit rediscovered by both runs is still just one row in `commits`, linked to both runs.

### How a commit connects to a PR or an issue

A commit belonging to a PR, a PR closing an issue, and a commit's own message merely mentioning an issue number are three different claims with three different strengths of evidence, and that's only the start of the list, so they're kept as distinctly-typed rows in one `relations` table (a `relation_type` column says which kind each row is, and `evidence` records the source text or GitHub field that established it), rather than collapsed into one generic "this commit relates to this issue" fact. That collapsing used to be a real bug here: attributing a PR's closing issue to every commit inside that PR overstated the connection for every commit in the PR except whichever one actually did the closing, since closing an issue is a fact about the PR as a whole, not about any one commit in it. Querying across relation types is just a `WHERE relation_type = '...'`, or no filter at all for "every relation touching this commit."

All seven relation types currently populated:

| `relation_type` | Endpoints | Source | Evidence |
|---|---|---|---|
| `commit_associated_pr` | commit, PR | `Commit.associatedPullRequests` | `"Commit.associatedPullRequests"` |
| `pr_contains_commit` | commit, PR | that PR's own `commits` list actually contains the sha | note + the PR's total commit count |
| `pr_closes_issue` | PR, issue | `PullRequest.closingIssuesReferences` | `"PullRequest.closingIssuesReferences"` |
| `pr_mentions_issue` | PR, issue | the issue's own `CrossReferencedEvent` timeline, source a PR | — |
| `commit_mentions_issue` | commit, issue | the issue's own `ReferencedEvent` timeline (GitHub-confirmed) | note, flags a cross-repository reference |
| `issue_mentions_commit` | commit, issue | a commit-SHA-shaped token found in the issue's own body text | the matched token |
| `commit_message_references_issue` | commit, issue | a raw `#123`-shaped match in the commit's own message | the matched `#N` |

`commit_associated_pr` and `pr_contains_commit` look at first glance like the same fact from two directions, they aren't: they come from two separately-queried GitHub facts that can disagree. A merge commit is a common real example, GitHub resolves it as "associated" with the PR it merged, but a PR's own commit list usually doesn't include the merge commit itself, only the commits that were merged in, so a merge commit typically gets a `commit_associated_pr` row with no matching `pr_contains_commit` row. `pr_mentions_issue` and `pr_closes_issue` can likewise both exist for the same PR/issue pair (a PR can reference an issue without its reference being the thing that closes it), or just one.

For example, after running the full pipeline once, this finds every genuine bug fix found so far, across every classification run, joining through whichever PR actually closed the linked issue (not just any PR the commit happened to be associated with):

```sql
SELECT c.sha, c.short_message, i.title AS issue_title, bc.verdict_rationale
FROM bug_classifications bc
JOIN commits c ON c.sha = bc.commit_sha
LEFT JOIN relations associated ON associated.relation_type = 'commit_associated_pr' AND associated.commit_sha = c.sha
LEFT JOIN relations closes ON closes.relation_type = 'pr_closes_issue' AND closes.pull_request_id = associated.pull_request_id
LEFT JOIN issues i ON i.id = closes.issue_id
WHERE bc.verdict = 1;
```

Currently populated relation types: `commit_belongs_to_pr`, `pr_closes_issue`, and `commit_message_references_issue` (a raw `#123`-shaped match in the commit's own message, unconfirmed by GitHub, kept separate from anything GitHub itself has resolved). A few more (`commit_mentions_issue`, `pr_references_issue`, `issue_mentions_commit_sha`) are designed for but not implemented yet, they need fetching data this pipeline doesn't fetch today (an issue's own timeline events and comment text, not just the commit → PR → issue chain).

### Discovered repositories

`/discover_repos` writes to the catalog directly, there's no JSON file and no separate `store` step for it. `search_repos_runs` holds the exact specification of each search (language, minimum stars, activity window, size bounds, fork/archived status, topics, license, and the requested result cap); `search_run_repositories` links that search to every repository it found, each with a snapshot of that repository's stars/language/last-pushed-date/etc. *at the moment that search found it*, since those change over time and a repository can legitimately be rediscovered by a later, differently-specified search. For example, this finds every repository any search has ever surfaced with at least 10,000 stars, and which searches found each one:

```sql
SELECT r.owner, r.repo, srr.stars, sr.language, sr.min_stars, sr.created_at
FROM search_run_repositories srr
JOIN repositories r ON r.id = srr.repository_id
JOIN search_repos_runs sr ON sr.id = srr.search_repos_run_id
WHERE srr.stars >= 10000
ORDER BY srr.stars DESC;
```

## How it all fits together

```mermaid
flowchart TD
    DR0["/discover_repos (optional)<br/>describe what you're looking for"]
    REPODISC(("repo-discovery<br/>agent"))
    DR0 --> REPODISC
    DB[(workspace/causeway.db)]
    GHSEARCH{{GitHub search API}}
    REPODISC -- "structured search flags" --> GHSEARCH
    GHSEARCH -- "candidate repos" --> REPODISC
    REPODISC -. "store immediately" .-> DB
    QUALIFY["qualify-repos (no agent, fixed check)<br/>issues enabled? closed issues/merged PRs? looks like tests?"]
    REPODISC -- "search results" --> QUALIFY
    QUALIFY -- "metadata + tree, no cloning" --> GH0{{GitHub}}
    QUALIFY -. "store qualification" .-> DB
    QUALIFY -- "pick a qualifying one" --> RC

    RC["/run_causeway<br/>ask for repo URL + time window"]
    GH1{{GitHub}}
    RC -- "repo URL" --> GH1
    GH1 -- "not found, up to 3 tries" --> RC
    GH1 -- "found it" --> IR

    IR0["/inspect_repo<br/>download or open the repo,<br/>list its remotes, ask which to use"]
    RC -- "repo + time window" --> IR0
    IR1["fetch from chosen remote,<br/>list its branches via GitHub,<br/>ask which branch to mine"]
    IR0 --> IR1
    IR["list commits in the window<br/>from the chosen branch,<br/>ask for a scan commit limit"]
    IR1 --> IR

    EV[["evidence file (JSON)<br/>commit list, plus remote/branch/SHA chosen,<br/>plus scan commit limit"]]
    IR --> EV
    DB[(workspace/causeway.db)]
    EV -. "store" .-> DB

    IC["/inspect_commits<br/>fetch PR/issue context, compute code diffs"]
    EV --> IC
    IC -- "batched requests" --> GH2{{GitHub}}
    GH2 -- "PR and issue details" --> IC

    EN[["enriched file (JSON)<br/>+ linked PRs/issues, + code diffs"]]
    IC --> EN
    EN -. "store" .-> DB

    CB["/classify_bugs<br/>ask: how many bug fixes to find? (bug target)"]
    EN --> CB

    NEXTBATCH["take the next group of 5 commits<br/>(newest first)"]
    CB --> NEXTBATCH
    REV(("bug-classifier<br/>reviewer"))
    NEXTBATCH --> REV
    JUDGE["orchestrator judges each commit<br/>in this group: bug fix, or not, with reasons"]
    REV --> JUDGE
    CHECK{"reached the bug target yet,<br/>or out of groups?"}
    JUDGE --> CHECK
    CHECK -- "no, groups remain" --> NEXTBATCH
    CHECK -- "yes" --> RESULT

    RESULT[["final file + table for you:<br/>every commit examined, its scores, its verdict"]]
    RESULT -. "store" .-> DB
```

Reading it top to bottom: `/discover_repos`, if you use it, describes what you're looking for in plain language, the `repo-discovery` agent translates that into structured search flags and runs the search, and every result is stored right away, not just whichever one you go on to pick. Those same results then go through `qualify-repos`, a fixed check invoked directly rather than through an agent (there's no translation judgment to make), which asks GitHub cheap questions about each one, no cloning involved, before you're shown anything. `/run_causeway` gets your repo and time window, checking the repo against GitHub as it goes, retrying up to 3 times on a bad URL (if you picked a repo from `/discover_repos`, its URL is already known here). `/inspect_repo` downloads or opens the repo, shows you its actual remotes and lets you pick one, then shows you that remote's actual branches (via GitHub) and lets you pick one, then lists the commits in your time window from that exact choice and asks for a scan commit limit once you can see how many there are. The output is a JSON file that every later step builds on. `/inspect_commits` adds GitHub's own context (linked pull requests and issues) and the real code differences for each commit. `/classify_bugs` asks for a bug target, then works through the commits in small groups, newest first, one group at a time, checking after each group whether enough genuine bug fixes have been found and stopping as soon as they have. What it actually examined ends up as a table and one complete file. Each of the three JSON files (dashed arrows above) also gets stored into the SQLite catalog as it's produced, so the data ends up queryable directly, not just readable one file at a time.

## Running the whole workflow

1. Make sure `.env` exists at the project root with a valid `GITHUB_TOKEN` in it.
2. Open this project in Claude Code.
2a. Don't already have a specific repo in mind? Type `/discover_repos` first, describe what you're looking for, and pick one of the results. Skip this if you already know the repo.
3. Type `/run_causeway` and answer its two questions: the repo URL and the time window.
4. Type `/inspect_repo`. Confirm or choose the remote if there's more than one, confirm or choose the branch to mine, then answer its question about the scan commit limit once it shows you the commit count.
5. Type `/inspect_commits` and let it run. No questions this time, it reuses everything from before.
6. Type `/classify_bugs`. Answer its question about the bug target once it shows you how many commits were scanned, then let it run. This is the step that takes the longest, since it works through commits in groups until it finds enough.
7. Read the table it prints, and open the final JSON file it mentions if you want the full detail behind every score.
8. If any step's result looks wrong, just run that same command again to redo it.
9. Everything that was written to a JSON file along the way is also sitting in `workspace/causeway.db`, queryable directly with any SQLite client, if you'd rather look at the data that way than open the JSON files one at a time.
