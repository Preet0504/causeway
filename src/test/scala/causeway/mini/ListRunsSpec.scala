package causeway.mini

import munit.FunSuite

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers `list-runs`, the query that lets `/inspect_commits`/`/classify_bugs`
  * pick a file to reuse by asking the database (which already knows which
  * repository and window each run belongs to) instead of blindly listing
  * `workspace/exports/`, where every file is named only `run_<uuid>...`
  * with no repo or window visible in the filename itself.
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
  ): Unit =
    Store.exec(
      conn,
      "INSERT INTO inspect_commits_runs (run_id, inspect_repo_run_id, enriched_commit_count, source_file, created_at) " +
        "VALUES (?, ?, 5, ?, ?)",
      Seq(runId, inspectRepoRunId.map(_.asInstanceOf[Any]).orNull, sourceFile, createdAt)
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
