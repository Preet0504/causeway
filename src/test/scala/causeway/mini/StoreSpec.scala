package causeway.mini

import munit.FunSuite

import java.io.File
import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers the two upsert guarantees the SQLite catalog exists for: storing
  * the same run twice must not create duplicate rows, and storing two
  * different runs (different runId, different window) against the same
  * repository must reuse the one `repositories` row while keeping each
  * run's own commit membership separate in `run_commits`. This is exactly
  * the scenario raised when designing this feature: mining the same repo
  * twice with different windows must not corrupt or merge the two runs.
  */
class StoreSpec extends FunSuite:

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-store-test", ".db").toFile
    dbFile.delete()
    dbFile.deleteOnExit()
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")

  private def evidenceJson(runId: String, sinceDate: String, shas: List[String]): ujson.Value =
    ujson.Obj(
      "runId" -> runId,
      "repoUrl" -> "https://github.com/acme/widgets",
      "owner" -> "acme",
      "repo" -> "widgets",
      "clonePath" -> "/tmp/widgets",
      "window" -> ujson.Obj("requested" -> "past-3-months", "sinceDate" -> sinceDate),
      "repoSnapshot" -> ujson.Obj(
        "remoteName" -> "origin",
        "branch" -> "main",
        "remoteHeadSha" -> "deadbeef",
        "retrievedAt" -> "2026-09-01T00:00:00Z"
      ),
      "scanCommitLimit" -> 5,
      "windowCommitCount" -> shas.length,
      "commits" -> shas.map { sha =>
        ujson.Obj(
          "sha" -> sha,
          "shortMessage" -> s"commit $sha",
          "fullMessage" -> s"commit $sha\n\nbody",
          "author" -> ujson.Obj("name" -> "Ada", "email" -> "ada@example.com", "date" -> "2026-08-01T00:00:00Z"),
          "committer" -> ujson.Obj("name" -> "Ada", "email" -> "ada@example.com", "date" -> "2026-08-01T00:00:00Z"),
          "commitDate" -> "2026-08-01T00:00:00Z",
          "parentShas" -> List.empty[String]
        )
      }
    )

  private def count(conn: Connection, sql: String): Int =
    val stmt = conn.createStatement()
    try
      val rs = stmt.executeQuery(sql)
      rs.next()
      rs.getInt(1)
    finally stmt.close()

  test("storing the same evidence run twice does not duplicate rows") {
    val conn = openTempDb()
    try
      val json = evidenceJson("run-A", "2026-06-01", List("sha1", "sha2"))
      Store.storeInto(conn, json, "run-A.json")
      Store.storeInto(conn, json, "run-A.json")

      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM inspect_repo_runs"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM commits"), 2)
      assertEquals(count(conn, "SELECT COUNT(*) FROM run_commits"), 2)
    finally conn.close()
  }

  test("two different runs against the same repo, different windows, stay separate but share the repository row") {
    val conn = openTempDb()
    try
      // Run A and run B share sha2 (rediscovered in both windows), but each
      // has one commit unique to its own window.
      val runA = evidenceJson("run-A", "2026-06-01", List("sha1", "sha2"))
      val runB = evidenceJson("run-B", "2026-01-01", List("sha2", "sha3"))

      Store.storeInto(conn, runA, "run-A.json")
      Store.storeInto(conn, runB, "run-B.json")

      // One repository row, reused by both runs, never duplicated just
      // because a second run touched the same repo.
      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories"), 1)

      // Two distinct run rows, one per runId, each with its own window.
      assertEquals(count(conn, "SELECT COUNT(*) FROM inspect_repo_runs"), 2)

      // sha2 is the same commit row in both runs, not duplicated.
      assertEquals(count(conn, "SELECT COUNT(*) FROM commits"), 3)

      // But run_commits must record BOTH runs' membership for sha2, a bare
      // column here (instead of a join table) would let the second run's
      // write silently clobber the first run's membership record.
      assertEquals(count(conn, "SELECT COUNT(*) FROM run_commits"), 4)
      assertEquals(
        count(conn, "SELECT COUNT(*) FROM run_commits rc JOIN inspect_repo_runs r ON r.id = rc.inspect_repo_run_id WHERE r.run_id = 'run-A'"),
        2
      )
      assertEquals(
        count(conn, "SELECT COUNT(*) FROM run_commits rc JOIN inspect_repo_runs r ON r.id = rc.inspect_repo_run_id WHERE r.run_id = 'run-B'"),
        2
      )
    finally conn.close()
  }

  private def enrichedJson(runId: String, commits: List[ujson.Value]): ujson.Value =
    ujson.Obj(
      "runId" -> runId,
      "repoUrl" -> "https://github.com/acme/widgets",
      "owner" -> "acme",
      "repo" -> "widgets",
      "enrichedCommitCount" -> commits.length,
      "commits" -> commits
    )

  private def commitWithPr(
      sha: String,
      fullMessage: String,
      prNumber: Int,
      closingIssueNumbers: List[Int],
      prCommitShas: List[String] = null
  ): ujson.Value =
    // Defaults to a PR whose own commit list actually contains this sha,
    // the common case. Pass an explicit prCommitShas (e.g. Nil, or some
    // other sha) to simulate the divergence pr_contains_commit exists for.
    val effectivePrCommitShas = if prCommitShas == null then List(sha) else prCommitShas
    ujson.Obj(
      "sha" -> sha,
      "shortMessage" -> fullMessage,
      "fullMessage" -> fullMessage,
      "pullRequests" -> List(
        ujson.Obj(
          "number" -> prNumber,
          "title" -> s"PR $prNumber",
          "url" -> s"https://github.com/acme/widgets/pull/$prNumber",
          "state" -> "MERGED",
          "closingIssues" -> closingIssueNumbers.map { n =>
            ujson.Obj("number" -> n, "title" -> s"Issue $n", "url" -> s"https://github.com/acme/widgets/issues/$n")
          },
          "commitShas" -> effectivePrCommitShas,
          "commitTotalCount" -> effectivePrCommitShas.length
        )
      )
    )

  test("a PR's closing issue is attributed to the PR, not duplicated onto every commit inside it") {
    val conn = openTempDb()
    try
      // Two commits both belong to PR 10; PR 10 closes issue 42. Only one
      // of these two commits, in reality, is the one that actually did the
      // closing, closing-an-issue is a fact about the PR as a whole, not
      // about every commit that happens to be part of it.
      val commitA = commitWithPr("shaA", "some work", prNumber = 10, closingIssueNumbers = List(42))
      val commitB = commitWithPr("shaB", "more work", prNumber = 10, closingIssueNumbers = List(42))

      Store.storeInto(conn, enrichedJson("run-A", List(commitA, commitB)), "run-A_enriched.json")

      // Both commits really are associated with PR 10, that's a genuine
      // per-commit fact, so two rows here is correct.
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_associated_pr'"), 2)

      // But PR 10 closing issue 42 must appear exactly once, as an edge
      // between the PR and the issue, not once per commit in that PR. The
      // old (buggy) behavior recorded this once per commit, i.e. 2 rows
      // here instead of 1, wrongly implying both commits individually
      // closed the issue.
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_closes_issue'"), 1)

      // And that one row must carry no commit_sha at all, since it's not
      // attributed to either commit specifically.
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_closes_issue' AND commit_sha IS NULL"), 1)
    finally conn.close()
  }

  test("a raw #N mention in a commit's own message is recorded as its own distinct, weaker relation type") {
    val conn = openTempDb()
    try
      val commit = ujson.Obj(
        "sha" -> "shaC",
        "shortMessage" -> "Fixes #99 by tightening validation",
        "fullMessage" -> "Fixes #99 by tightening validation",
        "pullRequests" -> List.empty[ujson.Value]
      )

      Store.storeInto(conn, enrichedJson("run-C", List(commit)), "run-C_enriched.json")

      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_message_references_issue'"), 1)
      assertEquals(
        count(
          conn,
          "SELECT COUNT(*) FROM relations r " +
            "JOIN issues i ON i.id = r.issue_id " +
            "WHERE r.relation_type = 'commit_message_references_issue' AND r.commit_sha = 'shaC' AND i.number = 99"
        ),
        1
      )
      // It must not also be recorded as a pr_closes_issue, that's a
      // different claim from a different source, this commit has no PR at
      // all here.
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_closes_issue'"), 0)
    finally conn.close()
  }

  test("storing the same enriched run twice does not duplicate relations") {
    val conn = openTempDb()
    try
      val commit = commitWithPr("shaD", "Fixes #7", prNumber = 20, closingIssueNumbers = List(7))
      val json = enrichedJson("run-D", List(commit))

      Store.storeInto(conn, json, "run-D_enriched.json")
      Store.storeInto(conn, json, "run-D_enriched.json")

      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_associated_pr'"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_contains_commit'"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_closes_issue'"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_message_references_issue'"), 1)
    finally conn.close()
  }

  test("commit_associated_pr and pr_contains_commit can disagree: a commit resolved as associated with a PR that no longer contains it") {
    val conn = openTempDb()
    try
      // GitHub resolves shaE as associated with PR 30, but a rebase moved
      // shaE out of PR 30's own commit list (which now only contains some
      // other sha). commit_associated_pr must still be recorded (GitHub
      // said so), pr_contains_commit must not be (it genuinely isn't there
      // anymore).
      val commit = commitWithPr("shaE", "rebased work", prNumber = 30, closingIssueNumbers = Nil, prCommitShas = List("someOtherSha"))

      Store.storeInto(conn, enrichedJson("run-E", List(commit)), "run-E_enriched.json")

      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_associated_pr' AND commit_sha = 'shaE'"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_contains_commit' AND commit_sha = 'shaE'"), 0)
    finally conn.close()
  }

  private def enrichedJsonWithIssueTimelines(runId: String, commits: List[ujson.Value], issueTimelines: List[ujson.Value]): ujson.Value =
    ujson.Obj(
      "runId" -> runId,
      "repoUrl" -> "https://github.com/acme/widgets",
      "owner" -> "acme",
      "repo" -> "widgets",
      "enrichedCommitCount" -> commits.length,
      "commits" -> commits,
      "issueTimelines" -> issueTimelines
    )

  test("an issue's own ReferencedEvent timeline produces a GitHub-confirmed commit_mentions_issue relation") {
    val conn = openTempDb()
    try
      val commit = ujson.Obj("sha" -> "shaF", "shortMessage" -> "fix thing", "fullMessage" -> "fix thing", "pullRequests" -> List.empty[ujson.Value])
      val timeline = ujson.Obj(
        "number" -> 55,
        "body" -> "some issue body with nothing sha-shaped in it",
        "referencedCommits" -> List(ujson.Obj("sha" -> "shaF", "isCrossRepository" -> false)),
        "crossReferencingPRs" -> List.empty[ujson.Value]
      )

      Store.storeInto(conn, enrichedJsonWithIssueTimelines("run-F", List(commit), List(timeline)), "run-F_enriched.json")

      assertEquals(
        count(
          conn,
          "SELECT COUNT(*) FROM relations r JOIN issues i ON i.id = r.issue_id " +
            "WHERE r.relation_type = 'commit_mentions_issue' AND r.commit_sha = 'shaF' AND i.number = 55"
        ),
        1
      )
    finally conn.close()
  }

  test("a referenced commit this run never scanned is not linked (would violate the commits foreign key)") {
    val conn = openTempDb()
    try
      val commit = ujson.Obj("sha" -> "shaG", "shortMessage" -> "unrelated", "fullMessage" -> "unrelated", "pullRequests" -> List.empty[ujson.Value])
      val timeline = ujson.Obj(
        "number" -> 56,
        "body" -> "",
        "referencedCommits" -> List(ujson.Obj("sha" -> "some-unscanned-sha", "isCrossRepository" -> false)),
        "crossReferencingPRs" -> List.empty[ujson.Value]
      )

      Store.storeInto(conn, enrichedJsonWithIssueTimelines("run-G", List(commit), List(timeline)), "run-G_enriched.json")

      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'commit_mentions_issue'"), 0)
    finally conn.close()
  }

  test("an issue's CrossReferencedEvent from a PR produces pr_mentions_issue, distinct from pr_closes_issue") {
    val conn = openTempDb()
    try
      val timeline = ujson.Obj(
        "number" -> 57,
        "body" -> "",
        "referencedCommits" -> List.empty[ujson.Value],
        "crossReferencingPRs" -> List(ujson.Obj("number" -> 99, "title" -> "Discusses the issue", "url" -> "https://github.com/acme/widgets/pull/99"))
      )

      Store.storeInto(conn, enrichedJsonWithIssueTimelines("run-H", Nil, List(timeline)), "run-H_enriched.json")

      assertEquals(
        count(
          conn,
          "SELECT COUNT(*) FROM relations r JOIN pull_requests p ON p.id = r.pull_request_id JOIN issues i ON i.id = r.issue_id " +
            "WHERE r.relation_type = 'pr_mentions_issue' AND p.number = 99 AND i.number = 57"
        ),
        1
      )
      assertEquals(count(conn, "SELECT COUNT(*) FROM relations WHERE relation_type = 'pr_closes_issue'"), 0)
    finally conn.close()
  }

  test("a commit SHA-shaped token in an issue's body text produces issue_mentions_commit, matched by prefix") {
    val conn = openTempDb()
    try
      val fullSha = "abc1234567890def1234567890abc1234567890"
      val commit = ujson.Obj("sha" -> fullSha, "shortMessage" -> "some fix", "fullMessage" -> "some fix", "pullRequests" -> List.empty[ujson.Value])
      // People commonly paste a short (7-12 char) prefix of a sha, not the
      // full 40 characters, in issue text.
      val timeline = ujson.Obj(
        "number" -> 58,
        "body" -> s"This looks related to ${fullSha.take(10)}, can someone check?",
        "referencedCommits" -> List.empty[ujson.Value],
        "crossReferencingPRs" -> List.empty[ujson.Value]
      )

      Store.storeInto(conn, enrichedJsonWithIssueTimelines("run-I", List(commit), List(timeline)), "run-I_enriched.json")

      assertEquals(
        count(
          conn,
          "SELECT COUNT(*) FROM relations r JOIN issues i ON i.id = r.issue_id " +
            s"WHERE r.relation_type = 'issue_mentions_commit' AND r.commit_sha = '$fullSha' AND i.number = 58"
        ),
        1
      )
    finally conn.close()
  }
