package causeway.mini

import munit.FunSuite

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers `repo-details`, the one-repository lookup shown as a "last look"
  * when someone actually picks a repo to mine, distinct from the lean
  * result tables `/discover_repos` and `/list_qualifying_repos` render for
  * every candidate.
  */
class RepoDetailsSpec extends FunSuite:

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-repo-details-test", ".db").toFile
    dbFile.delete()
    dbFile.deleteOnExit()
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")

  test("returns None for a repository that isn't in the catalog at all") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      assertEquals(RepoDetails.fetchDetails(conn, "acme", "nope"), None)
    finally conn.close()
  }

  test("returns identity fields even before qualify-repos has ever checked the repository") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      Store.upsertRepository(
        conn,
        "acme",
        "widgets",
        "https://github.com/acme/widgets",
        description = Some("A widget factory"),
        repoCreatedAt = Some("2020-01-01T00:00:00Z"),
        forksCount = Some(42),
        topics = List("widgets", "factory"),
        defaultBranch = Some("main")
      )

      val details = RepoDetails.fetchDetails(conn, "acme", "widgets").get
      assertEquals(details.description, Some("A widget factory"))
      assertEquals(details.repoCreatedAt, Some("2020-01-01T00:00:00Z"))
      assertEquals(details.forksCount, Some(42))
      assertEquals(details.topics, List("widgets", "factory"))
      assertEquals(details.defaultBranch, Some("main"))
      // Never qualified, so the current-snapshot fields are still empty.
      assertEquals(details.currentStars, None)
      assertEquals(details.currentLicense, None)
    finally conn.close()
  }

  test("returns the current snapshot Store.upsertRepository recorded, undisturbed by a later upsertQualification call") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      // Mirrors what QualifyRepos.run() actually does: current_* is written
      // via the shared Store.upsertRepository call (the single source of
      // truth both search-repos and qualify-repos feed), not by
      // upsertQualification, which only owns the qualification-specific
      // columns (issue/PR/test counts, rejection_reason, checked_at).
      // QualificationResult below deliberately carries *different*
      // current_* values than what was already recorded: if
      // upsertQualification still wrote them (the pre-fix behavior), this
      // call would clobber 1234/Java/5678/... with the mismatched values
      // below instead of leaving them alone.
      val repositoryId = Store.upsertRepository(
        conn,
        "acme",
        "widgets",
        "https://github.com/acme/widgets",
        currentStars = Some(1234),
        currentLanguage = Some("Java"),
        currentSizeKb = Some(5678),
        currentArchived = Some(false),
        currentFork = Some(false),
        currentLicense = Some("mit")
      )
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
          currentStars = Some(9999),
          currentLanguage = Some("Rust"),
          currentSizeKb = Some(1),
          currentArchived = Some(true),
          currentFork = Some(true),
          currentLicense = Some("gpl-3.0"),
          hasIssues = true,
          closedIssueCount = 5,
          openIssueCount = 1,
          mergedPrCount = 3,
          openPrCount = 0,
          testFileCount = 10,
          testEvidencePaths = Nil,
          treeTruncated = false,
          qualifies = true,
          rejectionReason = None
        )
      )

      val details = RepoDetails.fetchDetails(conn, "acme", "widgets").get
      assertEquals(details.currentStars, Some(1234))
      assertEquals(details.currentLanguage, Some("Java"))
      assertEquals(details.currentSizeKb, Some(5678))
      assertEquals(details.currentArchived, Some(false))
      assertEquals(details.currentFork, Some(false))
      assertEquals(details.currentLicense, Some("mit"))
    finally conn.close()
  }
