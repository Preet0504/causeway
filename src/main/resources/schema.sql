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

CREATE TABLE IF NOT EXISTS repositories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  owner TEXT NOT NULL,
  repo TEXT NOT NULL,
  url TEXT NOT NULL,
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
  scanned_commit_count INTEGER NOT NULL,
  stopped_early INTEGER NOT NULL,
  source_file TEXT NOT NULL,
  created_at TEXT NOT NULL
);

-- One row per `search-repos` invocation, capturing the exact structured
-- search specification that produced its results (language, minimum
-- stars, activity window, size bounds, fork/archived status, topics,
-- license, and the requested result cap), so a discovered repository can
-- always be traced back to exactly what was asked for. `topics` is stored
-- as a single comma-joined column rather than a separate join table: it's
-- part of one search's own specification, not a reusable dimension shared
-- across searches, so normalizing it further would add a table without
-- adding any real query power. Unlike the other three run tables, there is
-- no `source_file`: search-repos has no JSON output, its results are
-- inserted into the database directly as they're paginated.
CREATE TABLE IF NOT EXISTS search_repos_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id TEXT NOT NULL UNIQUE,
  language TEXT,
  min_stars INTEGER,
  pushed_within_months INTEGER,
  pushed_since_date TEXT,
  min_size_kb INTEGER,
  max_size_kb INTEGER,
  fork_status TEXT,
  archived_status TEXT,
  topics TEXT,
  license TEXT,
  max_results INTEGER NOT NULL,
  result_count INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

-- Many-to-many between a search run and the repositories it found, mirrors
-- `run_commits`: the same repository can legitimately be rediscovered by a
-- later, differently-specified search, and each discovery should keep its
-- own point-in-time snapshot (a repo's star count, last-pushed date, and
-- so on all change over time), rather than one search's write silently
-- overwriting another's.
CREATE TABLE IF NOT EXISTS search_run_repositories (
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

-- One row per repository, the CURRENT answer to "is this worth cloning",
-- re-checking replaces the row rather than layering a historical record
-- the way search_run_repositories does: nothing depends on reproducing a
-- stale qualification check, only on the latest one. Cheap, pre-clone
-- signals only, no cloning involved: has_issues and the closed-issue/
-- merged-PR counts come from repo metadata and the search API,
-- appears_to_have_tests comes from pattern-matching file paths in the
-- default branch's tree (fetched in one request, no file content, no
-- clone). appears_to_have_tests is a soft, heuristic signal (a repo could
-- easily have tests in an unconventional location this doesn't recognize)
-- so it is recorded but does not by itself fail `qualifies`; has_issues
-- being false, or having no closed issues and no merged PRs at all (no
-- possible source of bug-fix evidence), are the two hard disqualifiers.
CREATE TABLE IF NOT EXISTS repository_qualifications (
  repository_id INTEGER PRIMARY KEY REFERENCES repositories(id),
  has_issues INTEGER NOT NULL,
  closed_issue_count INTEGER NOT NULL,
  merged_pr_count INTEGER NOT NULL,
  appears_to_have_tests INTEGER NOT NULL,
  test_evidence_paths TEXT,
  tree_truncated INTEGER NOT NULL,
  qualifies INTEGER NOT NULL,
  rejection_reason TEXT,
  checked_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS commits (
  sha TEXT PRIMARY KEY,
  repository_id INTEGER NOT NULL REFERENCES repositories(id),
  short_message TEXT NOT NULL,
  full_message TEXT NOT NULL,
  author_name TEXT,
  author_email TEXT,
  author_date TEXT,
  committer_name TEXT,
  committer_email TEXT,
  committer_date TEXT,
  first_seen_inspect_repo_run_id INTEGER REFERENCES inspect_repo_runs(id)
);

CREATE TABLE IF NOT EXISTS commit_parents (
  commit_sha TEXT NOT NULL REFERENCES commits(sha),
  parent_sha TEXT NOT NULL,
  parent_order INTEGER NOT NULL,
  PRIMARY KEY (commit_sha, parent_order)
);

CREATE TABLE IF NOT EXISTS run_commits (
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

-- Stub for a future detect-tests capability: whether a commit shipped its
-- own regression test. Not populated by anything yet.
CREATE TABLE IF NOT EXISTS test_observations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  commit_sha TEXT NOT NULL REFERENCES commits(sha),
  has_regression_test INTEGER,
  test_file_path TEXT,
  detection_method TEXT,
  observed_at TEXT
);

-- Stub for a future materialize-commit capability: a commit's source tree
-- checked out to disk for building/testing. Not populated by anything yet.
CREATE TABLE IF NOT EXISTS materialized_revisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  commit_sha TEXT NOT NULL REFERENCES commits(sha),
  checkout_path TEXT,
  materialized_at TEXT
);

-- Stub for a future build capability: whether a materialized revision
-- actually compiles. Not populated by anything yet.
CREATE TABLE IF NOT EXISTS build_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  materialized_revision_id INTEGER REFERENCES materialized_revisions(id),
  status TEXT,
  log_path TEXT,
  started_at TEXT,
  finished_at TEXT
);
