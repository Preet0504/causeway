package causeway.mini

import munit.FunSuite

import java.io.File
import java.nio.file.Files
import java.sql.{Connection, DriverManager}

/** Covers the two things `search-repos` is supposed to guarantee: the
  * structured specification is translated into GitHub's qualifier syntax
  * correctly (`buildQualifiers`), and storing a discovered repository is
  * idempotent while still keeping each search run's own point-in-time
  * snapshot of that repository separate, the same "rediscovered by a
  * different run" guarantee already proven for commits in `StoreSpec`.
  */
class SearchReposSpec extends FunSuite:

  private def openTempDb(): Connection =
    val dbFile = Files.createTempFile("causeway-search-test", ".db").toFile
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

  private def searchItem(
      fullName: String,
      stars: Int,
      language: String,
      pushedAt: String,
      sizeKb: Int,
      fork: Boolean = false,
      archived: Boolean = false,
      licenseSpdx: Option[String] = Some("MIT")
  ): ujson.Value =
    ujson.Obj(
      "full_name" -> fullName,
      "html_url" -> s"https://github.com/$fullName",
      "stargazers_count" -> stars,
      "language" -> language,
      "pushed_at" -> pushedAt,
      "size" -> sizeKb,
      "fork" -> fork,
      "archived" -> archived,
      "license" -> licenseSpdx.map(id => ujson.Obj("spdx_id" -> id)).getOrElse(ujson.Null)
    )

  test("buildQualifiers translates every discriminator into GitHub's search qualifier syntax") {
    val q = SearchRepos.buildQualifiers(
      language = Some("Java"),
      minStars = Some(1000),
      pushedSinceDate = Some("2026-03-01"),
      minSizeKb = Some(100),
      maxSizeKb = Some(5000),
      forkStatus = Some("false"),
      archivedStatus = Some("false"),
      topics = List("json", "parser"),
      license = Some("mit")
    )
    assertEquals(
      q,
      "language:Java stars:>=1000 pushed:>=2026-03-01 size:100..5000 fork:false archived:false topic:json topic:parser license:mit"
    )
  }

  test("buildQualifiers with no discriminators produces an empty string") {
    assertEquals(SearchRepos.buildQualifiers(None, None, None, None, None, None, None, Nil, None), "")
  }

  test("buildQualifiers with only a minimum size uses a >= qualifier, not a range") {
    val q = SearchRepos.buildQualifiers(None, None, None, Some(100), None, None, None, Nil, None)
    assertEquals(q, "size:>=100")
  }

  test("storing the same discovered repo twice under the same search run does not duplicate rows") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val runSurrogateId = SearchRepos.upsertSearchRepoRun(
        conn,
        runId = "search-A",
        language = Some("Java"),
        minStars = None,
        pushedWithinMonths = None,
        pushedSinceDate = None,
        minSizeKb = None,
        maxSizeKb = None,
        forkStatus = None,
        archivedStatus = None,
        topics = Nil,
        license = None,
        maxResults = 10,
        resultCount = 0
      )

      val item = searchItem("acme/widgets", stars = 500, language = "Java", pushedAt = "2026-08-01T00:00:00Z", sizeKb = 1200)
      SearchRepos.storeDiscoveredRepo(conn, runSurrogateId, item)
      SearchRepos.storeDiscoveredRepo(conn, runSurrogateId, item)

      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM search_run_repositories"), 1)
    finally conn.close()
  }

  test("the same repository discovered by two different search runs keeps each run's own point-in-time snapshot") {
    val conn = openTempDb()
    try
      Store.applySchema(conn)
      val runA = SearchRepos.upsertSearchRepoRun(
        conn,
        "search-A",
        Some("Java"),
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Nil,
        None,
        10,
        0
      )
      val runB = SearchRepos.upsertSearchRepoRun(
        conn,
        "search-B",
        Some("Java"),
        Some(1000),
        None,
        None,
        None,
        None,
        None,
        None,
        Nil,
        None,
        10,
        0
      )

      // The same repository, but its star count grew between the two
      // searches, exactly the reason each run needs its own snapshot row
      // rather than one shared, overwritten record.
      SearchRepos.storeDiscoveredRepo(conn, runA, searchItem("acme/widgets", stars = 500, language = "Java", pushedAt = "2026-06-01T00:00:00Z", sizeKb = 1000))
      SearchRepos.storeDiscoveredRepo(conn, runB, searchItem("acme/widgets", stars = 1200, language = "Java", pushedAt = "2026-08-01T00:00:00Z", sizeKb = 1000))

      assertEquals(count(conn, "SELECT COUNT(*) FROM repositories"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM search_run_repositories"), 2)
      assertEquals(count(conn, "SELECT COUNT(*) FROM search_run_repositories WHERE search_repos_run_id = " + runA + " AND stars = 500"), 1)
      assertEquals(count(conn, "SELECT COUNT(*) FROM search_run_repositories WHERE search_repos_run_id = " + runB + " AND stars = 1200"), 1)
    finally conn.close()
  }
