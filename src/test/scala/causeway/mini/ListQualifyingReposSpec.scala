package causeway.mini

import munit.FunSuite

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers the query `list-qualifying-repos` exists for: only repositories
  * that currently qualify are listed, ordered by `repositories.current_stars`
  * (the single source of truth for "what does this repo currently look
  * like", kept fresh by whichever of `search-repos`/`qualify-repos` wrote
  * to it most recently), capped at the given limit, each flagged with
  * whether it's already been mined.
  */
class ListQualifyingReposSpec extends FunSuite:

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-list-qualifying-test", ".db").toFile
    dbFile.delete()
    dbFile.deleteOnExit()
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")

  /** Sets a repository's `current_*` columns the same way `search-repos`/
    * `qualify-repos` do in production, via `Store.upsertRepository`'s
    * `ON CONFLICT(owner, repo)` path (the repo must already exist).
    */
  private def setCurrent(conn: Connection, owner: String, repo: String, stars: Int, language: String, sizeKb: Int = 1000): Unit =
    Store.upsertRepository(
      conn,
      owner,
      repo,
      s"https://github.com/$owner/$repo",
      currentStars = Some(stars),
      currentLanguage = Some(language),
      currentSizeKb = Some(sizeKb)
    )

  private def qualify(conn: Connection, repositoryId: Long, qualifies: Boolean): Unit =
    QualifyRepos.upsertQualification(
      conn,
      repositoryId,
      QualifyRepos.QualificationResult(
        htmlUrl = "https://github.com/acme/widgets",
        description = None,
        repoCreatedAt = None,
        forksCount = None,
        topics = Nil,
        defaultBranch = "main",
        currentStars = None,
        currentLanguage = None,
        currentSizeKb = None,
        currentArchived = None,
        currentFork = None,
        currentLicense = None,
        hasIssues = true,
        closedIssueCount = 5,
        openIssueCount = 2,
        mergedPrCount = 5,
        openPrCount = 1,
        testFileCount = 3,
        testEvidencePaths = Nil,
        treeTruncated = false,
        qualifies = qualifies,
        rejectionReason = if qualifies then None else Some("test fixture rejection")
      )
    )

  private def markMined(conn: Connection, repositoryId: Long, runId: String): Unit =
    Store.exec(
      conn,
      "INSERT INTO repository_snapshots (repository_id, remote_name, branch, remote_head_sha, retrieved_at) VALUES (?, 'origin', 'main', 'deadbeef', '2026-01-01T00:00:00Z')",
      Seq(repositoryId)
    )
    val snapId = Store.queryId(
      conn,
      "SELECT id FROM repository_snapshots WHERE repository_id = ? AND remote_head_sha = 'deadbeef'",
      Seq(repositoryId)
    )
    Store.exec(
      conn,
      "INSERT INTO inspect_repo_runs (run_id, repository_id, repository_snapshot_id, window_requested, window_since_date, window_commit_count, source_file, created_at) " +
        "VALUES (?, ?, ?, 'past-3-months', '2026-06-01', 1, 'test.json', datetime('now'))",
      Seq(runId, repositoryId, snapId)
    )

  test("only currently-qualifying repositories are listed") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val a = Store.upsertRepository(conn, "acme", "qualifies", "https://github.com/acme/qualifies")
      val b = Store.upsertRepository(conn, "acme", "rejected", "https://github.com/acme/rejected")
      qualify(conn, a, qualifies = true)
      qualify(conn, b, qualifies = false)

      val results = ListQualifyingRepos.fetchTopQualifying(conn, limit = 10)
      assertEquals(results.map(_.repo), List("qualifies"))
    finally conn.close()
  }

  test("a repository that was discovered but never qualified does not show up as qualifying") {
    // There's no stored `qualifies` column, "qualifies" is a query
    // condition (rejection_reason IS NULL). A repository that was upserted
    // by search-repos but never run through qualify-repos also has
    // rejection_reason = NULL, for a completely different reason (never
    // checked, not checked-and-passed), so the query must also require
    // checked_at IS NOT NULL, or an unchecked repository would wrongly
    // count as qualifying.
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      Store.upsertRepository(conn, "acme", "never-checked", "https://github.com/acme/never-checked")

      assertEquals(ListQualifyingRepos.countQualifying(conn), 0)
      assertEquals(ListQualifyingRepos.fetchTopQualifying(conn, limit = 10), Nil)
    finally conn.close()
  }

  test("results are ordered by stars descending and capped at the given limit") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val a = Store.upsertRepository(conn, "acme", "small", "https://github.com/acme/small")
      val b = Store.upsertRepository(conn, "acme", "huge", "https://github.com/acme/huge")
      val c = Store.upsertRepository(conn, "acme", "medium", "https://github.com/acme/medium")
      List(a, b, c).foreach(qualify(conn, _, qualifies = true))

      setCurrent(conn, "acme", "small", stars = 100, language = "Java")
      setCurrent(conn, "acme", "huge", stars = 500, language = "Java")
      setCurrent(conn, "acme", "medium", stars = 200, language = "Java")

      val top2 = ListQualifyingRepos.fetchTopQualifying(conn, limit = 2)
      assertEquals(top2.map(_.repo), List("huge", "medium"))
    finally conn.close()
  }

  test("a repo whose current stats are written twice (e.g. by search-repos, then again by qualify-repos) shows the most recently written values, not the stale ones") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val a = Store.upsertRepository(conn, "acme", "widgets", "https://github.com/acme/widgets")
      qualify(conn, a, qualifies = true)

      setCurrent(conn, "acme", "widgets", stars = 100, language = "Java", sizeKb = 500)
      setCurrent(conn, "acme", "widgets", stars = 999, language = "Java", sizeKb = 750)

      val results = ListQualifyingRepos.fetchTopQualifying(conn, limit = 10)
      assertEquals(results.size, 1, s"expected exactly one row for a repository written twice, got $results")
      assertEquals(results.head.stars, Some(999))
      assertEquals(results.head.sizeKb, Some(750), "size should come from the same, most recent write as stars, not a stale one")
    finally conn.close()
  }

  test("alreadyMined reflects whether the repository has an inspect_repo_runs row") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val mined = Store.upsertRepository(conn, "acme", "already-mined", "https://github.com/acme/already-mined")
      val notMined = Store.upsertRepository(conn, "acme", "not-mined-yet", "https://github.com/acme/not-mined-yet")
      qualify(conn, mined, qualifies = true)
      qualify(conn, notMined, qualifies = true)
      markMined(conn, mined, "mine-run-1")

      val results = ListQualifyingRepos.fetchTopQualifying(conn, limit = 10).map(r => r.repo -> r.alreadyMined).toMap
      assertEquals(results("already-mined"), true)
      assertEquals(results("not-mined-yet"), false)
    finally conn.close()
  }

  test("countQualifying reflects the true total even when the list itself is capped by limit") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      List("one", "two", "three").foreach { name =>
        val id = Store.upsertRepository(conn, "acme", name, s"https://github.com/acme/$name")
        qualify(conn, id, qualifies = true)
      }

      assertEquals(ListQualifyingRepos.countQualifying(conn), 3)
      assertEquals(ListQualifyingRepos.fetchTopQualifying(conn, limit = 1).size, 1)
    finally conn.close()
  }
