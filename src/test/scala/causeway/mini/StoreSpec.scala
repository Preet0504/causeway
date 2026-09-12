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
