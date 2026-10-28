package causeway.mini

import java.io.{InputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

/** Everything the CLI touches outside itself, so tests can swap it. */
final case class Env(
    dataDir: Path, dbPath: Path, github: () => GitHub,
    stdin: InputStream, out: PrintStream, err: PrintStream, now: () => Instant = () => Instant.now()
)

object Env:
  def fromProcess(): Env =
    val data = Path.of(sys.env.getOrElse("CAUSEWAY_DATA", "data"))
    Env(data, sys.env.get("CAUSEWAY_DB").map(Path.of(_)).getOrElse(data.resolve("causeway.db")),
      () => HttpGitHub(GitHubAuth.resolve()), System.in, System.out, System.err)

/** Subcommand dispatch. Every subcommand prints one JSON document to stdout and returns an exit code. */
final class Cli(env: Env):
  private val Usage =
    """Usage: tools/causeway <command> [options]
      |
      |  search-repos   [--keywords T] [--language L] [--min-stars N] [--max-stars N] [--min-forks N]
      |                 [--pushed-after YYYY-MM-DD] [--created-before YYYY-MM-DD] [--min-size-kb N]
      |                 [--max-size-kb N] [--topics a,b] [--license K] [--max-results N]
      |  list-repos     [--limit N]            qualifying repos found by any past search
      |  inspect-repo   --url U [--name owner/repo] [--branch B] [--commit SHA] [--since YYYY-MM-DD] [--scan-limit N]
      |  enrich         --run ID               attach linked PRs and issues to a run's commits
      |  next-batch     --run ID [--size N]    up to 5 unclassified commits, as the classifier sees them
      |  record-classification --run ID        read the classifier's JSON reply on stdin and store it
      |  run-status     --run ID
      |  show-evidence  --run ID [--only-bug-fixes]
      |  list-runs      [--limit N]
      |  guard-bash     --allow a,b            PreToolUse hook: read a hook payload on stdin
      |""".stripMargin

  def run(argv: Seq[String]): Int =
    try
      val args = Args.parse(argv)
      args.command match
        case None | Some("help") => env.out.print(Usage); 0
        case Some("guard-bash") =>
          val allowed = args.req("allow").split(",").map(_.trim).filter(_.nonEmpty).toSet
          Guard.hook(String(env.stdin.readAllBytes(), UTF_8), allowed, env.err)
        case Some(cmd) =>
          val db = Db.open(env.dbPath)
          try dispatch(cmd, args, db) finally db.close()
    catch
      case e: UsageError => env.err.println(s"error: ${e.getMessage}"); 1
      case e: GitHubError => env.err.println(s"error: ${e.getMessage}"); 1

  private def emit(v: ujson.Value): Int = { env.out.println(Json.pretty(v)); 0 }

  private def dispatch(cmd: String, a: Args, db: Db): Int = cmd match
    case "search-repos" =>
      Discovery.search(env.github(), Discovery.specFrom(a)) match
        case Left(why) => throw UsageError(why)
        case Right((q, checked)) => emit(Discovery.toJson(db.saveSearch(q, checked, env.now().toString), q, checked))

    case "list-repos" =>
      emit(ujson.Obj("repos" -> Json.arr(db.qualifyingRepos(a.int("limit").getOrElse(20)))))

    case "inspect-repo" => emit(inspectRepo(a, db))

    case "enrich" => emit(Enrich.run(env.github(), db, a.req("run"), env.now()))

    case "next-batch" => emit(Classify.batchJson(db, a.req("run"), a.int("size").getOrElse(Classify.MaxBatch)))

    case "record-classification" =>
      val reply = String(env.stdin.readAllBytes(), UTF_8)
      Classify.record(db, a.req("run"), reply, env.now()) match
        case Left(why) => throw UsageError(why)
        case Right(v)  => emit(v)

    case "run-status" =>
      val id = a.req("run")
      val total = db.commits(id).size
      val summary = db.runSummary(id)
      emit(ujson.Obj("status" -> summary, "unclassified" -> (total - summary("classified").num.toInt)))

    case "show-evidence" =>
      val id = a.req("run")
      emit(ujson.Obj("status" -> db.runSummary(id),
        "commits" -> Json.arr(db.classifications(id, a.flag("only-bug-fixes")))))

    case "list-runs" =>
      emit(ujson.Obj("runs" -> Json.arr(db.listRuns(a.int("limit").getOrElse(20)))))

    case other => throw UsageError(s"unknown command '$other'; try 'help'")

  private def inspectRepo(a: Args, db: Db): ujson.Value =
    val url = a.req("url")
    val name = a.get("name").orElse(RepoMiner.parseRepoName(url))
      .getOrElse(throw UsageError("could not tell owner/repo from --url; pass --name owner/repo"))
    val since = a.get("since").map { d =>
      scala.util.Try(LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant)
        .getOrElse(throw UsageError(s"--since must look like 2025-01-31, got '$d'"))
    }
    val limit = a.int("scan-limit").getOrElse(100)
    if limit < 1 then throw UsageError("--scan-limit must be at least 1")

    val dir = env.dataDir.resolve("repos").resolve(name.replace('/', '_'))
    val pinned = RepoMiner.cloneAt(url, dir, a.get("branch"), a.get("commit"))
    val commits = RepoMiner.readCommits(dir, pinned.sha, since, limit)
    val id = UUID.randomUUID().toString.take(8)
    db.createRun(
      RunRow(id, env.now().toString, name, url, pinned.branch, pinned.sha, dir.toString,
        since.map(_.toString), limit, None),
      commits)
    ujson.Obj("run" -> id, "repo" -> name, "pinned_sha" -> pinned.sha, "branch" -> Json.str(pinned.branch),
      "commits" -> commits.size, "since" -> Json.str(since.map(_.toString)), "scan_limit" -> limit)
