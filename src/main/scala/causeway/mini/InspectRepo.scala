package causeway.mini

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{ObjectId, PersonIdent, Repository}
import org.eclipse.jgit.revwalk.{RevCommit, RevSort, RevWalk}

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Duration as JDuration, Instant, LocalDate, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** Clones (or reuses) a repo and mines a date window of its commit history.
  * Nothing about which remote or which branch to use is auto-detected:
  * a repo URL alone does not define repository state, a clone can have
  * several remotes (a fork's `origin` and `upstream` point at different
  * repositories entirely), and a branch other than whatever happens to be
  * the default may be exactly what's wanted. Every run is explicit about
  * both, driven by an orchestrator that lists the real options and lets a
  * person choose, rather than the tool silently picking one. Runs as one
  * of five modes, selected by `--mode`:
  *
  *   - `validate --repo-url <url>`: checks whether the URL names a real
  *     GitHub repository (prints `VALID=true` or `VALID=false`), the
  *     network call `/run_causeway` used to make itself via a raw `curl`.
  *     Nothing is cloned, this runs before a repo is committed to at all.
  *   - `list-remotes`: clone (or open an existing clone) and print every
  *     configured remote's name and URL. Nothing is fetched yet.
  *   - `list-branches --remote-name <name>`: fetch from that remote, look
  *     up its configured URL, and print every branch GitHub reports for
  *     that repository, each with its current commit SHA, flagging
  *     whichever one GitHub calls the default.
  *   - `count --remote-name <n> --branch-name <n> --branch-sha <sha>
  *     --since-date <date> --window-label <label>`: a cheap preview of how
  *     many commits fall in the window from that exact, already-chosen
  *     commit. Writes nothing.
  *   - `write` (same arguments as `count`, plus `--scan-commit-limit`):
  *     writes every commit in the window, plus the chosen repository
  *     snapshot, to a JSON evidence file.
  *
  * The `count` and `write` modes trust the given `--branch-sha` outright,
  * they do not re-fetch or re-resolve it, since that SHA is the very thing
  * that was explicitly chosen. Given the same snapshot (the remote, branch,
  * and SHA actually used) and the same arguments, which commits are found
  * and their data is deterministic; the run ID is not, it's a fresh random
  * identifier on every invocation so repeated runs don't collide on their
  * output file name.
  */
object InspectRepo:

  private val GitHubApiBase = "https://api.github.com"

  /** Entry point for the `inspect-repo` subcommand of the unified `Causeway`
    * CLI (see `Causeway.scala`). Not a JVM `main` itself, `Causeway` is the
    * only actual entry point now, this is called with the subcommand's own
    * argument list already stripped of the subcommand name itself.
    */
  def run(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val token = sys.env.getOrElse("GITHUB_TOKEN", fail("GITHUB_TOKEN environment variable is required"))

    if opts.getOrElse("mode", fail("--mode is required (validate, list-remotes, list-branches, count, or write)")) == "validate" then
      val repoUrl = opts.getOrElse("repo-url", fail("--repo-url is required"))
      val (owner, repo) = parseOwnerRepo(repoUrl)
      runValidate(owner, repo, token)
    else
      val repoUrl = opts.getOrElse("repo-url", fail("--repo-url is required"))
      val (owner, repo) = parseOwnerRepo(repoUrl)
      val workspaceDir = new File(s"workspace/$owner-$repo")

      opts("mode") match
        case "list-remotes"  => runListRemotes(repoUrl, workspaceDir)
        case "list-branches" => runListBranches(workspaceDir, opts, token)
        case "count"          => runInspect(repoUrl, owner, repo, workspaceDir, opts, writeEvidence = false)
        case "write"          => runInspect(repoUrl, owner, repo, workspaceDir, opts, writeEvidence = true)
        case other            => fail(s"Unknown --mode '$other'")
  end run

  // ---------------------------------------------------------------------
  // Mode: validate
  // ---------------------------------------------------------------------

  /** Checks whether a repo URL actually names a real GitHub repository,
    * the network call `/run_causeway` used to make itself via a raw
    * `curl`, before it knew a repo URL was even worth acting on further.
    * Deliberately does not clone anything, this runs before a person has
    * committed to mining a specific repo at all.
    */
  private def runValidate(owner: String, repo: String, token: String): Unit =
    val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(15)).build()
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$GitHubApiBase/repos/$owner/$repo"))
      .header("Accept", "application/vnd.github+json")
      .header("Authorization", s"Bearer $token")
      .timeout(JDuration.ofSeconds(30))
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    response.statusCode() match
      case 200 => println("VALID=true")
      case 404 => println("VALID=false")
      case status =>
        fail(s"GitHub API request to validate $owner/$repo failed with status $status: ${response.body().take(500)}")
  end runValidate

  // ---------------------------------------------------------------------
  // Mode: list-remotes
  // ---------------------------------------------------------------------

  private def runListRemotes(repoUrl: String, workspaceDir: File): Unit =
    val git = openOrClone(repoUrl, workspaceDir)
    val remotes = listConfiguredRemotes(git.getRepository)
    if remotes.isEmpty then fail(s"Repository at ${workspaceDir.getPath} has no configured remotes")
    remotes.foreach { case (name, url) => println(s"REMOTE name=$name url=$url") }
    git.close()
  end runListRemotes

  /** Every remote configured on this repository, name paired with its URL,
    * sorted by name for stable output. A fresh clone this tool made itself
    * always has exactly one ("origin", pointing at exactly the requested
    * URL); a reused clone could have more (a fork's "origin" and
    * "upstream" point at different repositories entirely), which is
    * exactly the ambiguity the orchestrator surfaces to a person to
    * resolve, rather than the tool silently picking one.
    */
  private[mini] def listConfiguredRemotes(repository: Repository): List[(String, String)] =
    repository.getConfig.getSubsections("remote").asScala.toList.sorted.map { name =>
      name -> repository.getConfig.getString("remote", name, "url")
    }

  // ---------------------------------------------------------------------
  // Mode: list-branches
  // ---------------------------------------------------------------------

  private def runListBranches(workspaceDir: File, opts: Map[String, String], token: String): Unit =
    val remoteName = opts.getOrElse("remote-name", fail("--remote-name is required"))
    if !workspaceDir.exists() then fail(s"No clone at ${workspaceDir.getPath}, run list-remotes first")
    val git = Git.open(workspaceDir)
    val repository = git.getRepository

    val remoteUrl = repository.getConfig.getString("remote", remoteName, "url")
    if remoteUrl == null then fail(s"No remote named '$remoteName' is configured at ${workspaceDir.getPath}")

    System.err.println(s"Fetching from $remoteName ($remoteUrl) ...")
    git.fetch().setRemote(remoteName).call()
    git.close()

    val (remoteOwner, remoteRepo) = parseOwnerRepo(remoteUrl)
    val defaultBranch = fetchDefaultBranch(remoteOwner, remoteRepo, token)
    val branches = fetchBranches(remoteOwner, remoteRepo, token)
    if branches.isEmpty then fail(s"GitHub reported no branches for $remoteOwner/$remoteRepo")
    branches.foreach { case (name, sha) =>
      println(s"BRANCH name=$name sha=$sha isDefault=${name == defaultBranch}")
    }
  end runListBranches

  private def fetchDefaultBranch(owner: String, repo: String, token: String): String =
    val body = httpGet(s"$GitHubApiBase/repos/$owner/$repo", token)
    ujson.read(body)("default_branch").str

  private def fetchBranches(owner: String, repo: String, token: String): List[(String, String)] =
    val body = httpGet(s"$GitHubApiBase/repos/$owner/$repo/branches?per_page=100", token)
    ujson.read(body).arr.toList.map(b => (b("name").str, b("commit")("sha").str))

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
  // Modes: count / write
  // ---------------------------------------------------------------------

  private def runInspect(
      repoUrl: String,
      owner: String,
      repo: String,
      workspaceDir: File,
      opts: Map[String, String],
      writeEvidence: Boolean
  ): Unit =
    val remoteName = opts.getOrElse("remote-name", fail("--remote-name is required"))
    val branchName = opts.getOrElse("branch-name", fail("--branch-name is required"))
    val branchSha = opts.getOrElse("branch-sha", fail("--branch-sha is required"))
    val sinceDateStr = opts.getOrElse("since-date", fail("--since-date is required (ISO date, e.g. 2026-06-05)"))
    val windowLabel = opts.getOrElse("window-label", sinceDateStr)
    val scanCommitLimit = opts.get("scan-commit-limit").map(_.toInt)

    if !workspaceDir.exists() then fail(s"No clone at ${workspaceDir.getPath}, run list-remotes first")
    val git = Git.open(workspaceDir)
    val repository = git.getRepository

    val startPoint =
      try ObjectId.fromString(branchSha)
      catch case e: IllegalArgumentException => fail(s"--branch-sha '$branchSha' is not a valid commit SHA")

    // Deliberately does not fetch or re-resolve here: branchSha is the exact
    // commit that was already chosen (list-branches already fetched it into
    // this clone). Re-fetching now could silently move the pin to whatever
    // is newest at execution time instead of what was actually chosen.
    try repository.parseCommit(startPoint)
    catch
      case e: Exception =>
        fail(
          s"Commit $branchSha is not present in the local clone at ${workspaceDir.getPath}. " +
            "Run list-branches again to re-fetch before retrying."
        )

    val retrievedAt = Instant.now()
    val sinceEpochSeconds =
      LocalDate.parse(sinceDateStr).atStartOfDay(ZoneOffset.UTC).toInstant.getEpochSecond
    val windowCommits = findCommitsInWindow(repository, startPoint, sinceEpochSeconds)

    if !writeEvidence then
      git.close()
      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
    else
      def personObj(p: PersonIdent) =
        ujson.Obj(
          "name" -> p.getName,
          "email" -> p.getEmailAddress,
          "date" -> p.getWhenAsInstant.toString
        )

      val commitArr = ujson.Arr.from(
        windowCommits.map { c =>
          ujson.Obj(
            "sha" -> c.getName,
            "shortMessage" -> c.getShortMessage,
            "fullMessage" -> c.getFullMessage,
            "author" -> personObj(c.getAuthorIdent),
            "committer" -> personObj(c.getCommitterIdent),
            "commitDate" -> Instant.ofEpochSecond(c.getCommitTime.toLong).toString,
            "parentShas" -> ujson.Arr.from(c.getParents.toList.map(_.getName))
          )
        }
      )

      val runId = UUID.randomUUID().toString

      val evidence = ujson.Obj(
        "runId" -> runId,
        "repoUrl" -> repoUrl,
        "owner" -> owner,
        "repo" -> repo,
        "clonePath" -> workspaceDir.getPath,
        "window" -> ujson.Obj(
          "requested" -> windowLabel,
          "sinceDate" -> sinceDateStr
        ),
        "repoSnapshot" -> ujson.Obj(
          "remoteName" -> remoteName,
          "branch" -> branchName,
          "remoteHeadSha" -> branchSha,
          "retrievedAt" -> retrievedAt.toString
        ),
        "scanCommitLimit" -> scanCommitLimit.map(c => ujson.Num(c.toDouble)).getOrElse(ujson.Null),
        "windowCommitCount" -> windowCommits.size,
        "commits" -> commitArr
      )

      val exportsDir = new File("workspace/exports")
      exportsDir.mkdirs()
      val outFile = new File(exportsDir, s"run_$runId.json")
      Files.write(outFile.toPath, ujson.write(evidence, indent = 2).getBytes("UTF-8"))

      git.close()

      println(s"WINDOW_COMMIT_COUNT=${windowCommits.size}")
      println(s"EVIDENCE_FILE=${outFile.getPath}")
      println(s"RUN_ID=$runId")
  end runInspect

  private def openOrClone(repoUrl: String, workspaceDir: File): Git =
    if workspaceDir.exists() && new File(workspaceDir, ".git").exists() then
      System.err.println(s"Reusing existing clone at ${workspaceDir.getPath}")
      Git.open(workspaceDir)
    else
      System.err.println(s"Cloning $repoUrl into ${workspaceDir.getPath} ...")
      workspaceDir.getParentFile.mkdirs()
      Git.cloneRepository().setURI(repoUrl).setDirectory(workspaceDir).call()

  /** Every commit reachable from `startPoint` whose commit time is at or
    * after `sinceEpochSeconds`, newest first. Does not stop early: Git
    * commit timestamps are not guaranteed to be monotonic along any
    * traversal order. Clock skew, rebases, and merges of
    * independently-developed branches can all produce a commit whose
    * timestamp is out of step with its neighbors in the walk (JGit's own
    * test suite covers exactly this case: a backdated child emitted before
    * a parent with a later timestamp). Stopping at the first commit found
    * to be outside the window would silently miss any later commit, still
    * reachable, that legitimately belongs in it, so every reachable commit
    * is visited and filtered individually instead.
    */
  private[mini] def findCommitsInWindow(
      repository: Repository,
      startPoint: ObjectId,
      sinceEpochSeconds: Long
  ): List[RevCommit] =
    val walk = new RevWalk(repository)
    try
      walk.markStart(walk.parseCommit(startPoint))
      walk.sort(RevSort.COMMIT_TIME_DESC)
      val windowCommits = scala.collection.mutable.ListBuffer[RevCommit]()
      val it = walk.iterator()
      while it.hasNext do
        val c = it.next()
        if c.getCommitTime.toLong >= sinceEpochSeconds then windowCommits += c
      windowCommits.toList
    finally walk.dispose()

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

  private def parseOwnerRepo(url: String): (String, String) =
    val stripped = url
      .replaceFirst("^git@github\\.com:", "")
      .replaceFirst("^https?://github\\.com/", "")
      .replaceFirst("\\.git/?$", "")
      .stripSuffix("/")
    val parts = stripped.split("/")
    if parts.length < 2 then fail(s"Could not parse owner/repo from $url")
    (parts(0), parts(1))

  private def fail(msg: String): Nothing =
    System.err.println(s"ERROR: $msg")
    sys.exit(1)

end InspectRepo
