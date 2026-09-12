package causeway.mini

import munit.FunSuite

import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers the pure decision logic `qualify-repos` exists for: the two hard
  * disqualifiers (issues disabled, or no closed issue and no merged PR
  * ever) versus the soft, non-disqualifying test-presence heuristic, and
  * that re-checking a repository replaces its one current row rather than
  * accumulating history.
  */
class QualifyReposSpec extends FunSuite:

  test("issues disabled is a hard disqualifier regardless of issue/PR counts") {
    val (qualifies, reason) = QualifyRepos.decideQualification(hasIssues = false, closedIssueCount = 50, mergedPrCount = 50)
    assertEquals(qualifies, false)
    assert(reason.exists(_.contains("issues are disabled")), s"expected an issues-disabled reason, got $reason")
  }

  test("zero closed issues and zero merged PRs is a hard disqualifier") {
    val (qualifies, reason) = QualifyRepos.decideQualification(hasIssues = true, closedIssueCount = 0, mergedPrCount = 0)
    assertEquals(qualifies, false)
    assert(reason.isDefined)
  }

  test("at least one closed issue is enough to qualify, even with zero merged PRs") {
    val (qualifies, reason) = QualifyRepos.decideQualification(hasIssues = true, closedIssueCount = 1, mergedPrCount = 0)
    assertEquals(qualifies, true)
    assertEquals(reason, None)
  }

  test("at least one merged PR is enough to qualify, even with zero closed issues") {
    val (qualifies, _) = QualifyRepos.decideQualification(hasIssues = true, closedIssueCount = 0, mergedPrCount = 1)
    assertEquals(qualifies, true)
  }

  test("detectTestEvidence recognizes common test-file conventions across ecosystems") {
    val paths = List(
      "src/main/java/org/acme/Widget.java",
      "src/test/java/org/acme/WidgetTest.java",
      "tests/test_widget.py",
      "widget_test.go",
      "src/component.spec.ts",
      "lib/spec/widget_spec.rb",
      "README.md"
    )
    val evidence = QualifyRepos.detectTestEvidence(paths)
    assertEquals(
      evidence.toSet,
      Set("src/test/java/org/acme/WidgetTest.java", "tests/test_widget.py", "widget_test.go", "src/component.spec.ts", "lib/spec/widget_spec.rb")
    )
  }

  test("detectTestEvidence finds nothing in a tree with no test-like paths") {
    val paths = List("src/main/java/org/acme/Widget.java", "README.md", "build.gradle")
    assertEquals(QualifyRepos.detectTestEvidence(paths), Nil)
  }

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-qualify-test", ".db").toFile
    dbFile.delete()
    dbFile.deleteOnExit()
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")

  private def count(conn: Connection, sql: String): Int =
    val stmt = conn.createStatement()
    try
      val rs = stmt.executeQuery(sql)
      rs.next()
      rs.getInt(1)
    finally stmt.close()

  test("re-qualifying a repository updates its row in place, never creating a second repositories row") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val repositoryId = Store.upsertRepository(conn, "acme", "widgets", "https://github.com/acme/widgets")

      val firstCheck = QualifyRepos.QualificationResult(
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
        closedIssueCount = 0,
        openIssueCount = 2,
        mergedPrCount = 0,
        openPrCount = 1,
        testFileCount = 0,
        testEvidencePaths = Nil,
        treeTruncated = false,
        qualifies = false,
        rejectionReason = Some("no closed issues and no merged pull requests found, no possible bug-fix evidence source")
      )
      QualifyRepos.upsertQualification(conn, repositoryId, firstCheck)

      // Time passes, the repository gains activity, it's checked again.
      val secondCheck = firstCheck.copy(closedIssueCount = 3, qualifies = true, rejectionReason = None)
      QualifyRepos.upsertQualification(conn, repositoryId, secondCheck)

      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories WHERE rejection_reason IS NULL AND closed_issue_count = 3"), 1)
    finally conn.close()
  }
