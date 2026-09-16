package causeway.mini

import munit.FunSuite

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers `list-runs`, the query that lets `/inspect_commits`/`/classify_bugs`
  * pick a file to reuse by asking the database (which already knows which
  * repository and window each run belongs to) instead of crawling
  * `workspace/exports/<owner>-<repo>/json/`, where a run is still just a
  * `run_<uuid>...` file with no window or date visible without opening it.
  */
class ListRunsSpec extends FunSuite:

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-list-runs-test", ".db").toFile
    dbFile.delete()
    dbFile.deleteOnExit()
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")
    Store.applySchema(conn)
    conn

  /** Inserts an `inspect_repo_runs` row (plus the repository/snapshot it
    * needs) with an explicit `created_at`, so tests can control recency
    * deterministically instead of racing `datetime('now')`'s one-second
    * resolution across fast inserts.
    */
  private def insertInspectRepoRun(
      conn: Connection,
      runId: String,
      owner: String,
      repo: String,
      windowRequested: String,
      sourceFile: String,
      createdAt: String
  ): Long =
    val repositoryId = Store.upsertRepository(conn, owner, repo, s"https://github.com/$owner/$repo")
    Store.exec(
      conn,
      "INSERT INTO repository_snapshots (repository_id, remote_name, branch, remote_head_sha, retrieved_at) " +
        "VALUES (?, 'origin', 'main', 'deadbeef', '2026-01-01T00:00:00Z')",
      Seq(repositoryId)
    )
    val snapshotId = Store.queryId(
      conn,
      "SELECT id FROM repository_snapshots WHERE repository_id = ? AND remote_head_sha = 'deadbeef'",
      Seq(repositoryId)
    )
    Store.exec(
      conn,
      "INSERT INTO inspect_repo_runs " +
        "(run_id, repository_id, repository_snapshot_id, window_requested, window_since_date, window_commit_count, source_file, created_at) " +
        "VALUES (?, ?, ?, ?, '2026-06-01', 10, ?, ?)",
      Seq(runId, repositoryId, snapshotId, windowRequested, sourceFile, createdAt)
    )
    Store.queryId(conn, "SELECT id FROM inspect_repo_runs WHERE run_id = ?", Seq(runId))

  private def insertInspectCommitsRun(
      conn: Connection,
      runId: String,
      inspectRepoRunId: Option[Long],
      sourceFile: String,
      createdAt: String
  ): Long =
    Store.exec(
      conn,
      "INSERT INTO inspect_commits_runs (run_id, inspect_repo_run_id, enriched_commit_count, source_file, created_at) " +
        "VALUES (?, ?, 5, ?, ?)",
      Seq(runId, inspectRepoRunId.map(_.asInstanceOf[Any]).orNull, sourceFile, createdAt)
    )
    Store.queryId(conn, "SELECT id FROM inspect_commits_runs WHERE run_id = ?", Seq(runId))

  private def insertClassifyBugsRun(
      conn: Connection,
      runId: String,
      inspectCommitsRunId: Option[Long],
      sourceFile: String,
      createdAt: String,
      bugTarget: Int = 3,
      examinedCommitCount: Int = 5,
      stoppedEarly: Boolean = false
  ): Unit =
    Store.exec(
      conn,
      "INSERT INTO classify_bugs_runs (run_id, inspect_commits_run_id, bug_target, examined_commit_count, stopped_early, source_file, created_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
      Seq(runId, inspectCommitsRunId.map(_.asInstanceOf[Any]).orNull, bugTarget, examinedCommitCount, stoppedEarly, sourceFile, createdAt)
    )

  test("inspect-repo runs are listed newest first, capped at the given limit, with owner/repo/window/sourceFile") {
    val conn = openTempDb()
    try
      insertInspectRepoRun(conn, "run-old", "acme", "widgets", "past-1-month", "workspace/exports/run-old.json", "2026-01-01T00:00:00Z")
      insertInspectRepoRun(conn, "run-new", "acme", "gadgets", "past-3-months", "workspace/exports/run-new.json", "2026-06-01T00:00:00Z")

      val runs = ListRuns.fetchInspectRepoRuns(conn, limit = 10)
      assertEquals(runs.map(_.runId), List("run-new", "run-old"), "newest first")

      val newest = runs.head
      assertEquals(newest.owner, "acme")
      assertEquals(newest.repo, "gadgets")
      assertEquals(newest.windowRequested, "past-3-months")
      assertEquals(newest.sourceFile, "workspace/exports/run-new.json")

      assertEquals(ListRuns.fetchInspectRepoRuns(conn, limit = 1).map(_.runId), List("run-new"), "limit is respected")
    finally conn.close()
  }

  test("inspect-commits runs are listed newest first, joined back to the repository that was mined") {
    val conn = openTempDb()
    try
      val repoRunId = insertInspectRepoRun(conn, "run-A", "acme", "widgets", "past-3-months", "workspace/exports/run-A.json", "2026-01-01T00:00:00Z")
      insertInspectCommitsRun(conn, "enrich-old", Some(repoRunId), "workspace/exports/run-A_enriched.json", "2026-01-02T00:00:00Z")
      insertInspectCommitsRun(conn, "enrich-new", Some(repoRunId), "workspace/exports/run-A_enriched2.json", "2026-01-03T00:00:00Z")

      val runs = ListRuns.fetchInspectCommitsRuns(conn, limit = 10)
      assertEquals(runs.map(_.runId), List("enrich-new", "enrich-old"), "newest first")
      assertEquals(runs.head.owner, "acme")
      assertEquals(runs.head.repo, "widgets")
      assertEquals(runs.head.windowRequested, "past-3-months")
      assertEquals(runs.head.sourceFile, "workspace/exports/run-A_enriched2.json")
    finally conn.close()
  }

  test("an inspect-commits run with no resolvable inspect-repo run still lists, with owner/repo/window shown as unknown") {
    val conn = openTempDb()
    try
      insertInspectCommitsRun(conn, "orphan-enrich", inspectRepoRunId = None, "workspace/exports/orphan_enriched.json", "2026-01-01T00:00:00Z")

      val runs = ListRuns.fetchInspectCommitsRuns(conn, limit = 10)
      assertEquals(runs.map(_.runId), List("orphan-enrich"))
      assertEquals(runs.head.owner, "?")
      assertEquals(runs.head.repo, "?")
      assertEquals(runs.head.sourceFile, "workspace/exports/orphan_enriched.json")
    finally conn.close()
  }

  test("classify-bugs runs are listed newest first, joined back to the repository, carrying bug target/examined count/stopped early") {
    val conn = openTempDb()
    try
      val repoRunId = insertInspectRepoRun(conn, "run-A", "acme", "widgets", "past-3-months", "workspace/exports/run-A.json", "2026-01-01T00:00:00Z")
      val commitsRunId = insertInspectCommitsRun(conn, "run-A", Some(repoRunId), "workspace/exports/run-A_enriched.json", "2026-01-01T00:00:01Z")
      insertClassifyBugsRun(conn, "classify-old", Some(commitsRunId), "workspace/exports/run-A_classified.json", "2026-01-02T00:00:00Z", bugTarget = 2, examinedCommitCount = 4, stoppedEarly = true)
      insertClassifyBugsRun(conn, "classify-new", Some(commitsRunId), "workspace/exports/run-A_classified2.json", "2026-01-03T00:00:00Z", bugTarget = 5, examinedCommitCount = 10, stoppedEarly = false)

      val runs = ListRuns.fetchClassifyBugsRuns(conn, limit = 10)
      assertEquals(runs.map(_.runId), List("classify-new", "classify-old"), "newest first")

      val newest = runs.head
      assertEquals(newest.stage, "classify-bugs")
      assertEquals(newest.owner, "acme")
      assertEquals(newest.repo, "widgets")
      assertEquals(newest.sourceFile, "workspace/exports/run-A_classified2.json")
      assertEquals(newest.bugTarget, Some(5))
      assertEquals(newest.examinedCommitCount, Some(10))
      assertEquals(newest.stoppedEarly, Some(false))
    finally conn.close()
  }

  test("fetchByRunId returns a row per stage a run actually reached, in pipeline order, not recency order") {
    val conn = openTempDb()
    try
      // All three rows deliberately share the same run_id, exactly like a
      // real mining pass: /inspect_repo mints it, /inspect_commits and
      // /classify_bugs both carry it forward rather than minting their own.
      val repoRunId = insertInspectRepoRun(conn, "full-run", "acme", "widgets", "past-3-months", "workspace/exports/full-run.json", "2026-01-01T00:00:00Z")
      val commitsRunId = insertInspectCommitsRun(conn, "full-run", Some(repoRunId), "workspace/exports/full-run_enriched.json", "2026-01-01T00:00:01Z")
      insertClassifyBugsRun(conn, "full-run", Some(commitsRunId), "workspace/exports/full-run_classified.json", "2026-01-01T00:00:02Z")

      val runs = ListRuns.fetchByRunId(conn, "full-run")
      assertEquals(runs.map(_.stage), List("inspect-repo", "inspect-commits", "classify-bugs"))
      assertEquals(runs.map(_.runId), List("full-run", "full-run", "full-run"))
      assertEquals(runs.map(_.owner), List("acme", "acme", "acme"))
    finally conn.close()
  }

  test("fetchByRunId returns only the stages a run has actually reached, not the ones it hasn't") {
    val conn = openTempDb()
    try
      // This run only ever went through /inspect_repo and /inspect_commits,
      // /classify_bugs was never run for it.
      val repoRunId = insertInspectRepoRun(conn, "partial-run", "acme", "widgets", "past-3-months", "workspace/exports/partial-run.json", "2026-01-01T00:00:00Z")
      insertInspectCommitsRun(conn, "partial-run", Some(repoRunId), "workspace/exports/partial-run_enriched.json", "2026-01-01T00:00:01Z")

      val runs = ListRuns.fetchByRunId(conn, "partial-run")
      assertEquals(runs.map(_.stage), List("inspect-repo", "inspect-commits"))
    finally conn.close()
  }

  test("fetchByRunId returns nothing for a run_id that was never used") {
    val conn = openTempDb()
    try
      assertEquals(ListRuns.fetchByRunId(conn, "never-existed"), Nil)
    finally conn.close()
  }

  test("fetchRunHistory lists one row per pass, newest first, each tagged with the stages it actually reached") {
    val conn = openTempDb()
    try
      // Three separate passes, deliberately stopped at different stages,
      // like a real user running /inspect_repo a few times and only
      // sometimes continuing on to /inspect_commits and /classify_bugs.
      val repoOnlyId = insertInspectRepoRun(conn, "pass-repo-only", "acme", "widgets", "past-1-month", "workspace/exports/pass-repo-only.json", "2026-01-01T00:00:00Z")

      val repoAndCommitsId = insertInspectRepoRun(conn, "pass-to-commits", "acme", "gadgets", "past-3-months", "workspace/exports/pass-to-commits.json", "2026-01-02T00:00:00Z")
      insertInspectCommitsRun(conn, "pass-to-commits", Some(repoAndCommitsId), "workspace/exports/pass-to-commits_enriched.json", "2026-01-02T00:00:01Z")

      val fullRepoId = insertInspectRepoRun(conn, "pass-full", "acme", "sprockets", "past-6-months", "workspace/exports/pass-full.json", "2026-01-03T00:00:00Z")
      val fullCommitsId = insertInspectCommitsRun(conn, "pass-full", Some(fullRepoId), "workspace/exports/pass-full_enriched.json", "2026-01-03T00:00:01Z")
      insertClassifyBugsRun(conn, "pass-full", Some(fullCommitsId), "workspace/exports/pass-full_classified.json", "2026-01-03T00:00:02Z")

      val runs = ListRuns.fetchRunHistory(conn, limit = 10)
      assertEquals(runs.map(_.runId), List("pass-full", "pass-to-commits", "pass-repo-only"), "newest first")
      assertEquals(runs.map(_.stagesReached), List(Some("inspect-repo,inspect-commits,classify-bugs"), Some("inspect-repo,inspect-commits"), Some("inspect-repo")))

      assertEquals(ListRuns.fetchRunHistory(conn, limit = 1).map(_.runId), List("pass-full"), "limit is respected")
    finally conn.close()
  }
