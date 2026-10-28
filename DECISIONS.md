# Decisions

Each entry says what was decided, why, and what it costs. Newest decisions are not special; read in order.

## 1. Orphan branch, nothing carried over
The rebuild lives on `causeway-mini-rebuild`, an orphan branch whose only commit is empty. The earlier implementation on `causeway-mini` was not copied from, so this is a build from the brief alone.

## 2. Scala 3.3 LTS, sbt, a handful of libraries
Scala 3.3.4 (the LTS line), sbt 1.10, JGit for git, `sqlite-jdbc` for storage, upickle/ujson for JSON, MUnit for tests. GitHub is called with the JDK's own `java.net.http`, and arguments are parsed by hand; each of those would have been one more dependency for a few dozen lines. `sqlite-jdbc` bundles native SQLite for macOS, Linux and Windows, which is what makes "works on all three" cheap.

## 3. The CLI does everything deterministic; agents only judge
Calls to GitHub, SQL, validation, scoring arithmetic, the pass/fail threshold: all in Scala. Agents get two jobs only: translating a fuzzy request into flags, and scoring commit text. The chat commands are instructions for ordering and asking questions, and contain no logic worth testing.

## 4. The database is the only store, no JSON interchange files
Stages pass a **run id**, not files. One place holds state, `list-runs` can count anything, and there is no folder of UUID-named files to drift out of sync with the database. The cost is that a stage's output is not diffable as a file; `show-evidence` and `next-batch` print JSON when you need it.

## 5. A run is the unit; repos are a separate catalog
`repos` is what the last search learned about a repository (upserted by name, so re-searching never duplicates). `runs` is one pass at one pinned commit. Mining the same repo twice makes two runs, so you can compare passes and `list-runs` stays honest.

## 6. "Pinned" means a recorded SHA, not a branch name
`inspect-repo` clones, fetches if the clone exists, then checks out a detached HEAD at one commit: `--commit`, else the tip of `--branch`, else the remote's default branch. The SHA goes in the run. A branch name alone would mean something different next week.

## 7. Qualification: evidence matters, tests do not
A repo is rejected if archived, a fork, or having no closed issue and no merged PR, because it could never yield commit-to-issue evidence. Presence of tests was considered and dropped: it costs more API calls and is not a reliable bug-fix signal. Issues being disabled does not reject a repo that has merged PRs, since PRs are evidence too.
Cost: qualifying spends two search requests per candidate, which is the binding limit on `--max-results`.

## 8. Search values are validated, not escaped
Values going into GitHub's `q` string must match a strict character set, so `--language "Scala stars:>1"` is an error instead of an extra qualifier. Free-text keywords are limited to letters, digits, spaces, `.`, `_`, `-`.

## 9. Merge commits are skipped; the date window never stops the walk early
A merge's diff against its first parent is a whole branch, which says nothing about one fix. Separately, `--since` filters commits one by one rather than stopping at the first old one, because commit dates in real history are not monotonic (rebases, clock skew). A test builds a backdated commit and a mutation check confirmed the test fails if the early exit is reintroduced. Cost: a window far in the past can walk a large history.

## 10. Links keep the reason they were made
A commit→PR link from GitHub, a "fixes #N" in the message, a "fixes #N" in the PR body, and a bare "#N" mention are different claims. They are stored in `links.how` rather than collapsed into "related", so the classifier prompt and a reader can weigh them. A number GitHub reports as a PR is stored as `pr` even when reached through `#N` in a message (squash merges do this).

## 11. Three scored signals, combined by code
The classifier returns message, diff, and linked scores with explanations; the CLI computes the verdict. This makes the evidence inspectable per signal, keeps the model from also being the judge of its own threshold, and lets the threshold change without re-running a model. Weights 0.3/0.5/0.2 and threshold 0.6 are a first guess: the diff is the most direct evidence, linked text the most often missing. They are not tuned against data.

## 12. Replies are validated, partial failures are kept
`record-classification` stores the valid entries and returns the rejected ones with reasons; the command re-asks for just those once. A whole-batch rejection would throw away good work over one bad entry. Reasoning: scores outside 0..1, empty explanations, unknown shas, duplicates and ambiguous short shas are all realistic model slips.

## 13. Batches of five
The classifier takes at most 5 commits at a time: enough to amortize an agent call, few enough that each diff gets attention and a malformed reply costs little. Commits are served newest first; there is no pre-ranking, so a target of N bug fixes can take many batches in a repo where fixes are rare.

## 14. Containing the agents
- `bug-classifier`: an allow-list naming a single harmless tool, not a deny-list. A deny-list would have to name every tool, including MCP tools that vary by machine; an allow-list cannot miss one.
- `repo-discovery`: needs Bash for exactly one command. A prompt asking it to behave is not a control, so a `PreToolUse` hook in its frontmatter runs `guard-bash`, which rejects any command that is not one plain `tools/causeway search-repos …` (no `; & | < > $ \` \ ( ) { } !`, no newlines, balanced quotes) and fails closed on unreadable input.
- The guard is a CLI subcommand in Scala rather than a script, so it needs no Python or shell on Windows and is unit-tested with the rest.
- `.claude/settings.json` allows only `tools/causeway` in Bash and denies reading `.env`.
**Unverified:** I could not exercise the hook or the empty-ish tool allow-list inside a live Claude Code session in this build (new agent files are not loaded into a running session). The guard's logic is tested; the wiring (hook field names, frontmatter `hooks` support, behaviour of an unknown tool name in `tools:`) is from documentation knowledge and should be checked once by invoking each agent.

## 15. One launcher, Git Bash on Windows
`tools/causeway` is a POSIX shell script that loads `.env`, rebuilds the cached classpath only when a source file is newer than it, and runs `java` directly, so normal calls skip sbt. There is no `.cmd` twin: Claude Code on Windows runs Bash through Git Bash, so a shell launcher is what the slash commands would call anyway. `.gitattributes` pins its line endings to LF. Untested on Windows.

## 16. GitHub token: environment, then `gh`
`GITHUB_TOKEN`, `GH_TOKEN`, or `gh auth token`. No token is written to the database or printed. A rate limit is waited out up to 90 seconds, then reported with the reset time rather than hanging.

## 17. Tests
61 MUnit tests run offline: a fake GitHub (`FakeGitHub`), real git repositories built with JGit (`TestRepo`), and in-memory or temp-file SQLite. `CliSpec` runs the whole pipeline through the real entry point. One mutation check was done by hand (decision 9). Not covered by tests: the real HTTP client (exercised live instead), the launcher script, and any Claude agent.
