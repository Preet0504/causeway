package causeway.app

import causeway.core.{CandidateNet, HunkStatus, OldLines, UnknownReason}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import causeway.vcs.{Candidates, GitService}
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** MCP handlers over the vcs services.
  *
  * These are adapters, nothing more: they translate arguments in, call a deterministic service,
  * and shape the result to the schema. No judgement lives here — that is the whole point of the
  * tool/agent split.
  */
object VcsHandlers:

  def all(handles: Handles): Vector[ToolHandler] = Vector(
    repoClone(handles),
    repoDetectEcosystem(handles),
    historyListCommits(handles),
    historyCommitMeta(handles),
    historyDiff(handles),
    historyFileAt(handles),
    historyBlame(handles),
    historyCandidates(handles)
  )

  // ── helpers ────────────────────────────────────────────────────────────

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def bool(a: JsonNode, f: String): Option[Boolean] =
    Option(a.get(f)).filter(_.isBoolean).map(_.booleanValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val a = Json.mapper.createArrayNode()
    node.set(field, a)
    a

  private def missingRepo(handle: String) =
    HandlerResult.Undetermined(UnknownReason.NotApplicable, s"no clone registered for '$handle'")

  private def withGit(handles: Handles, a: JsonNode)(f: GitService => HandlerResult): HandlerResult =
    str(a, "repoHandle") match
      case None => HandlerResult.Undetermined(UnknownReason.NotApplicable, "repoHandle missing")
      case Some(h) => handles.git(h).map(f).getOrElse(missingRepo(h))

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def statusName(s: HunkStatus): String = s match
    case HunkStatus.Added    => "ADDED"
    case HunkStatus.Modified => "MODIFIED"
    case HunkStatus.Deleted  => "DELETED"
    case HunkStatus.Renamed  => "RENAMED"

  // ── handlers ───────────────────────────────────────────────────────────

  /** Clone in full — never shallow, because blame and SZZ need complete history and a shallow
    * clone would truncate lineage silently rather than fail.
    */
  private def repoClone(handles: Handles): ToolHandler = handler("repo_clone") { a =>
    str(a, "url") match
      case None => HandlerResult.Undetermined(UnknownReason.NotApplicable, "url missing")
      case Some(url) =>
        val handle = handles.repoHandle(url)
        val dir    = handles.workspace.resolve("clones").resolve(handle)

        val existed = handles.git(handle).isDefined || Files.isDirectory(dir.resolve(".git"))

        val svc =
          if handles.git(handle).isDefined then Right(handles.git(handle).get)
          else if Files.isDirectory(dir.resolve(".git")) then GitService.open(dir)
          else
            Files.createDirectories(dir.getParent)
            GitService.cloneRepo(url, dir)

        svc match
          case Left(err) =>
            HandlerResult.Undetermined(UnknownReason.ToolUnavailable, err)
          case Right(g) =>
            handles.register(handle, g, dir, url)

            // `refresh` on a clone that did not exist is already satisfied by the clone itself.
            // A fetch FAILURE is reported and not fatal: a stale-but-usable clone still answers
            // every historical question, and the caller needs to know which one it got.
            val refreshed =
              if bool(a, "refresh").getOrElse(false) && existed then Some(g.fetch()) else None

            val d = Json.obj()
            d.put("repoHandle", handle)
            d.put("localPath", dir.toAbsolutePath.toString)
            d.put("defaultBranch", g.defaultBranch)
            g.headSha.foreach(d.put("headSha", _))
            d.put("commitCount", g.commitCount)
            refreshed.foreach {
              case Right(n)  => d.put("refreshed", true); d.put("refsUpdated", n)
              case Left(err) => d.put("refreshed", false); d.put("refreshError", err.take(300))
            }
            HandlerResult.Data(d)
  }

  private def repoDetectEcosystem(handles: Handles): ToolHandler =
    handler("repo_detect_ecosystem") { a =>
      withGit(handles, a) { g =>
        val dir = str(a, "repoHandle").flatMap(handles.dir)
        dir match
          case None => HandlerResult.Undetermined(UnknownReason.NotApplicable, "no clone directory")
          case Some(d) =>
            val candidates = Vector("build.sbt", "pom.xml", "build.gradle", "build.gradle.kts",
              "CMakeLists.txt", "BUILD.bazel", "Makefile")

            // At a REF when one is given, from the working tree otherwise. A repository can
            // change build system over its history — Maven in 2016, Gradle now — and reading
            // today's tree when asked about a revision reports today's ecosystem for a
            // decade-old commit.
            val markers = str(a, "ref") match
              case None => candidates.filter(m => Files.exists(d.resolve(m)))
              case Some(ref) =>
                g.resolve(ref).toOption match
                  case None      => Vector.empty
                  case Some(sha) => candidates.filter(m => g.fileAt(sha, m).isRight)

            val eco =
              if markers.exists(m => m.startsWith("build.") || m == "pom.xml") then "JVM"
              else if markers.nonEmpty then "LLVM"
              else "UNSUPPORTED"

            val out = Json.obj()
            out.put("ecosystem", eco)
            val ms = arr(out, "markerFiles")
            markers.foreach(ms.add)
            HandlerResult.Data(out)
      }
    }

  private def historyListCommits(handles: Handles): ToolHandler =
    handler("history_list_commits") { a =>
      withGit(handles, a) { g =>
        // An unparseable date must not silently become "no bound": asking for commits up to
        // 2020 and receiving all of history is a WRONG answer, not a missing filter.
        def instant(f: String) = str(a, f).map(v => (v, Try(Instant.parse(v)).toOption))
        val since = instant("since")
        val until = instant("until")

        val bad = Vector(since, until).flatten.collect { case (v, None) => v }

        val paths = Option(a.get("paths")).filter(_.isArray)
          .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty)

        if bad.nonEmpty then
          HandlerResult.Undetermined(UnknownReason.NotApplicable,
            s"not an ISO-8601 instant: ${bad.mkString(", ")}")
        else g.listCommits(str(a, "ref").getOrElse("HEAD"), since.flatMap(_._2), until.flatMap(_._2),
                      int(a, "maxCount").getOrElse(300), paths) match
          case Left(err) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
          case Right((commits, truncated)) =>
            val out = Json.obj()
            val list = arr(out, "commits")
            commits.foreach { c =>
              val n = Json.obj()
              n.put("sha", c.sha); n.put("author", c.author)
              n.put("authorDate", c.authorDate.toString)
              n.put("committerDate", c.commitDate.toString)
              n.put("subject", c.subject); n.put("isMerge", c.isMerge)
              val ps = arr(n, "parents"); c.parents.foreach(ps.add)
              list.add(n)
            }
            out.put("scanned", commits.size)
            out.put("truncated", truncated)
            HandlerResult.Data(out)
      }
    }

  private def historyCommitMeta(handles: Handles): ToolHandler =
    handler("history_commit_meta") { a =>
      withGit(handles, a) { g =>
        str(a, "sha").map(g.commitMeta) match
          case None => HandlerResult.Undetermined(UnknownReason.NotApplicable, "sha missing")
          case Some(Left(err)) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
          case Some(Right(c)) =>
            val n = Json.obj()
            n.put("sha", c.sha); n.put("message", c.message); n.put("author", c.author)
            n.put("committer", c.author)
            n.put("authorDate", c.authorDate.toString); n.put("commitDate", c.commitDate.toString)
            val ps = arr(n, "parents"); c.parents.foreach(ps.add)
            val rs = arr(n, "issueRefs"); c.issueRefs.foreach(rs.add)
            HandlerResult.Data(n)
      }
    }

  private def historyDiff(handles: Handles): ToolHandler = handler("history_diff") { a =>
    withGit(handles, a) { g =>
      str(a, "sha").map(s => g.diff(s, int(a, "parentIndex").getOrElse(0))) match
        case None => HandlerResult.Undetermined(UnknownReason.NotApplicable, "sha missing")
        case Some(Left(err)) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
        case Some(Right(d)) =>
          val out   = Json.obj()
          val files = arr(out, "files")

          d.allHunks.foreach { h =>
            val n = Json.obj()
            n.put("file", h.file)
            n.put("status", statusName(h.status))
            h.oldPath.foreach(n.put("oldPath", _))
            // Old and new ranges are tagged with the side they belong to, so a consumer cannot
            // query the parent revision with fix-side line numbers.
            h.oldRange.foreach { r =>
              val o = Json.obj(); o.put("side", "old"); o.put("start", r.start); o.put("end", r.end)
              n.set("oldRange", o)
            }
            h.newRange.foreach { r =>
              val o = Json.obj(); o.put("side", "new"); o.put("start", r.start); o.put("end", r.end)
              n.set("newRange", o)
            }
            val added = arr(n, "addedLines"); h.addedLines.foreach(added.add)
            val del   = arr(n, "deletedLines"); h.deletedLines.foreach(del.add)
            files.add(n)
          }

          val stats = Json.obj()
          stats.put("linesAdded", d.linesAdded)
          stats.put("linesDeleted", d.linesDeleted)
          stats.put("filesTouched", d.filesTouched)
          stats.put("hunkCount", d.hunkCount)
          out.set("stats", stats)
          out.put("truncated", false)
          HandlerResult.Data(out)
    }
  }

  private def historyFileAt(handles: Handles): ToolHandler = handler("history_file_at") { a =>
    withGit(handles, a) { g =>
      (str(a, "sha"), str(a, "path")) match
        case (Some(sha), Some(path)) =>
          g.fileAt(sha, path) match
            case Left(err) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
            case Right(content) =>
              val slice = (int(a, "startLine"), int(a, "endLine")) match
                case (Some(s), Some(e)) =>
                  content.linesIterator.slice(math.max(0, s - 1), e).mkString("\n")
                case _ => content
              val n = Json.obj()
              n.put("path", path); n.put("sha", sha); n.put("content", slice)
              n.put("truncated", slice.length < content.length)
              HandlerResult.Data(n)
        case _ => HandlerResult.Undetermined(UnknownReason.NotApplicable, "sha and path are required")
    }
  }

  private def historyBlame(handles: Handles): ToolHandler = handler("history_blame") { a =>
    withGit(handles, a) { g =>
      val range = Option(a.get("range")).map(r =>
        OldLines(r.get("start").intValue(), r.get("end").intValue()))

      (str(a, "sha"), str(a, "path"), range) match
        case (Some(sha), Some(path), Some(r)) =>
          g.blame(sha, path, r) match
            case Left(err) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
            case Right(lines) =>
              val out = Json.obj()
              val ls  = arr(out, "lines")
              lines.foreach { b =>
                val n = Json.obj()
                n.put("line", b.line); n.put("commit", b.commit)
                n.put("author", b.author); n.put("date", b.date.toString)
                ls.add(n)
              }
              HandlerResult.Data(out)
        case _ => HandlerResult.Undetermined(UnknownReason.NotApplicable, "sha, path and range are required")
    }
  }

  private def historyCandidates(handles: Handles): ToolHandler =
    handler("history_candidates") { a =>
      withGit(handles, a) { g =>
        val nets = Option(a.get("nets"))
          .filter(_.isArray)
          .map(_.values().iterator().asInstanceOf[java.util.Iterator[JsonNode]])
          .map { it =>
            var acc = Set.empty[CandidateNet]
            while it.hasNext do
              val v = it.next().stringValue()
              CandidateNet.values.find(_.toString.toUpperCase == v.replace("_", "").toUpperCase)
                .orElse(v match
                  case "LEXICAL"        => Some(CandidateNet.Lexical)
                  case "STRUCTURAL"     => Some(CandidateNet.Structural)
                  case "RANDOM_CONTROL" => Some(CandidateNet.RandomControl)
                  case _                => None)
                .foreach(n => acc = acc + n)
            acc
          }
          .getOrElse(CandidateNet.values.toSet)

        val since = str(a, "since").flatMap(s => Try(Instant.parse(s)).toOption)

        Candidates.find(g, str(a, "ref").getOrElse("HEAD"), since,
          int(a, "maxCount").getOrElse(300), nets,
          int(a, "controlSampleSize").getOrElse(50)) match
          case Left(err) => HandlerResult.Undetermined(UnknownReason.NotApplicable, err)
          case Right(res) =>
            val out  = Json.obj()
            val list = arr(out, "candidates")
            res.candidates.foreach { c =>
              val n = Json.obj()
              n.put("sha", c.sha)
              val ms = arr(n, "matchedNets")
              c.matchedNets.foreach(m => ms.add(netName(m)))
              val rs = arr(n, "issueRefs"); c.issueRefs.foreach(rs.add)
              n.put("touchesTests", c.touchesTests)
              n.put("touchesSource", c.touchesSource)
              list.add(n)
            }
            out.put("scanned", res.scanned)
            val counts = Json.obj()
            res.perNetCounts.foreach { case (net, n) => counts.put(netName(net), n) }
            out.set("perNetCounts", counts)
            out.put("truncated", res.truncated)
            HandlerResult.Data(out)
      }
    }

  private def netName(n: CandidateNet): String = n match
    case CandidateNet.Lexical       => "LEXICAL"
    case CandidateNet.Structural    => "STRUCTURAL"
    case CandidateNet.RandomControl => "RANDOM_CONTROL"
