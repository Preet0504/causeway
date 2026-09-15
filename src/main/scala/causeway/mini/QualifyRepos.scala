package causeway.mini

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager}
import java.time.{Duration as JDuration, Instant}

/** Cheaply qualifies discovered repositories before anything clones them.
  * `/inspect_repo` cloning a repository is the expensive step in this
  * pipeline; a repository that has issues disabled, or has never had a
  * closed issue or a merged pull request, can never produce the kind of
  * commit-to-issue evidence the rest of the pipeline looks for, no matter
  * how it's mined, so there's no reason to pay for a clone to find that
  * out. Everything here comes from a handful of GitHub API calls: repo
  * metadata, four search-API existence counts (closed/open issues,
  * merged/open PRs), and one recursive tree listing (paths only, no file
  * content, no clone) pattern-matched for test-like paths. Fully
  * mechanical, no judgment calls, hence a plain CLI subcommand rather than
  * something routed through an agent.
  */
object QualifyRepos:

  private val ApiBase = "https://api.github.com"
  private val DbPath = "workspace/causeway.db"

  /** The outcome of qualifying one repository. `qualifies` is decided by
    * [[decideQualification]] from `hasIssues`/`closedIssueCount`/
    * `mergedPrCount` alone; `openIssueCount`, `openPrCount`, and
    * `testFileCount` (see [[detectTestEvidence]]) are richer activity
    * signals recorded for context but never affect `qualifies` themselves.
    */
  private[mini] case class QualificationResult(
      htmlUrl: String,
      description: Option[String],
      repoCreatedAt: Option[String],
      forksCount: Option[Int],
      topics: List[String],
      defaultBranch: String,
      currentStars: Option[Int],
      currentLanguage: Option[String],
      currentSizeKb: Option[Int],
      currentArchived: Option[Boolean],
      currentFork: Option[Boolean],
      currentLicense: Option[String],
      hasIssues: Boolean,
      closedIssueCount: Int,
      openIssueCount: Int,
      mergedPrCount: Int,
      openPrCount: Int,
      testFileCount: Int,
      testEvidencePaths: List[String],
      treeTruncated: Boolean,
      qualifies: Boolean,
      rejectionReason: Option[String]
  )

  /** Entry point for the `qualify-repos` subcommand of the unified
    * `Causeway` CLI (see `Causeway.scala`). Two modes: `--search-run-id
    * <id>` qualifies every repository a given `search-repos` run
    * discovered; `--owner <o> --repo <r>` qualifies one repository
    * directly, useful outside a search (e.g. a repo already known from
    * elsewhere).
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val token = sys.env.getOrElse("GITHUB_TOKEN", fail("GITHUB_TOKEN environment variable is required")).trim

    Class.forName("org.sqlite.JDBC")
    new File(DbPath).getAbsoluteFile.getParentFile.mkdirs()
    val conn = DriverManager.getConnection(s"jdbc:sqlite:$DbPath")
    try
      conn.setAutoCommit(false)
      Store.applySchema(conn)

      val targets: List[(String, String, Long)] =
        (opts.get("search-run-id"), opts.get("owner"), opts.get("repo")) match
          case (Some(runId), _, _) => repositoriesFromSearchRun(conn, runId)
          case (None, Some(owner), Some(repo)) =>
            val url = opts.getOrElse("repo-url", s"https://github.com/$owner/$repo")
            List((owner, repo, Store.upsertRepository(conn, owner, repo, url)))
          case _ => fail("Either --search-run-id <id>, or both --owner <o> and --repo <r>, is required")

      if targets.isEmpty then fail("No repositories found for the given --search-run-id")

      var qualifiedCount = 0
      var rejectedCount = 0

      targets.foreach { case (owner, repo, repositoryId) =>
        val result = evaluateRepository(owner, repo, token)
        // Fill in identity fields too, in case this repo was never
        // discovered via search-repos (the --owner/--repo direct path) and
        // so has none of them yet; COALESCE-preserved, so this never
        // clobbers a richer value search-repos already recorded.
        Store.upsertRepository(
          conn,
          owner,
          repo,
          result.htmlUrl,
          description = result.description,
          repoCreatedAt = result.repoCreatedAt,
          forksCount = result.forksCount,
          topics = result.topics,
          defaultBranch = Some(result.defaultBranch),
          currentStars = result.currentStars,
          currentLanguage = result.currentLanguage,
          currentSizeKb = result.currentSizeKb,
          currentArchived = result.currentArchived,
          currentFork = result.currentFork,
          currentLicense = result.currentLicense
        )
        upsertQualification(conn, repositoryId, result)
        if result.qualifies then qualifiedCount += 1 else rejectedCount += 1
        println(
          s"QUALIFY owner=$owner repo=$repo qualifies=${result.qualifies} " +
            s"stars=${result.currentStars.getOrElse("?")} language=${result.currentLanguage.getOrElse("?")} " +
            s"sizeKb=${result.currentSizeKb.getOrElse("?")} " +
            s"closedIssues=${result.closedIssueCount} openIssues=${result.openIssueCount} " +
            s"mergedPrs=${result.mergedPrCount} openPrs=${result.openPrCount} testFiles=${result.testFileCount}" +
            result.rejectionReason.map(r => s" reason=\"$r\"").getOrElse("")
        )
      }

      conn.commit()
      println(s"QUALIFIED_COUNT=$qualifiedCount")
      println(s"REJECTED_COUNT=$rejectedCount")
      println(s"DATABASE=$DbPath")
    catch
      case e: Exception =>
        conn.rollback()
        fail(s"Qualification failed, rolled back, no partial write: ${e.getMessage}")
    finally conn.close()
  end run

  private def repositoriesFromSearchRun(conn: Connection, runId: String): List[(String, String, Long)] =
    val ps = conn.prepareStatement(
      "SELECT r.owner, r.repo, r.id FROM search_repos_run_repositories srr " +
        "JOIN search_repos_runs sr ON sr.id = srr.search_repos_run_id " +
        "JOIN repositories r ON r.id = srr.repository_id " +
        "WHERE sr.run_id = ?"
    )
    try
      ps.setString(1, runId)
      val rs = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer[(String, String, Long)]()
      while rs.next() do buf += ((rs.getString(1), rs.getString(2), rs.getLong(3)))
      buf.toList
    finally ps.close()

  // ---------------------------------------------------------------------
  // GitHub calls
  // ---------------------------------------------------------------------

  private def evaluateRepository(owner: String, repo: String, token: String): QualificationResult =
    val metadata = ujson.read(httpGet(s"$ApiBase/repos/$owner/$repo", token))
    val hasIssues = metadata("has_issues").bool
    val defaultBranch = metadata("default_branch").str

    // Stable identity-ish fields, in the same response, so qualify-repos
    // can fill these in on `repositories` even for a repo that was never
    // discovered via search-repos (the --owner/--repo direct path).
    val description = metadata.obj.get("description").filterNot(_ == ujson.Null).map(_.str)
    val repoCreatedAt = metadata.obj.get("created_at").filterNot(_ == ujson.Null).map(_.str)
    val forksCount = metadata.obj.get("forks_count").map(_.num.toInt)
    val topics = metadata.obj.get("topics").map(_.arr.toList.map(_.str)).getOrElse(Nil)

    // This call's own point-in-time snapshot (stars/language/size/etc.),
    // fed into the shared `current_*` columns on `repositories`,
    // deliberately separate from search_repos_run_repositories' per-search
    // history, see schema.sql's comment on `repositories` for why.
    val currentStars = metadata.obj.get("stargazers_count").map(_.num.toInt)
    val currentLanguage = metadata.obj.get("language").filterNot(_ == ujson.Null).map(_.str)
    val currentSizeKb = metadata.obj.get("size").map(_.num.toInt)
    val currentArchived = metadata.obj.get("archived").map(_.bool)
    val currentFork = metadata.obj.get("fork").map(_.bool)
    val currentLicense = metadata.obj.get("license").filterNot(_ == ujson.Null).flatMap(_.obj.get("spdx_id")).filterNot(_ == ujson.Null).map(_.str)

    val closedIssueCount = searchTotalCount(owner, repo, "type:issue state:closed", token)
    val openIssueCount = searchTotalCount(owner, repo, "type:issue state:open", token)
    val mergedPrCount = searchTotalCount(owner, repo, "type:pr is:merged", token)
    val openPrCount = searchTotalCount(owner, repo, "type:pr state:open", token)

    val treeJson = ujson.read(httpGet(s"$ApiBase/repos/$owner/$repo/git/trees/${URLEncoder.encode(defaultBranch, StandardCharsets.UTF_8)}?recursive=1", token))
    val truncated = treeJson.obj.get("truncated").exists(_.bool)
    val paths = treeJson.obj.get("tree").map(_.arr.toList).getOrElse(Nil).map(_("path").str)
    val testEvidence = detectTestEvidence(paths)

    val (qualifies, reason) = decideQualification(hasIssues, closedIssueCount, mergedPrCount)

    QualificationResult(
      htmlUrl = metadata("html_url").str,
      description = description,
      repoCreatedAt = repoCreatedAt,
      forksCount = forksCount,
      topics = topics,
      defaultBranch = defaultBranch,
      currentStars = currentStars,
      currentLanguage = currentLanguage,
      currentSizeKb = currentSizeKb,
      currentArchived = currentArchived,
      currentFork = currentFork,
      currentLicense = currentLicense,
      hasIssues = hasIssues,
      closedIssueCount = closedIssueCount,
      openIssueCount = openIssueCount,
      mergedPrCount = mergedPrCount,
      openPrCount = openPrCount,
      // The true count, not capped, testEvidencePaths (a sample for
      // storage/display) is capped separately below.
      testFileCount = testEvidence.size,
      testEvidencePaths = testEvidence.take(5),
      treeTruncated = truncated,
      qualifies = qualifies,
      rejectionReason = reason
    )

  private def searchTotalCount(owner: String, repo: String, extraQualifiers: String, token: String): Int =
    val q = URLEncoder.encode(s"repo:$owner/$repo $extraQualifiers", StandardCharsets.UTF_8)
    val body = httpGet(s"$ApiBase/search/issues?q=$q&per_page=1", token)
    ujson.read(body)("total_count").num.toInt

  private def httpGet(url: String, token: String): String =
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
      fail(s"GitHub API request to $url failed with status ${response.statusCode()}: ${response.body().take(500)}")
    response.body()

  // ---------------------------------------------------------------------
  // Pure decision logic (regression-tested directly, no HTTP involved)
  // ---------------------------------------------------------------------

  /** The two hard disqualifiers: issues turned off entirely, or no closed
    * issue and no merged PR ever, in either case there is no possible
    * commit-to-issue evidence this pipeline could ever find here, no
    * matter what window or scan limit a later `/inspect_repo` run used.
    */
  private[mini] def decideQualification(hasIssues: Boolean, closedIssueCount: Int, mergedPrCount: Int): (Boolean, Option[String]) =
    if !hasIssues then (false, Some("issues are disabled on this repository"))
    else if closedIssueCount == 0 && mergedPrCount == 0 then
      (false, Some("no closed issues and no merged pull requests found, no possible bug-fix evidence source"))
    else (true, None)

  private val TestPathIndicators: List[scala.util.matching.Regex] = List(
    """(?i)(^|/)(tests?|specs?|__tests__)(/|$)""".r,
    """(?i)(^|/)[^/]*Tests?\.(java|kt|kts|scala)$""".r,
    """(?i)(^|/)[^/]*Spec\.(scala|kt|groovy)$""".r,
    """(^|/)test_[^/]+\.py$""".r,
    """(^|/)[^/]+_test\.(py|go)$""".r,
    """[^/]+\.(test|spec)\.[jt]sx?$""".r,
    """(^|/)[^/]+_spec\.rb$""".r
  )

  /** Every path (out of a repository's full file tree) that looks like a
    * test, by common naming convention across several ecosystems. A
    * pattern-match heuristic, not authoritative, a repository can easily
    * have tests this doesn't recognize (an unconventional layout, a
    * language not covered here), which is exactly why this signal is
    * recorded but never on its own fails [[decideQualification]].
    */
  private[mini] def detectTestEvidence(paths: List[String]): List[String] =
    paths.filter(p => TestPathIndicators.exists(_.findFirstIn(p).isDefined))

  // ---------------------------------------------------------------------
  // Database write
  // ---------------------------------------------------------------------

  /** Qualification columns live directly on `repositories` (see
    * `schema.sql`), a plain `UPDATE` by id, not an upsert: the row itself
    * is always already there by the time this runs (found via a search, or
    * created outright by the `--owner`/`--repo` path in `run` above), only
    * these columns are new information.
    */
  private[mini] def upsertQualification(conn: Connection, repositoryId: Long, result: QualificationResult): Unit =
    // No qualifies column: it would always be exactly `rejection_reason IS
    // NULL`, storing both would be pure redundancy. "Qualifies" is a query
    // condition, computed where needed, not a persisted fact.
    //
    // No current_* columns here either: those are written by the shared
    // `Store.upsertRepository` call above (the single source of truth both
    // `search-repos` and `qualify-repos` feed), setting them again here
    // would just be a second, easy-to-forget place to keep in sync.
    Store.exec(
      conn,
      "UPDATE repositories SET " +
        "has_issues = ?, closed_issue_count = ?, open_issue_count = ?, merged_pr_count = ?, open_pr_count = ?, " +
        "test_file_count = ?, test_evidence_paths = ?, tree_truncated = ?, " +
        "rejection_reason = ?, checked_at = ? " +
        "WHERE id = ?",
      Seq(
        result.hasIssues,
        result.closedIssueCount,
        result.openIssueCount,
        result.mergedPrCount,
        result.openPrCount,
        result.testFileCount,
        if result.testEvidencePaths.isEmpty then null else result.testEvidencePaths.mkString(","),
        result.treeTruncated,
        result.rejectionReason.orNull,
        Instant.now().toString,
        repositoryId
      )
    )

  // ---------------------------------------------------------------------
  // Plumbing
  // ---------------------------------------------------------------------

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

end QualifyRepos
