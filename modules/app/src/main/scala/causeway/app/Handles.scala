package causeway.app

import causeway.core.MethodRef
import causeway.jvm.{CallGraphHandle, CoverageReport, CoverageService}
import causeway.mcp.Json
import causeway.vcs.GitService

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Server-side state that tools refer to by handle.
  *
  * A clone, a build tree and a call graph are all far too large to travel through an agent's
  * context. Tools return an opaque handle; the agent queries through it. This is what keeps
  * context small no matter how big the repository is.
  *
  * Handles are derived from content (the clone URL, the build directory) rather than allocated
  * from a counter, so the same thing resolves to the same handle across a restart and a resumed
  * run does not strand its references.
  */
final class Handles(val workspace: Path):
  private val repos      = TrieMap.empty[String, (GitService, Path)]
  private val remotes    = TrieMap.empty[String, String]
  private val builds     = TrieMap.empty[String, (Path, String)]
  /** Call graphs, BOUNDED. Access-ordered, so eviction drops the least recently used.
    *
    * Each `CallGraphHandle` retains a SootUp `JavaView`, and every view pins the whole JDK
    * runtime plus the project's classes — hundreds of megabytes apiece, on top of a graph of
    * ~6500 nodes and ~29000 edges. Unbounded, a server completed exactly three builds and then
    * hung forever on the fourth, and from that moment every JVM-path tool hung too: not a lock,
    * a garbage collector with nothing left to reclaim. Cheap `jvm_method_at_line` calls hung
    * alongside it because they open a full view of their own, while `graph_*` and `notes_*`
    * kept answering because they barely allocate.
    *
    * Two is deliberate rather than tuned. The pipeline compares a parent and a fix; a third
    * live view means something is being retained that nobody is reading.
    */
  private val callGraphLimit =
    sys.env.get("CAUSEWAY_CALLGRAPH_CACHE").flatMap(_.toIntOption).filter(_ > 0).getOrElse(2)

  private val callGraphs =
    java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap[String, CallGraphHandle](16, 0.75f, true) {
        override def removeEldestEntry(e: java.util.Map.Entry[String, CallGraphHandle]): Boolean =
          val over = size() > callGraphLimit
          if over then evictedGraphs.add(e.getKey)
          over
      }
    )

  /** Ids that WERE built and have since been evicted.
    *
    * Kept so an agent citing one gets told to rebuild, rather than the same message it would
    * get for an id that never existed. The two situations call for different actions.
    */
  private val evictedGraphs = java.util.Collections.synchronizedSet(new java.util.HashSet[String]())
  private val coverage   = TrieMap.empty[String, CoverageReport]
  private val traces     = TrieMap.empty[String, Vector[MethodRef]]

  // ── the handle journal ─────────────────────────────────────────────────

  /** An append-only record of what each handle was derived FROM.
    *
    * Handles are content-derived, so the same inputs always produce the same handle — but the
    * MAP from handle to inputs lived only in memory, and a process that died took it with it.
    * S7 lost that map twice: the coverage runs had completed, their `.exec` files were sitting
    * on disk, and the next process had no way to know which report id they belonged to. It
    * would have re-run the whole suite.
    *
    * Inputs only, never payloads. A parsed coverage report is derived data and re-parsing it
    * takes seconds; the exec file it came from is the fact. This keeps the same discipline as
    * the rest of the system — the handle travels, the object does not.
    */
  private def journalPath: Path = workspace.resolve("handles.jsonl")

  private def journal(fields: (String, String)*): Unit =
    Try {
      Files.createDirectories(workspace)
      val n = Json.obj()
      fields.foreach((k, v) => n.put(k, v))
      Files.writeString(journalPath, Json.write(n) + "\n",
        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

  /** The last journal entry for a handle, if one was ever written. */
  private def recorded(handle: String): Option[Map[String, String]] =
    if !Files.isRegularFile(journalPath) then None
    else
      Try {
        Using.resource(Files.lines(journalPath)) { lines =>
          lines.iterator().asScala
            .flatMap(l => Try(Json.parse(l)).toOption)
            .filter(n => Option(n.get("handle")).exists(_.stringValue() == handle))
            .map { n =>
              n.propertyNames().asScala.map(k => k -> n.get(k).stringValue()).toMap
            }
            .toVector
            .lastOption
        }
      }.toOption.flatten

  private def digest(s: String, prefix: String): String =
    val d = MessageDigest.getInstance("SHA-256")
      .digest(s.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    s"${prefix}_${d.take(16)}"

  // ── clones ─────────────────────────────────────────────────────────────

  def repoHandle(url: String): String = digest(url, "repo")

  /** Registers a clone AND journals it — found missing by actually running a mining session:
    * `builds` and `coverage` were journaled, `repos` never was, so a server restart mid-run
    * (recovering from an unrelated fix) dropped every repoHandle's clone binding while the
    * clone itself sat untouched on disk. Every tool needing the clone returned
    * `Unknown(NotApplicable)` until `repo_clone` was called a second time by hand. `builds` and
    * `coverage` already rehydrate from disk on a miss (see `rehydrateBuild`,
    * `rehydrateCoverage`); `repos` now does too, the same way.
    */
  def register(handle: String, svc: GitService, dir: Path, url: String = ""): Unit =
    repos.put(handle, (svc, dir))
    if url.nonEmpty then remotes.put(handle, url)
    journal("kind" -> "repo", "handle" -> handle,
            "dir" -> dir.toAbsolutePath.toString, "url" -> url)

  /** Recover a repo handle issued by an earlier process.
    *
    * Only if the directory is still a git repository — a handle pointing at a deleted or
    * half-cloned directory would be worse than a miss, since every downstream call would look
    * like it succeeded against an empty tree.
    */
  private def rehydrateRepo(handle: String): Option[(GitService, Path)] =
    for
      row <- recorded(handle)
      dir <- row.get("dir").map(Path.of(_)) if Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git"))
      svc <- GitService.open(dir).toOption
    yield
      repos.put(handle, (svc, dir))
      row.get("url").filter(_.nonEmpty).foreach(remotes.put(handle, _))
      (svc, dir)

  private def repo(handle: String): Option[(GitService, Path)] =
    repos.get(handle).orElse(rehydrateRepo(handle))

  /** The URL a clone came from, so owner/name can be derived rather than guessed. */
  def remoteUrl(handle: String): Option[String] =
    remotes.get(handle).orElse {
      val _ = repo(handle) // trigger rehydration, which repopulates `remotes` as a side effect
      remotes.get(handle)
    }

  def git(handle: String): Option[GitService] = repo(handle).map(_._1)
  def dir(handle: String): Option[Path]       = repo(handle).map(_._2)

  // ── builds ─────────────────────────────────────────────────────────────

  def registerBuild(dir: Path, image: String): String =
    val h = digest(s"${dir.toAbsolutePath}|$image", "build")
    builds.put(h, (dir, image))
    journal("kind" -> "build", "handle" -> h,
            "dir" -> dir.toAbsolutePath.toString, "image" -> image)
    h

  /** Recover a build handle issued by an earlier process.
    *
    * Only if the directory is still there — a handle pointing at a deleted worktree would be
    * worse than a miss, because the caller would get an empty classpath rather than an error.
    */
  private def rehydrateBuild(handle: String): Option[(Path, String)] =
    for
      row   <- recorded(handle)
      dir   <- row.get("dir").map(Path.of(_)) if Files.isDirectory(dir)
      image <- row.get("image")
    yield
      builds.put(handle, (dir, image))
      (dir, image)

  /** Materialise one revision in its own worktree, reusing it if already present.
    *
    * Per-commit rather than moving the shared clone: two probes can then run at once without
    * fighting over the checkout, and the clone stays on its default branch for the history
    * queries that assume it. Costs disk, not downloads — the dependency cache is shared.
    */
  def worktreeAt(handle: String, commit: String): Either[String, Path] =
    git(handle) match
      case None => Left(s"no clone registered for '$handle'")
      case Some(g) =>
        val target = workspace.resolve("checkouts").resolve(s"${handle}_${commit.take(12)}")
        if Files.isDirectory(target.resolve(".git")) || Files.isRegularFile(target.resolve(".git"))
        then Right(target)
        else g.checkoutTo(commit, target).map(_ => target)

  /** Where downloaded dependencies live, shared across every build in this workspace.
    *
    * One location, not one per repository: artifacts are addressed by coordinate, so two
    * projects that both depend on junit should download it once between them.
    */
  def cacheRoot: Path = workspace.resolve("caches")

  private def build(handle: String): Option[(Path, String)] =
    builds.get(handle).orElse(rehydrateBuild(handle))

  def buildDir(handle: String): Option[Path]     = build(handle).map(_._1)
  def buildImage(handle: String): Option[String] = build(handle).map(_._2)

  /** Compiled output directories for a build, as a classpath.
    *
    * Discovered rather than assumed: which directory holds classes depends on the build tool,
    * and guessing `target/classes` would silently produce an empty call graph on a Gradle or sbt
    * project — indistinguishable from a project whose code calls nothing.
    */
  def buildClasspath(handle: String): Vector[String] =
    buildDir(handle).toVector.flatMap { root =>
      val candidates = Vector(
        root.resolve("target/classes"),                 // maven
        root.resolve("build/classes/java/main"),        // gradle
        root.resolve("build/classes/kotlin/main"),
        root.resolve("target/scala-2.13/classes"),      // sbt
        root.resolve("target/scala-3/classes")
      ).filter(Files.isDirectory(_))

      if candidates.nonEmpty then candidates.map(_.toAbsolutePath.toString)
      else
        // Fall back to a bounded search for any directory containing class files. Bounded
        // because a deep unbounded walk over a large repository is slow enough to look hung.
        Try {
          Using.resource(Files.walk(root, 6)) { s =>
            s.iterator().asScala
              .filter(p => p.toString.endsWith(".class"))
              .map(_.getParent)
              .take(400)
              .toVector
              .distinct
              .take(20)
              .map(_.toAbsolutePath.toString)
          }
        }.getOrElse(Vector.empty)
    }.distinct

  // ── call graphs ────────────────────────────────────────────────────────

  def registerCallGraph(h: CallGraphHandle): Unit =
    evictedGraphs.remove(h.id)
    callGraphs.put(h.id, h)

  def callGraph(id: String): Option[CallGraphHandle] = Option(callGraphs.get(id))

  /** Was this id built earlier and dropped to bound memory? */
  def callGraphEvicted(id: String): Boolean = evictedGraphs.contains(id)

  private[app] def liveCallGraphs: Int = callGraphs.size

  // ── coverage ───────────────────────────────────────────────────────────

  def registerCoverage(r: CoverageReport): Unit = coverage.put(r.id, r)

  /** Record what a report was parsed FROM, so it can be rebuilt without re-running tests. */
  def registerCoverage(r: CoverageReport, execFile: Path, classDirs: Vector[Path]): Unit =
    coverage.put(r.id, r)
    journal("kind" -> "coverage", "handle" -> r.id,
            "execFile" -> execFile.toAbsolutePath.toString,
            "classDirs" -> classDirs.map(_.toAbsolutePath.toString).mkString("|"))

  /** Re-parse a report from the exec file that produced it.
    *
    * This is the one that matters. A coverage run costs two to four MINUTES of test execution;
    * re-parsing its `.exec` costs seconds. When S7 died mid-stage, all nineteen exec files were
    * intact on disk and the only thing missing was the mapping from report id back to them.
    */
  private def rehydrateCoverage(id: String): Option[CoverageReport] =
    for
      row  <- recorded(id)
      exec <- row.get("execFile").map(Path.of(_)) if Files.isRegularFile(exec)
      dirs  = row.get("classDirs").toVector.flatMap(_.split('|')).filter(_.nonEmpty).map(Path.of(_))
      // Re-parsing recomputes the id from content. If it does not match, the exec file has been
      // overwritten by a later run and this is a DIFFERENT measurement wearing the same name.
      report <- CoverageService.parse(exec, dirs).toOption if report.id == id
    yield
      coverage.put(id, report)
      report

  def coverageReport(id: String): Option[CoverageReport] =
    coverage.get(id).orElse(rehydrateCoverage(id))

  // ── execution traces ───────────────────────────────────────────────────

  /** Field separator within one method, and between methods, in the journal row. ``/
    * `` rather than a printable character: a descriptor can carry almost anything a JVM
    * type signature allows, and an fqcn can carry a dot, so no printable delimiter is safe.
    * Matches the convention `NativeTestRunner.Sep` already uses for the same reason.
    */
  private val TraceFieldSep  = 1.toChar.toString
  private val TraceMethodSep = 2.toChar.toString

  /** Record what actually ran during a reproducing execution, AND journal it.
    *
    * Keyed by content so the same run resolves to the same id across a restart, like every
    * other handle here — but unlike builds and coverage, there is no artifact on disk this can
    * be RE-DERIVED from: the container that produced it is `--rm`, gone the moment the run
    * ends, and the differential tools are idempotent on their inputs (found live: replaying the
    * identical harness after a trace was lost returned the same cached differential result
    * without re-executing, so it could not re-register one either). A restart previously meant
    * every trace issued so far was gone for good, with the only recovery being to perturb the
    * input and re-run the whole differential — costly, and for one bug outright non-deterministic
    * (a timeout-raced test produced a genuinely different executed set on retry). Found by
    * actually running a mining session: three separate path-tracer dispatches lost their trace
    * to an unrelated server restart mid-run, each losing real analysis work to rediscover that
    * the trace their tasking cited no longer existed. The content itself is small — a method
    * list, not a multi-hundred-megabyte call graph — so, unlike a call graph, journaling it
    * costs nothing worth measuring.
    */
  def registerTrace(methods: Vector[MethodRef]): String =
    val id = digest(methods.map(_.signature).mkString("|"), "tr")
    traces.put(id, methods)
    journal("kind" -> "trace", "handle" -> id,
      "methods" -> methods.map(m => s"${m.fqcn}$TraceFieldSep${m.name}$TraceFieldSep${m.descriptor}")
        .mkString(TraceMethodSep))
    id

  /** Recover a trace issued by an earlier process, from its journaled method list.
    *
    * The id is recomputed from the decoded content and compared against what was asked for — a
    * mismatch means the row is corrupt (or, if journal rows are ever pruned/compacted by hand,
    * stale) and must not be handed back silently mislabelled as the id it does not match.
    */
  private def rehydrateTrace(id: String): Option[Vector[MethodRef]] =
    for
      row <- recorded(id)
      raw <- row.get("methods")
      methods = if raw.isEmpty then Vector.empty
        else raw.split(TraceMethodSep, -1).toVector.map { entry =>
          val parts = entry.split(TraceFieldSep, -1)
          MethodRef(parts(0), parts(1), if parts.length > 2 then parts(2) else "")
        }
      if digest(methods.map(_.signature).mkString("|"), "tr") == id
    yield
      traces.put(id, methods)
      methods

  def trace(id: String): Option[Vector[MethodRef]] = traces.get(id).orElse(rehydrateTrace(id))

  // ── lifecycle ──────────────────────────────────────────────────────────

  def known: Vector[String] = repos.keys.toVector.sorted

  def closeAll(): Unit =
    repos.values.foreach { case (svc, _) => Try(svc.close()) }
    repos.clear()
    remotes.clear()
    builds.clear()
    callGraphs.clear()
    coverage.clear()
    traces.clear()
