package causeway.mini

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.time.{Duration as JDuration, LocalDate}
import java.util.UUID

/** Discovers candidate repositories via GitHub's repository search API from
  * a structured specification (language, a star range, recent activity,
  * a size range, a fork-count range, a repo-age range, fork/archived
  * status, topics, license, and a result cap), rather
  * than requiring a person to already know an exact repo URL the way
  * `/inspect_repo` does. Unlike `InspectRepo`/`EnrichCommits`, this writes
  * no JSON file: results are paginated and inserted into the SQLite
  * catalog directly, as they're found, each one tagged with the exact
  * search specification (as a `search_repos_runs` row) that discovered it.
  *
  * This tool does the deterministic, mechanical half of repository
  * discovery. Translating a person's fuzzy requirements ("popular,
  * actively-maintained Java repos") into these structured flags is the
  * `repo-discovery` agent's job (see `.claude/agents/repo-discovery.md`),
  * not this tool's, this tool only ever does exactly what its flags say.
  */
object SearchRepos:

  private val SearchUrl = "https://api.github.com/search/repositories"
  private val PerPage = 100
  // GitHub's repository search caps any single query at 1000 results
  // total (page 10 at 100 per page), regardless of how high a caller asks.
  private val GitHubResultCeiling = 1000
  private val DbPath = "workspace/causeway.db"

  /** Entry point for the `search-repos` subcommand of the unified
    * `Causeway` CLI (see `Causeway.scala`).
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val token = sys.env.getOrElse("GITHUB_TOKEN", fail("GITHUB_TOKEN environment variable is required")).trim

    val language = opts.get("language")
    val minStars = opts.get("min-stars").map(_.toInt)
    val maxStars = opts.get("max-stars").map(_.toInt)
    val pushedWithinMonths = opts.get("pushed-within-months").map(_.toInt)
    val minSizeKb = opts.get("min-size-kb").map(_.toInt)
    val maxSizeKb = opts.get("max-size-kb").map(_.toInt)
    val minForks = opts.get("min-forks").map(_.toInt)
    val maxForks = opts.get("max-forks").map(_.toInt)
    val minRepoAgeYears = opts.get("min-repo-age-years").map(_.toInt)
    val maxRepoAgeYears = opts.get("max-repo-age-years").map(_.toInt)
    val forkStatus = opts.get("fork").map(validateFork)
    val archivedStatus = opts.get("archived").map(validateBoolFlag("--archived"))
    val topics = opts.get("topics").toList.flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))
    val license = opts.get("license")
    val requestedMaxResults = opts.get("max-results").map(_.toInt).getOrElse(fail("--max-results is required"))

    val maxResults =
      if requestedMaxResults > GitHubResultCeiling then
        System.err.println(
          s"WARN: --max-results $requestedMaxResults exceeds GitHub's search API ceiling of $GitHubResultCeiling results per query, capping at $GitHubResultCeiling"
        )
        GitHubResultCeiling
      else requestedMaxResults

    val pushedSinceDate = pushedWithinMonths.map(months => LocalDate.now().minusMonths(months.toLong).toString)
    // An older repository (a larger min-age) has a smaller (earlier)
    // creation date, so min age resolves to an upper bound on the
    // creation date, and max age resolves to a lower bound, not the
    // other way around.
    val createdBeforeDate = minRepoAgeYears.map(years => LocalDate.now().minusYears(years.toLong).toString)
    val createdAfterDate = maxRepoAgeYears.map(years => LocalDate.now().minusYears(years.toLong).toString)

    val qualifiers = buildQualifiers(
      language,
      minStars,
      maxStars,
      pushedSinceDate,
      minSizeKb,
      maxSizeKb,
      minForks,
      maxForks,
      createdAfterDate,
      createdBeforeDate,
      forkStatus,
      archivedStatus,
      topics,
      license
    )
    if qualifiers.isEmpty then
      fail("At least one search discriminator is required (language, min-stars, pushed-within-months, size, forks, repo-age, fork, archived, topics, or license)")

    val runId = UUID.randomUUID().toString

    Class.forName("org.sqlite.JDBC")
    new java.io.File(DbPath).getAbsoluteFile.getParentFile.mkdirs()
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      conn.setAutoCommit(false)
      Store.applySchema(conn)

      val runSurrogateId = upsertSearchRepoRun(
        conn,
        runId,
        language,
        minStars,
        maxStars,
        pushedWithinMonths,
        pushedSinceDate,
        minSizeKb,
        maxSizeKb,
        minForks,
        maxForks,
        minRepoAgeYears,
        maxRepoAgeYears,
        createdBeforeDate,
        createdAfterDate,
        forkStatus,
        archivedStatus,
        topics,
        license,
        maxResults,
        resultCount = 0
      )

      val seen = scala.collection.mutable.Set[String]()
      var page = 1
      var collected = 0
      var keepGoing = true

      while keepGoing && collected < maxResults && page <= GitHubResultCeiling / PerPage do
        val body = fetchPage(qualifiers, page, token)
        val json = ujson.read(body)
        val items = json.obj.get("items").map(_.arr.toList).getOrElse(Nil)

        if items.isEmpty then keepGoing = false
        else
          items.foreach { item =>
            if collected < maxResults then
              val fullName = item("full_name").str
              // Defensive dedup: GitHub's search shouldn't hand back the
              // same repo twice within one query, but the requirement is
              // explicit, and this also protects against a shifting
              // best-match ordering moving a repo across a page boundary
              // between two page fetches.
              if !seen.contains(fullName) then
                seen += fullName
                storeDiscoveredRepo(conn, runSurrogateId, item)
                collected += 1
          }
          if items.size < PerPage then keepGoing = false
          page += 1

      updateResultCount(conn, runSurrogateId, collected)
      conn.commit()

      println(s"SEARCH_RUN_ID=$runId")
      println(s"RESULT_COUNT=$collected")
      println(s"DATABASE=$DbPath")
    catch
      case e: Exception =>
        conn.rollback()
        fail(s"Search failed, rolled back, no partial write: ${e.getMessage}")
    finally conn.close()
  end run

  // ---------------------------------------------------------------------
  // Query construction
  // ---------------------------------------------------------------------

  private[mini] def buildQualifiers(
      language: Option[String],
      minStars: Option[Int],
      maxStars: Option[Int],
      pushedSinceDate: Option[String],
      minSizeKb: Option[Int],
      maxSizeKb: Option[Int],
      minForks: Option[Int] = None,
      maxForks: Option[Int] = None,
      createdAfterDate: Option[String] = None,
      createdBeforeDate: Option[String] = None,
      forkStatus: Option[String],
      archivedStatus: Option[String],
      topics: List[String],
      license: Option[String]
  ): String =
    val parts = scala.collection.mutable.ListBuffer[String]()
    language.foreach(l => parts += s"language:$l")
    (minStars, maxStars) match
      case (Some(min), Some(max)) => parts += s"stars:$min..$max"
      case (Some(min), None)      => parts += s"stars:>=$min"
      case (None, Some(max))      => parts += s"stars:<=$max"
      case (None, None)           => ()
    pushedSinceDate.foreach(d => parts += s"pushed:>=$d")
    (minSizeKb, maxSizeKb) match
      case (Some(min), Some(max)) => parts += s"size:$min..$max"
      case (Some(min), None)      => parts += s"size:>=$min"
      case (None, Some(max))      => parts += s"size:<=$max"
      case (None, None)           => ()
    (minForks, maxForks) match
      case (Some(min), Some(max)) => parts += s"forks:$min..$max"
      case (Some(min), None)      => parts += s"forks:>=$min"
      case (None, Some(max))      => parts += s"forks:<=$max"
      case (None, None)           => ()
    // createdAfterDate/createdBeforeDate are already resolved dates (see
    // run()'s min/max-repo-age-years -> date conversion), a min-age lower
    // bound on how far back the repo must go becomes an upper bound on its
    // creation date, and vice versa.
    (createdAfterDate, createdBeforeDate) match
      case (Some(after), Some(before)) => parts += s"created:$after..$before"
      case (Some(after), None)         => parts += s"created:>=$after"
      case (None, Some(before))        => parts += s"created:<=$before"
      case (None, None)                => ()
    forkStatus.foreach(f => parts += s"fork:$f")
    archivedStatus.foreach(a => parts += s"archived:$a")
    topics.foreach(t => parts += s"topic:$t")
    license.foreach(l => parts += s"license:$l")
    parts.mkString(" ")

  private def fetchPage(qualifiers: String, page: Int, token: String): String =
    val encodedQuery = URLEncoder.encode(qualifiers, StandardCharsets.UTF_8)
    val url = s"$SearchUrl?q=$encodedQuery&per_page=$PerPage&page=$page&sort=stars&order=desc"
    val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(15)).build()
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url))
      .header("Accept", "application/vnd.github+json")
      .header("Authorization", s"Bearer $token")
      .timeout(JDuration.ofSeconds(30))
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      fail(s"GitHub search API request failed with status ${response.statusCode()}: ${response.body().take(500)}")
    response.body()

  // ---------------------------------------------------------------------
  // Database writes
  // ---------------------------------------------------------------------

  private[mini] def upsertSearchRepoRun(
      conn: Connection,
      runId: String,
      language: Option[String],
      minStars: Option[Int],
      maxStars: Option[Int],
      pushedWithinMonths: Option[Int],
      pushedSinceDate: Option[String],
      minSizeKb: Option[Int],
      maxSizeKb: Option[Int],
      minForks: Option[Int] = None,
      maxForks: Option[Int] = None,
      minRepoAgeYears: Option[Int] = None,
      maxRepoAgeYears: Option[Int] = None,
      createdBeforeDate: Option[String] = None,
      createdAfterDate: Option[String] = None,
      forkStatus: Option[String],
      archivedStatus: Option[String],
      topicFilter: List[String],
      license: Option[String],
      maxResults: Int,
      resultCount: Int
  ): Long =
    Store.exec(
      conn,
      "INSERT INTO search_repos_runs " +
        "(run_id, language, min_stars, max_stars, pushed_within_months, pushed_since_date, min_size_kb, max_size_kb, " +
        "min_forks, max_forks, min_repo_age_years, max_repo_age_years, created_before_date, created_after_date, " +
        "fork_status, archived_status, topic_filter, license, max_results, result_count, created_at) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now')) " +
        "ON CONFLICT(run_id) DO UPDATE SET result_count = excluded.result_count",
      Seq(
        runId,
        language.orNull,
        minStars.map(_.asInstanceOf[Any]).orNull,
        maxStars.map(_.asInstanceOf[Any]).orNull,
        pushedWithinMonths.map(_.asInstanceOf[Any]).orNull,
        pushedSinceDate.orNull,
        minSizeKb.map(_.asInstanceOf[Any]).orNull,
        maxSizeKb.map(_.asInstanceOf[Any]).orNull,
        minForks.map(_.asInstanceOf[Any]).orNull,
        maxForks.map(_.asInstanceOf[Any]).orNull,
        minRepoAgeYears.map(_.asInstanceOf[Any]).orNull,
        maxRepoAgeYears.map(_.asInstanceOf[Any]).orNull,
        createdBeforeDate.orNull,
        createdAfterDate.orNull,
        forkStatus.orNull,
        archivedStatus.orNull,
        if topicFilter.isEmpty then null else topicFilter.mkString(","),
        license.orNull,
        maxResults,
        resultCount
      )
    )
    Store.queryId(conn, "SELECT id FROM search_repos_runs WHERE run_id = ?", Seq(runId))

  private def updateResultCount(conn: Connection, searchRepoRunSurrogateId: Long, resultCount: Int): Unit =
    Store.exec(conn, "UPDATE search_repos_runs SET result_count = ? WHERE id = ?", Seq(resultCount, searchRepoRunSurrogateId))

  private[mini] def storeDiscoveredRepo(conn: Connection, searchRepoRunSurrogateId: Long, item: ujson.Value): Unit =
    val fullName = item("full_name").str
    val parts = fullName.split("/", 2)
    val (owner, repo) = (parts(0), parts(1))
    val description = item.obj.get("description").filterNot(_ == ujson.Null).map(_.str)
    val repoCreatedAt = item.obj.get("created_at").filterNot(_ == ujson.Null).map(_.str)
    val forksCount = item.obj.get("forks_count").map(_.num.toInt)
    val topics = item.obj.get("topics").map(_.arr.toList.map(_.str)).getOrElse(Nil)
    val defaultBranch = item.obj.get("default_branch").filterNot(_ == ujson.Null).map(_.str)
    val stars = item.obj.get("stargazers_count").map(_.num.toInt)
    val language = item.obj.get("language").filterNot(_ == ujson.Null).map(_.str)
    val pushedAt = item.obj.get("pushed_at").filterNot(_ == ujson.Null).map(_.str)
    val sizeKb = item.obj.get("size").map(_.num.toInt)
    val isFork = item.obj.get("fork").map(_.bool)
    val isArchived = item.obj.get("archived").map(_.bool)
    val license = item.obj.get("license").filterNot(_ == ujson.Null).flatMap(_.obj.get("spdx_id")).filterNot(_ == ujson.Null).map(_.str)

    val repositoryId = Store.upsertRepository(
      conn,
      owner,
      repo,
      item("html_url").str,
      description = description,
      repoCreatedAt = repoCreatedAt,
      forksCount = forksCount,
      topics = topics,
      defaultBranch = defaultBranch,
      currentStars = stars,
      currentLanguage = language,
      currentSizeKb = sizeKb,
      currentArchived = isArchived,
      currentFork = isFork,
      currentLicense = license
    )

    Store.exec(
      conn,
      "INSERT INTO search_repos_run_repositories " +
        "(search_repos_run_id, repository_id, stars, language, pushed_at, size_kb, is_fork, is_archived, license) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT(search_repos_run_id, repository_id) DO UPDATE SET " +
        "stars = excluded.stars, language = excluded.language, pushed_at = excluded.pushed_at, " +
        "size_kb = excluded.size_kb, is_fork = excluded.is_fork, is_archived = excluded.is_archived, license = excluded.license",
      Seq(
        searchRepoRunSurrogateId,
        repositoryId,
        stars.map(_.asInstanceOf[Any]).orNull,
        language.orNull,
        pushedAt.orNull,
        sizeKb.map(_.asInstanceOf[Any]).orNull,
        isFork.map(_.asInstanceOf[Any]).orNull,
        isArchived.map(_.asInstanceOf[Any]).orNull,
        license.orNull
      )
    )

    println(
      s"REPO owner=$owner repo=$repo stars=${stars.getOrElse("?")} language=${language.getOrElse("?")} " +
        s"sizeKb=${sizeKb.getOrElse("?")} url=${item("html_url").str}"
    )

  // ---------------------------------------------------------------------
  // Validation and plumbing
  // ---------------------------------------------------------------------

  private def validateFork(v: String): String =
    if Set("true", "false", "only").contains(v) then v
    else fail(s"--fork must be one of true, false, only (got '$v')")

  private def validateBoolFlag(flagName: String)(v: String): String =
    if Set("true", "false").contains(v) then v
    else fail(s"$flagName must be true or false (got '$v')")

  private def parseArgs(args: Array[String]): Map[String, String] =
    val map = scala.collection.mutable.Map[String, String]()
    var i = 0
    while i < args.length do
      val a = args(i)
      if a.startsWith("--") && i + 1 < args.length then
        map(a.drop(2)) = args(i + 1)
        i += 2
      else i += 1
    map.toMap

  private def fail(msg: String): Nothing =
    System.err.println(s"ERROR: $msg")
    sys.exit(1)

end SearchRepos
