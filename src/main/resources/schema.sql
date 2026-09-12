-- Causeway Mini's repository catalog. Every table with a natural-key UNIQUE
-- constraint is meant to be upserted into (INSERT ... ON CONFLICT ... DO
-- UPDATE), never plain-inserted, so re-running the same mining operation
-- updates or reuses existing rows instead of creating duplicates.
--
-- Runs get one table per pipeline stage (inspect_repo_runs,
-- inspect_commits_runs, classify_bugs_runs) rather than one shared table
-- with a stage column, so each table only has the columns that actually
-- apply to that stage, and downstream foreign keys point at the specific
-- stage that produced them (a bug_classification always points at a
-- classify_bugs_run, never a generic "run"). A single run_id is shared
-- across every stage of one mining pass (inspect-commits and classify-bugs
-- both carry forward the run_id their upstream stage used, rather than
-- minting a new one), it just lives in a different table per stage.

-- Qualification columns (has_issues through checked_at) live directly on
-- this table, as nullable columns, rather than a separate
-- repository_qualifications table: qualifying a repository is a strict
-- one-to-one fact about it (repository_id would be that table's own
-- primary key), not a one-to-many relationship, so a separate table would
-- only add a join every place qualification data is read, for no
-- normalization benefit. They stay NULL until `qualify-repos` actually
-- checks a repository; re-checking overwrites them in place, there is no
-- history of past checks, only the latest one, since nothing depends on
-- reproducing a stale qualification the way reproducing a past search's
-- exact result set matters (see search_repos_run_repositories below, which
-- does keep history, for that contrast). has_issues is a repo setting that
-- can't be derived from a count (a repo can have issues enabled with zero
-- ever filed, a different fact than disabled); has_issues = false, or
-- having no closed issue and no merged PR at all (no possible source of
-- bug-fix evidence), are the two hard disqualifiers. There is no separate
-- `qualifies` boolean column: it would always be exactly
-- `rejection_reason IS NULL`, a persisted column that could never actually
-- disagree with `rejection_reason`'s own nullability is pure redundancy,
-- so "qualifies" is a query condition (`rejection_reason IS NULL`), not a
-- stored fact. test_file_count is a soft, pattern-matched heuristic (real
-- tests can live somewhere this doesn't recognize) and never by itself
-- produces a rejection reason.
-- description/repo_created_at/forks_count/topics/default_branch are stable
-- identity-ish facts (GitHub's repo metadata, not a point-in-time
-- snapshot), populated by whichever tool (search-repos or qualify-repos)
-- sees this repository first, COALESCE-preserved so neither overwrites the
-- other with null. current_stars through current_license, by contrast, are
-- the single "what does this repository currently look like" snapshot,
-- written by both search-repos and qualify-repos every time either one
-- sees a repository (same COALESCE-preserving upsert as the identity
-- fields above, so whichever ran more recently simply wins), deliberately
-- distinct from search_repos_run_repositories' per-search historical
-- snapshot below (that one keeps every search's own point-in-time
-- snapshot, this one keeps only the latest known state). A repository
-- qualified directly (--owner/--repo, never discovered via search) still
-- gets this data recorded, via the same shared upsert path.
CREATE TABLE IF NOT EXISTS repositories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner TEXT NOT NULL,
  repo TEXT NOT NULL,
  url TEXT NOT NULL,
  description TEXT,
  repo_created_at TEXT,
  forks_count INTEGER,
  topics TEXT,
  default_branch TEXT,
  has_issues INTEGER,
  closed_issue_count INTEGER,
  open_issue_count INTEGER,
  merged_pr_count INTEGER,
  open_pr_count INTEGER,
  test_file_count INTEGER,
  test_evidence_paths TEXT,
  tree_truncated INTEGER,
  current_stars INTEGER,
  current_language TEXT,
  current_size_kb INTEGER,
  current_archived INTEGER,
  current_fork INTEGER,
  current_license TEXT,
  rejection_reason TEXT,
  checked_at TEXT,
  UNIQUE (owner, repo)
);

CREATE TABLE IF NOT EXISTS repository_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  remote_name TEXT NOT NULL,
  branch TEXT NOT NULL,
  remote_head_sha TEXT NOT NULL,
  retrieved_at TEXT NOT NULL,
  UNIQUE (repository_id, remote_name, branch, remote_head_sha)
);

CREATE TABLE IF NOT EXISTS inspect_repo_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL UNIQUE,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  repository_snapshot_id INTEGER NOT NULL REFERENCES repository_snapshots(id),
  window_requested TEXT NOT NULL,
  window_since_date TEXT NOT NULL,
  scan_commit_limit INTEGER,
  window_commit_count INTEGER NOT NULL,
  source_file TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS inspect_commits_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL UNIQUE,
  inspect_repo_run_id INTEGER REFERENCES inspect_repo_runs(id),
  enriched_commit_count INTEGER NOT NULL,
  source_file TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS classify_bugs_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL UNIQUE,
  inspect_commits_run_id INTEGER REFERENCES inspect_commits_runs(id),
  bug_target INTEGER NOT NULL,
  examined_commit_count INTEGER NOT NULL,
  stopped_early INTEGER NOT NULL,
  source_file TEXT NOT NULL,
  created_at TEXT NOT NULL
);

-- One row per `search-repos` invocation, capturing the exact structured
-- search specification that produced its results (language, minimum
-- stars, activity window, size bounds, fork count bounds, repo age
-- bounds, fork/archived status, topic filter, license, and the requested
-- result cap), so a discovered repository can always be traced back to
-- exactly what was asked for. `topic_filter` is stored as a single
-- comma-joined column rather than a separate join table: it's part of one
-- search's own specification, not a reusable dimension shared across
-- searches, so normalizing it further would add a table without adding
-- any real query power. It's named `topic_filter`, not `topics`, to
-- distinguish it from `repositories.topics` (a repository's own actual
-- topics, a completely different fact from what topic a search filtered
-- on). Unlike the other three run tables, there is no `source_file`:
-- search-repos has no JSON output, its results are inserted into the
-- database directly as they're paginated.
--
-- min/max_repo_age_years are the requested durations (mirroring
-- pushed_within_months); created_after_date/created_before_date are
-- those durations resolved to absolute dates at the time of the search
-- (mirroring pushed_since_date), stored alongside the requested duration
-- for the same provenance reason. Age and creation date point in opposite
-- directions: an *older* repository (a larger min-age) has a *smaller*
-- (earlier) creation date, so min_repo_age_years resolves to
-- created_before_date (must have been created on or before that date to
-- be at least that old) and max_repo_age_years resolves to
-- created_after_date (must have been created on or after that date to be
-- at most that old), not the other way around.
CREATE TABLE IF NOT EXISTS search_repos_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL UNIQUE,
  language TEXT,
  min_stars INTEGER,
  max_stars INTEGER,
  pushed_within_months INTEGER,
  pushed_since_date TEXT,
  min_size_kb INTEGER,
  max_size_kb INTEGER,
  min_forks INTEGER,
  max_forks INTEGER,
  min_repo_age_years INTEGER,
  max_repo_age_years INTEGER,
  created_before_date TEXT,
  created_after_date TEXT,
  fork_status TEXT,
  archived_status TEXT,
  topic_filter TEXT,
  license TEXT,
  max_results INTEGER NOT NULL,
  result_count INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

-- Many-to-many between a search run and the repositories it found, mirrors
-- `inspect_repo_run_commits`: the same repository can legitimately be
-- rediscovered by a later, differently-specified search, and each
-- discovery should keep its own point-in-time snapshot (a repo's star
-- count, last-pushed date, and so on all change over time), rather than
-- one search's write silently overwriting another's.
CREATE TABLE IF NOT EXISTS search_repos_run_repositories (
  search_repos_run_id INTEGER NOT NULL REFERENCES search_repos_runs(id),
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  stars INTEGER,
  language TEXT,
  pushed_at TEXT,
  size_kb INTEGER,
  is_fork INTEGER,
  is_archived INTEGER,
  license TEXT,
  PRIMARY KEY (search_repos_run_id, repository_id)
);

CREATE TABLE IF NOT EXISTS commits (
  sha TEXT PRIMARY KEY,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  short_message TEXT NOT NULL,
  full_message TEXT NOT NULL,
  first_seen_inspect_repo_run_id INTEGER REFERENCES inspect_repo_runs(id)
);

CREATE TABLE IF NOT EXISTS inspect_repo_run_commits (
  inspect_repo_run_id INTEGER NOT NULL REFERENCES inspect_repo_runs(id),
  commit_sha TEXT NOT NULL REFERENCES commits(sha),
  PRIMARY KEY (inspect_repo_run_id, commit_sha)
);

CREATE TABLE IF NOT EXISTS pull_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  number INTEGER NOT NULL,
  title TEXT,
  url TEXT,
  state TEXT,
  first_seen_inspect_commits_run_id INTEGER REFERENCES inspect_commits_runs(id),
  UNIQUE (repository_id, number)
);

CREATE TABLE IF NOT EXISTS issues (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  number INTEGER NOT NULL,
  title TEXT,
  url TEXT,
  first_seen_inspect_commits_run_id INTEGER REFERENCES inspect_commits_runs(id),
  UNIQUE (repository_id, number)
);

-- A typed edge between two of {commit, pull_request, issue}. Each relation
-- kind means something genuinely different (a commit belonging to a PR is
-- not the same claim as that PR closing an issue, which is not the same
-- claim as the commit's own message merely mentioning an issue number), and
-- collapsing them into one generic "this commit relates to this issue" fact
-- was a real bug: attributing a PR's closing-issue to every commit inside
-- that PR overstates the connection for every commit except whichever one
-- actually did the closing. One table with a `relation_type` discriminator
-- (rather than a table per type) is used because every kind here is the
-- same shape, an edge with an optional evidence note, and future
-- confidence-scoring needs "every relation touching this commit, of any
-- type" to be one query, not a UNION across tables.
--
-- Exactly two of (commit_sha, pull_request_id, issue_id) are set per row;
-- which two, and what the edge means, is determined by relation_type. Two
-- relation types can describe the same pair of endpoints and still both be
-- true, or disagree, because they come from genuinely different GitHub
-- queries:
--   commit_associated_pr             (commit_sha, pull_request_id)
--     Commit.associatedPullRequests: GitHub's own resolution of which
--     PR(s) a commit belongs to.
--   pr_contains_commit               (commit_sha, pull_request_id)
--     PullRequest.commits: whether this exact sha is still in that PR's
--     own commit list. Can disagree with commit_associated_pr (a rebase or
--     force-push can drop a commit from a PR's list while GitHub still
--     resolves the commit as associated with it), which is exactly why
--     these are two relation types, not one.
--   pr_closes_issue                  (pull_request_id, issue_id)
--     PullRequest.closingIssuesReferences: a fact about the PR as a whole,
--     never attributed to one commit inside it.
--   pr_mentions_issue                (pull_request_id, issue_id)
--     An issue's CrossReferencedEvent whose source is that PR: the PR
--     references the issue, which does not imply it closes it (a PR can
--     have both a pr_mentions_issue and a pr_closes_issue row for the same
--     pair, or just one).
--   commit_mentions_issue            (commit_sha, issue_id)
--     An issue's ReferencedEvent naming that commit: GitHub's own
--     confirmation that the commit's message referenced the issue.
--   issue_mentions_commit            (commit_sha, issue_id)
--     A commit-SHA-shaped token found in the issue's own body text,
--     matched as a prefix against a commit this run knows about. A plain
--     text heuristic, not GitHub-confirmed, we don't fetch issue comments,
--     only the body.
--   commit_message_references_issue  (commit_sha, issue_id)
--     A raw #N-shaped match in the commit's own message, unconfirmed by
--     GitHub (it doesn't know whether #N is really an issue or a PR
--     number in this repo), kept distinct from the GitHub-confirmed
--     commit_mentions_issue above.
CREATE TABLE IF NOT EXISTS relations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  relation_type TEXT NOT NULL,
  commit_sha TEXT REFERENCES commits(sha),
  pull_request_id INTEGER REFERENCES pull_requests(id),
  issue_id INTEGER REFERENCES issues(id),
  evidence TEXT,
  first_seen_inspect_commits_run_id INTEGER REFERENCES inspect_commits_runs(id)
);

-- SQLite treats every NULL as distinct for UNIQUE purposes, so a plain
-- UNIQUE(relation_type, commit_sha, pull_request_id, issue_id) would not
-- dedupe rows where the unused endpoint is NULL (e.g. two pr_closes_issue
-- rows for the same PR/issue pair, both with commit_sha NULL, would not
-- conflict). COALESCE-ing each endpoint to a non-NULL sentinel in an
-- expression index makes the natural key actually enforce uniqueness.
CREATE UNIQUE INDEX IF NOT EXISTS relations_natural_key ON relations (
  relation_type,
  COALESCE(commit_sha, ''),
  COALESCE(pull_request_id, -1),
  COALESCE(issue_id, -1)
);

-- relations_natural_key is an expression index (COALESCE-wrapped columns),
-- so it doesn't serve a plain `WHERE commit_sha = ?` (or pull_request_id/
-- issue_id) lookup, which is exactly how relations get looked up when
-- walking from a commit/PR/issue to what it's connected to. Plain indexes
-- on each endpoint make those lookups use an index instead of a full scan.
CREATE INDEX IF NOT EXISTS relations_commit_sha ON relations (commit_sha);
CREATE INDEX IF NOT EXISTS relations_pull_request_id ON relations (pull_request_id);
CREATE INDEX IF NOT EXISTS relations_issue_id ON relations (issue_id);

CREATE TABLE IF NOT EXISTS bug_classifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  commit_sha TEXT NOT NULL REFERENCES commits(sha),
  classify_bugs_run_id INTEGER NOT NULL REFERENCES classify_bugs_runs(id),
  message_score REAL,
  message_explanation TEXT,
  diff_score REAL,
  diff_explanation TEXT,
  pr_score REAL,
  pr_explanation TEXT,
  verdict INTEGER,
  verdict_rationale TEXT,
  counts_toward_target INTEGER,
  UNIQUE (commit_sha, classify_bugs_run_id)
);

-- No stub tables for not-yet-built capabilities (detect-tests,
-- materialize-commit, build): none of them are populated by anything yet,
-- and an empty, unused table is speculative schema for a capability that
-- doesn't exist. Add test_observations / materialized_revisions /
-- build_runs back (they were sketched out once, see DECISIONS.md) when the
-- corresponding capability actually gets built and needs somewhere to
-- write.
