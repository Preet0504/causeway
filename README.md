# Causeway Mini

A Claude Code workflow that mines public GitHub repositories for bug-fix commits and keeps what it finds in a local SQLite database.

You drive it from chat with slash commands. Each command is a thin wrapper around `tools/causeway`, a compiled Scala 3 CLI that does the real work: every GitHub call, every database write, every deterministic rule. Two subagents do the two jobs that need judgment, and neither can run arbitrary commands.

## The pipeline

| Step | Slash command | CLI subcommand(s) | What happens |
|---|---|---|---|
| 1. Discover | `/discover_repos <description>` | `search-repos`, `list-repos` | `repo-discovery` agent turns plain language into GitHub search flags; the CLI searches and keeps only repos that *could* yield evidence (at least one closed issue or merged PR; not archived, not a fork) |
| 2. Pin | `/inspect_repo <url>` | `inspect-repo` | Clones the repo, checks out one exact commit, records the recent non-merge commits with their diffs. A **run** is created |
| 3. Enrich | `/enrich_commits <run>` | `enrich` | Attaches the pull requests and issues linked to each commit, with the reason each link is believed |
| 4. Classify | `/classify_bugs <run>` | `next-batch`, `record-classification`, `show-evidence` | `bug-classifier` agent scores batches of 5 commits on three signals; the CLI validates, combines, and stores the scores. Repeats until your bug-fix target is reached |
| Browse | `/list_runs`, `/list_repos` | `list-runs`, `list-repos` | Past runs with how many bug fixes each found; every qualifying repo ever discovered |
| All of 2–4 | `/run_causeway <url>` | | Asks its questions once, then runs steps 2, 3 and 4 |

Everything lives in `data/causeway.db` (SQLite); clones live in `data/repos/`. Both are git-ignored.

## Install

You need a **JDK 17 or newer** and **sbt** on your `PATH`, plus `git`. A GitHub token is strongly recommended: unauthenticated search is limited to 10 requests a minute.

| OS | JDK + sbt |
|---|---|
| macOS | `brew install openjdk@21 sbt` (or [SDKMAN](https://sdkman.io)) |
| Linux | SDKMAN (`sdk install java`, `sdk install sbt`) or your package manager |
| Windows | Install [Git for Windows](https://gitforwindows.org) (Claude Code needs its Git Bash anyway), then a JDK and sbt, and run everything from Git Bash |

```bash
git clone <this repo> && cd causeway-mini
echo 'GITHUB_TOKEN=ghp_yourtoken' > .env     # optional if `gh auth login` is done
tools/causeway help                           # first call compiles (about a minute)
```

The token is found in this order: `GITHUB_TOKEN`, `GH_TOKEN`, `gh auth token`. `.env` is loaded by the launcher and denied to the agents in `.claude/settings.json`. A token needs no scopes for public repositories.

Run the tests with `sbt test`.

## Using it

```
/discover_repos active Scala libraries with 300 to 3000 stars, under 20 MB
/inspect_repo softwaremill/elasticmq
/enrich_commits 1ef3fead
/classify_bugs 1ef3fead
/list_runs
```

### Real output

`tools/causeway search-repos --language Scala --min-stars 300 --max-stars 3000 --pushed-after 2026-03-01 --max-size-kb 20000 --max-results 3`, abridged:

```
language:Scala stars:300..3000 pushed:>=2026-03-01 size:<=20000 fork:false archived:false is:public
softwaremill/elasticmq            2950 stars   241 closed issues   757 merged PRs   qualifies
spark-jobserver/spark-jobserver   2833 stars   713 closed issues   455 merged PRs   qualifies
scalatra/scalatra                 2648 stars   388 closed issues  1469 merged PRs   qualifies
```

`tools/causeway inspect-repo --url softwaremill/elasticmq --scan-limit 15`:

```json
{ "run": "1ef3fead", "repo": "softwaremill/elasticmq", "branch": "master",
  "pinned_sha": "2ace8f5b2e6e540e0e89af96d4a006f836681fca", "commits": 15, "scan_limit": 15 }
```

`tools/causeway enrich --run 1ef3fead` linked PRs to 15 of 15 commits with no errors.

### What a classification looks like

`show-evidence` returns, for each commit, the verdict and the evidence behind it. Shape (from the end-to-end test, not a live model run):

```json
{ "sha": "…", "subject": "Fix NullPointerException when input is null", "is_bug_fix": true, "combined": 0.92,
  "evidence": {
    "message": { "score": 0.95, "explanation": "says Fix NullPointerException" },
    "diff":    { "score": 0.90, "explanation": "wraps the argument in Option" },
    "linked":  { "score": 0.90, "explanation": "issue 7 reports parse(null) throwing" } },
  "links": [ { "kind": "issue", "number": 7, "how": "closing_ref" }, { "kind": "pr", "number": 8, "how": "github_pr" } ] }
```

## How a verdict is reached

The agent never says "yes" or "no". It scores three signals from 0.0 to 1.0, each with an explanation:

| Signal | Looks at | Weight |
|---|---|---|
| `message` | the commit message | 0.3 |
| `diff` | the code change | 0.5 |
| `linked` | titles and bodies of linked PRs/issues | 0.2 |

The CLI computes the weighted mean and calls it a bug fix at **0.6 or above**. With no linked PR or issue the other two weights are rescaled, so a commit is not punished for having no paper trail. The weights and threshold are judgment calls, not measured; they are constants at the top of `Classify.scala`.

The CLI also checks the agent's reply: scores must be in range, explanations non-empty, shas must belong to the run, and a commit with links must have a `linked` score. Bad entries are rejected with a reason and re-asked; they are never stored.

Link reasons (`how`): `github_pr` (GitHub says the commit is in that PR), `closing_ref` (the commit message says "fixes #N"), `pr_closing_ref` (the PR body says it), `message_ref` (the message merely mentions #N).

## What keeps the agents contained

| Agent | Restriction | Enforced by |
|---|---|---|
| `repo-discovery` | Bash only, and only `tools/causeway search-repos …` as one plain command | a `PreToolUse` hook in its frontmatter runs `tools/causeway guard-bash --allow search-repos`, which blocks chaining, redirects, substitution and any other program |
| `bug-classifier` | No file, shell, or network access | its `tools:` allow-list names only a harmless tool |
| the chat commands | `Bash(tools/causeway:*)` only | `allowed-tools` in each command and `.claude/settings.json` |

Neither agent can open the database; only the CLI does. The guard is unit-tested (`GuardSpec`) including a dozen injection shapes.

## Known limits

- **Classification quality is unmeasured.** Nothing here has been evaluated against labelled bug fixes. Treat scores as evidence to read, not as ground truth.
- **Bug fixes are not guaranteed to have been fixed *by* the linked PR.** `message_ref` links in particular can be incidental.
- Merge commits are skipped; squash-merge commits are kept and usually carry the PR number in the subject.
- Diffs are stored up to 20,000 characters per commit (the agent sees 6,000); the rest stays in the clone.
- GitHub search allows 30 requests a minute with a token; qualifying costs two requests per candidate, so large `--max-results` is slow.
- Not verified live in a Claude Code session: the `repo-discovery` hook and the `bug-classifier` tool restriction. Everything in the CLI is covered by tests and was exercised against GitHub. See `DECISIONS.md`.
- Windows support rests on Git Bash running `tools/causeway`; it has not been run on Windows.
