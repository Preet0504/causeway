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
-- which two, and what the edge means, is determined by relation_type:
--   commit_belongs_to_pr             (commit_sha, pull_request_id)
--   pr_closes_issue                  (pull_request_id, issue_id)
--   commit_message_references_issue  (commit_sha, issue_id) -- a raw #N-shaped
--     match in the commit's own message, unconfirmed by GitHub, so it is
--     kept distinct from the future GitHub-confirmed commit_mentions_issue.
-- Future relation types (need per-issue timeline/comment data not fetched
-- yet): commit_mentions_issue, pr_references_issue, issue_mentions_commit_sha.
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
