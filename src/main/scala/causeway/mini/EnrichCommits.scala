package causeway.mini

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, EmptyTreeIterator}

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Duration as JDuration}
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

/** Enriches each commit in an /inspect_repo evidence file with the pull
  * requests and issues GitHub associates with it (via a single batched
  * GraphQL query per group of commits) and its old/new-side diff hunks
  * (via JGit, run locally in parallel — no network involved). Writes a new
  * "_enriched" evidence file; the original is left untouched.
  */
object EnrichCommits:

  private val GraphqlUrl = "https://api.github.com/graphql"
  private val GraphqlBatchSize = 20
  private val DiffThreadCount = 8

  def main(args: Array[String]): Unit =
    val opts = parseArgs(args)
    val evidencePath = opts.getOrElse("evidence-file", fail("--evidence-file is required"))
    val token = sys.env.getOrElse("GITHUB_TOKEN", fail("GITHUB_TOKEN environment variable is required")).trim

    val evidenceFile = new File(evidencePath)
    if !evidenceFile.exists() then fail(s"Evidence file not found: $evidencePath")

    val evidence = ujson.read(Files.readString(evidenceFile.toPath))
    val owner = evidence("owner").str
    val repoName = evidence("repo").str
    val clonePath = evidence("clonePath").str
    val scanCommitLimit: Option[Int] = evidence("scanCommitLimit") match
      case ujson.Null => None
      case v          => Some(v.num.toInt)

    val allCommits = evidence("commits").arr.toList
    val commitsToEnrich = scanCommitLimit match
      case Some(limit) => allCommits.take(limit)
      case None        => allCommits

    System.err.println(
      s"Enriching ${commitsToEnrich.size} of ${allCommits.size} window commits (scanCommitLimit=${scanCommitLimit.getOrElse("none")})"
    )

    val shas = commitsToEnrich.map(_("sha").str)

    val prIssueBySha = fetchPullRequestsAndIssues(owner, repoName, shas, token)

    val repository = Git.open(new File(clonePath)).getRepository
    val diffExecutor = Executors.newFixedThreadPool(DiffThreadCount)
    given ExecutionContext = ExecutionContext.fromExecutor(diffExecutor)
    val diffFutures = shas.map(sha => Future(sha -> diffCommit(repository, sha)))
    val diffBySha = Await.result(Future.sequence(diffFutures), Duration.Inf).toMap
    diffExecutor.shutdown()
    repository.close()

    val enrichedCommits = commitsToEnrich.map { c =>
      val sha = c("sha").str
      ujson.Obj(
        "sha" -> c("sha"),
        "shortMessage" -> c("shortMessage"),
        "fullMessage" -> c("fullMessage"),
        "pullRequests" -> prIssueBySha.getOrElse(sha, ujson.Arr()),
        "diff" -> diffBySha.getOrElse(sha, emptyDiff)
      )
    }

    val enrichedEvidence = ujson.Obj(
      "runId" -> evidence("runId"),
      "repoUrl" -> evidence("repoUrl"),
      "owner" -> evidence("owner"),
      "repo" -> evidence("repo"),
      "clonePath" -> evidence("clonePath"),
      "window" -> evidence("window"),
      "scanCommitLimit" -> evidence("scanCommitLimit"),
      "windowCommitCount" -> evidence("windowCommitCount"),
      "enrichedCommitCount" -> enrichedCommits.size,
      "commits" -> ujson.Arr.from(enrichedCommits)
    )

    val outFile = new File(
      evidenceFile.getParentFile,
      evidenceFile.getName.stripSuffix(".json") + "_enriched.json"
    )
    Files.write(outFile.toPath, ujson.write(enrichedEvidence, indent = 2).getBytes(StandardCharsets.UTF_8))

    println(s"ENRICHED_COUNT=${enrichedCommits.size}")
    println(s"ENRICHED_FILE=${outFile.getPath}")

    enrichedCommits.take(3).foreach { c =>
      val sha = c("sha").str.take(10)
      val prCount = c("pullRequests").arr.size
      val fileCount = c("diff")("files").arr.size
      val isMerge = c("diff")("isMergeCommit").bool
      println(s"SAMPLE sha=$sha prCount=$prCount diffFileCount=$fileCount isMergeCommit=$isMerge")
    }

    // Both our own thread pool and the JDK HttpClient can leave non-daemon
    // threads idling after main() returns, which would keep the JVM (and any
    // pipe reading its stdout) alive indefinitely even though all real work
    // is done. Force a clean exit rather than rely on every thread pool
    // involved being daemon by default.
    sys.exit(0)
  end main

  private def emptyDiff: ujson.Value =
    ujson.Obj("isMergeCommit" -> false, "parentSha" -> ujson.Null, "files" -> ujson.Arr())

  /** Diffs a single commit against its first parent. Merge commits (more than
    * one parent) are skipped — their diff against any one parent can reflect
    * an entire branch's worth of changes, not the merge's own work, so
    * including it would be misleading rather than merely incomplete.
    */
  private def diffCommit(repository: Repository, sha: String): ujson.Value =
    val walk = new RevWalk(repository)
    try
      val commit = walk.parseCommit(repository.resolve(sha))
      if commit.getParentCount > 1 then
        ujson.Obj("isMergeCommit" -> true, "parentSha" -> ujson.Null, "files" -> ujson.Arr())
      else
        val reader = repository.newObjectReader()
        val newTreeIter = new CanonicalTreeParser()
        newTreeIter.reset(reader, commit.getTree)

        val parentShaOpt =
          if commit.getParentCount == 0 then None else Some(commit.getParent(0).getName)

        val oldTreeIter =
          if commit.getParentCount == 0 then new EmptyTreeIterator()
          else
            val parent = walk.parseCommit(commit.getParent(0))
            val old = new CanonicalTreeParser()
            old.reset(reader, parent.getTree)
            old

        val patchOut = new java.io.ByteArrayOutputStream()
        val df = new DiffFormatter(patchOut)
        df.setRepository(repository)
        df.setDetectRenames(true)
        val entries = df.scan(oldTreeIter, newTreeIter).asScala

        val files = entries.map { entry =>
          val hunks = df.toFileHeader(entry).toEditList.asScala.map { e =>
            ujson.Obj(
              "oldStart" -> (e.getBeginA + 1),
              "oldLineCount" -> (e.getEndA - e.getBeginA),
              "newStart" -> (e.getBeginB + 1),
              "newLineCount" -> (e.getEndB - e.getBeginB)
            )
          }
          patchOut.reset()
          df.format(entry)
          val patchText = patchOut.toString(StandardCharsets.UTF_8)
          ujson.Obj(
            "path" -> entry.getNewPath,
            "oldPath" -> entry.getOldPath,
            "changeType" -> entry.getChangeType.toString,
            "hunks" -> ujson.Arr.from(hunks),
            "patch" -> patchText
          )
        }

        ujson.Obj(
          "isMergeCommit" -> false,
          "parentSha" -> parentShaOpt.map(ujson.Str(_)).getOrElse(ujson.Null),
          "files" -> ujson.Arr.from(files)
        )
    finally walk.dispose()

  /** Fetches PRs (and the issues each PR closes) associated with each commit,
    * one GraphQL request per batch via aliasing — not one request per commit.
    */
  private def fetchPullRequestsAndIssues(
      owner: String,
      repo: String,
      shas: List[String],
      token: String
  ): Map[String, ujson.Value] =
    val client = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(15)).build()
    val results = scala.collection.mutable.Map[String, ujson.Value]()

    shas.grouped(GraphqlBatchSize).foreach { batch =>
      val aliasToSha = batch.zipWithIndex.map { case (sha, i) => s"c$i" -> sha }

      val fields = aliasToSha
        .map { case (alias, sha) =>
          s"""$alias: object(oid: "$sha") {
             |    ... on Commit {
             |      associatedPullRequests(first: 5) {
             |        nodes {
             |          number
             |          title
             |          url
             |          state
             |          closingIssuesReferences(first: 5) {
             |            nodes { number title url }
             |          }
             |        }
             |      }
             |    }
             |  }""".stripMargin
        }
        .mkString("\n  ")

      val query =
        s"""query {
           |  repository(owner: "$owner", name: "$repo") {
           |  $fields
           |  }
           |}""".stripMargin

      val body = ujson.write(ujson.Obj("query" -> query))

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(GraphqlUrl))
        .header("Authorization", s"Bearer $token")
        .header("Content-Type", "application/json")
        .timeout(JDuration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if response.statusCode() != 200 then
        System.err.println(s"WARN: GraphQL request failed with status ${response.statusCode()}: ${response.body().take(500)}")
      else
        val json = ujson.read(response.body())
        json.obj.get("errors").foreach(errs => System.err.println(s"WARN: GraphQL errors: $errs"))

        val repoData = json.obj.get("data").flatMap(_.obj.get("repository"))
        aliasToSha.foreach { case (alias, sha) =>
          val prNodes = repoData
            .flatMap(_.obj.get(alias))
            .filterNot(_ == ujson.Null)
            .flatMap(_.obj.get("associatedPullRequests"))
            .flatMap(_.obj.get("nodes"))
            .map(_.arr.toList)
            .getOrElse(Nil)

          val prs = prNodes.map { pr =>
            val closingIssues = pr.obj
              .get("closingIssuesReferences")
              .flatMap(_.obj.get("nodes"))
              .map(_.arr.toList)
              .getOrElse(Nil)
              .map { issue =>
                ujson.Obj(
                  "number" -> issue("number").num.toInt,
                  "title" -> issue("title").str,
                  "url" -> issue("url").str
                )
              }
            ujson.Obj(
              "number" -> pr("number").num.toInt,
              "title" -> pr("title").str,
              "url" -> pr("url").str,
              "state" -> pr("state").str,
              "closingIssues" -> ujson.Arr.from(closingIssues)
            )
          }
          results(sha) = ujson.Arr.from(prs)
        }
    }
    results.toMap

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

end EnrichCommits
