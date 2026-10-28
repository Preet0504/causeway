-- Causeway Mini schema. Applied with CREATE ... IF NOT EXISTS on every open,
-- so a fresh file and an existing file go through the same path.

CREATE TABLE IF NOT EXISTS searches (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT    NOT NULL,
  query      TEXT    NOT NULL,
  checked    INTEGER NOT NULL,
  qualifying INTEGER NOT NULL
);

-- One row per repository, holding what was learned the last time it was seen.
-- rejection_reason IS NULL means the repository qualifies for mining.
CREATE TABLE IF NOT EXISTS repos (
  full_name        TEXT PRIMARY KEY,
  url              TEXT    NOT NULL,
  description      TEXT,
  language         TEXT,
  stars            INTEGER NOT NULL,
  forks            INTEGER NOT NULL,
  size_kb          INTEGER NOT NULL,
  pushed_at        TEXT,
  default_branch   TEXT,
  closed_issues    INTEGER,
  merged_prs       INTEGER,
  rejection_reason TEXT,
  last_search_id   INTEGER REFERENCES searches(id),
  checked_at       TEXT    NOT NULL
);

-- A run is one pass of mining a repository at one pinned commit.
CREATE TABLE IF NOT EXISTS runs (
  id          TEXT PRIMARY KEY,
  created_at  TEXT    NOT NULL,
  full_name   TEXT    NOT NULL,
  url         TEXT    NOT NULL,
  branch      TEXT,
  pinned_sha  TEXT    NOT NULL,
  local_path  TEXT    NOT NULL,
  since       TEXT,
  scan_limit  INTEGER NOT NULL,
  enriched_at TEXT
);

CREATE TABLE IF NOT EXISTS commits (
  run_id         TEXT    NOT NULL REFERENCES runs(id),
  sha            TEXT    NOT NULL,
  authored_at    TEXT    NOT NULL,
  message        TEXT    NOT NULL,
  files          TEXT    NOT NULL,
  diff           TEXT    NOT NULL,
  diff_truncated INTEGER NOT NULL,
  PRIMARY KEY (run_id, sha)
);

-- Pull requests and issues tied to a commit. `how` records why we believe the
-- tie exists, because the ways differ in how much they can be trusted.
CREATE TABLE IF NOT EXISTS links (
  run_id TEXT    NOT NULL,
  sha    TEXT    NOT NULL,
  kind   TEXT    NOT NULL CHECK (kind IN ('pr', 'issue')),
  number INTEGER NOT NULL,
  title  TEXT    NOT NULL,
  body   TEXT    NOT NULL,
  state  TEXT    NOT NULL,
  url    TEXT    NOT NULL,
  how    TEXT    NOT NULL CHECK (how IN ('github_pr', 'closing_ref', 'pr_closing_ref', 'message_ref')),
  PRIMARY KEY (run_id, sha, number),
  FOREIGN KEY (run_id, sha) REFERENCES commits(run_id, sha)
);

-- One verdict per commit with the three signals that produced it.
-- linked_* are NULL when the commit has no linked PR or issue.
CREATE TABLE IF NOT EXISTS classifications (
  run_id              TEXT    NOT NULL,
  sha                 TEXT    NOT NULL,
  is_bug_fix          INTEGER NOT NULL,
  combined            REAL    NOT NULL,
  message_score       REAL    NOT NULL,
  message_explanation TEXT    NOT NULL,
  diff_score          REAL    NOT NULL,
  diff_explanation    TEXT    NOT NULL,
  linked_score        REAL,
  linked_explanation  TEXT,
  classified_at       TEXT    NOT NULL,
  PRIMARY KEY (run_id, sha),
  FOREIGN KEY (run_id, sha) REFERENCES commits(run_id, sha)
);
