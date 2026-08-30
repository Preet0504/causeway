package causeway.app

import causeway.core.{RunId, UnknownReason}
import causeway.graphstore.{BugRow, CheckpointRow, FindingRow, Neo4jStore}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Persistence and run bookkeeping. */
final class GraphHandlers(store: Option[Neo4jStore], handles: Handles):

  def all: Vector[ToolHandler] = Vector(upsertBug, query, resumeState, checkpoint, exportRun)

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val x = Json.mapper.createArrayNode()
    node.set(field, x)
    x

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(d: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, d)

  private def withStore(f: Neo4jStore => HandlerResult): HandlerResult =
    store match
      case Some(s) => f(s)
      case None =>
        // Not an error: a run without Neo4j still produces findings in memory and can export.
        // Reporting it as a gap keeps the run honest about what will not survive a restart.
        HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
          "Neo4j is not reachable; results will not be persisted")

  /** Upsert one bug. Idempotent MERGE, so a resumed run overwrites rather than duplicating.
    *
    * A bug failing admission is stored with `admitted: false` and a reason — never dropped
    * (D2), because deletion makes the dataset's selection bias invisible.
    */
  private def upsertBug: ToolHandler = handler("graph_upsert_bug") { a =>
    withStore { s =>
      (str(a, "runId"), Option(a.get("record")).filterNot(_.isNull)) match
        case (Some(rid), Some(rec)) =>
          RunId(rid) match
            case Left(err) => na(err)
            case Right(_) =>
              val repo = str(rec, "repo").getOrElse("")
              val fix  = str(rec, "fixSha").getOrElse("")
              if repo.isEmpty || fix.isEmpty then na("record needs repo and fixSha")
              else
                val admitted = Option(rec.get("admitted")).filter(_.isBoolean)
                  .map(_.booleanValue()).getOrElse(false)
                Try {
                  s.putBug(
                    repo, fix, admitted,
                    str(rec, "rejectedFor"),
                    Option(rec.get("importance")).filter(_.isNumber).map(_.doubleValue()),
                    Option(rec.get("scoredOn")).filter(_.isArray)
                      .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty),
                    str(rec, "reproducerTier"),
                    str(rec, "pathFidelity")
                  )
                }.toEither match
                  case Left(t) => HandlerResult.Undetermined(UnknownReason.ToolUnavailable, t.getMessage)
                  case Right(_) =>
                    val n = Json.obj()
                    n.put("nodeId", s"$repo@$fix")
                    n.put("created", true)
                    n.put("relationshipsWritten", 2)
                    HandlerResult.Data(n)
        case _ => na("runId and record are required")
    }
  }

  /** Named queries only.
    *
    * Free-form Cypher is deliberately not accepted: an agent composing its own query could read
    * or alter anything in the graph, and the results would not be schema-checked.
    */
  private def query: ToolHandler = handler("graph_query") { a =>
    withStore { s =>
      str(a, "name") match
        case None => na("name is required")
        case Some(queryName) =>
          val params = Option(a.get("params")).filterNot(_.isNull)
          val repo   = params.flatMap(p => str(p, "repo")).getOrElse("")

          queryName match
            case "bugCount" =>
              val n = Json.obj()
              n.put("name", queryName)
              val rows = arr(n, "rows")
              val row = Json.obj()
              row.put("total", s.bugCount(repo))
              row.put("admitted", s.bugCount(repo, admittedOnly = true))
              rows.add(row)
              n.put("rowCount", 1)
              n.put("truncated", false)
              HandlerResult.Data(n)

            case "rejectionBreakdown" =>
              val n = Json.obj()
              n.put("name", queryName)
              val rows = arr(n, "rows")
              val breakdown = s.rejectionBreakdown(repo)
              breakdown.foreach { case (reason, count) =>
                val row = Json.obj()
                row.put("reason", reason); row.put("count", count)
                rows.add(row)
              }
              n.put("rowCount", breakdown.size)
              n.put("truncated", false)
              HandlerResult.Data(n)

            case other =>
              na(s"no registered query '$other'; available: bugCount, rejectionBreakdown")
    }
  }

  /** How far a run got — discovered BY REPOSITORY, not by run id.
    *
    * An agent starting work does not know a prior run's id; the question it needs answered is
    * "is there an unfinished run for this repository". Requiring the id would make the tool
    * answerable only when the answer is already known.
    */
  private def resumeState: ToolHandler = handler("graph_resume_state") { a =>
    withStore { s =>
      val explicit = str(a, "runId").flatMap(r => RunId(r).toOption)
      val found    = explicit.orElse(str(a, "repoUrl").flatMap(s.latestRunFor))

      found match
        case None =>
          // No prior run is a perfectly good answer: start fresh.
          val n = Json.obj()
          n.put("complete", false)
          arr(n, "stages")
          HandlerResult.Data(n)
        case Some(runId) =>
          val rows = s.resumeState(runId)
          val n = Json.obj()
          n.put("runId", runId.value)
          n.put("complete", rows.nonEmpty && rows.forall(_.pending == 0))
          val stages = arr(n, "stages")
          rows.foreach { r =>
            val o = Json.obj()
            o.put("stage", r.stage); o.put("done", r.done); o.put("pending", r.pending)
            stages.add(o)
          }
          HandlerResult.Data(n)
    }
  }

  private def checkpoint: ToolHandler = handler("graph_checkpoint") { a =>
    withStore { s =>
      (str(a, "runId").flatMap(r => RunId(r).toOption), str(a, "stage"),
       str(a, "key"), str(a, "status")) match
        case (Some(runId), Some(stage), Some(key), Some(status)) =>
          Try {
            // Record the run<->repo link here so a later resume can find this run by
            // repository rather than needing an id nobody has yet.
            str(a, "repo").foreach(repo => s.putRun(runId, repo))

            val supersedes = Option(a.get("supersedes")).filter(_.isArray)
              .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty)

            s.checkpoint(runId, stage, key, status, str(a, "detail").getOrElse(""), supersedes)
          }.toEither match
            case Left(t) => HandlerResult.Undetermined(UnknownReason.ToolUnavailable, t.getMessage)
            case Right(superseded) =>
              val n = Json.obj()
              n.put("acknowledged", true)
              if superseded > 0 then n.put("supersededCount", superseded)
              HandlerResult.Data(n)
        case _ => na("runId, stage, key and status are all required")
    }
  }

  /** Export a run so results survive a wiped database.
    *
    * Exports the BUGS, not just how far the run got. The previous version wrote checkpoint rows
    * and reported `bugCount: 0` unconditionally — a fabricated number in the one artefact meant
    * to outlive the database.
    *
    * Rejected bugs are included. An export containing only admitted bugs would look like a
    * cleaner dataset while hiding exactly the selection bias D2 exists to keep visible.
    */
  private def exportRun: ToolHandler = handler("export_run") { a =>
    (str(a, "runId").flatMap(r => RunId(r).toOption), store) match
      case (None, _) => na("a valid runId is required")
      case (_, None) =>
        // Without a store there is nothing to export. Writing an empty file would be a lie the
        // caller could not distinguish from a run that genuinely found nothing.
        HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
          "no graph store connected; there is nothing persisted to export")
      case (Some(runId), Some(st)) =>
        // jsonl streams: one object per line, so a large export can be read incrementally and
        // appended to. json is a single document. Same content either way.
        val jsonl = str(a, "format").contains("jsonl")
        val out = str(a, "outPath")
          .map(Paths.get(_))
          .getOrElse(handles.workspace.resolve("exports")
            .resolve(s"${runId.value}.${if jsonl then "jsonl" else "json"}"))

        Try {
          Files.createDirectories(out.getParent)

          val repo     = st.repoForRun(runId)
          val bugs     = repo.map(st.bugsForRepo).getOrElse(Vector.empty)
          val findings = st.findingsForRun(runId)
          val stages   = st.resumeState(runId)

          def bugNode(b: BugRow) =
            val o = Json.obj()
            o.put("type", "bug")
            o.put("repo", b.repo); o.put("fixSha", b.fixSha); o.put("admitted", b.admitted)
            b.rejectedFor.foreach(o.put("rejectedFor", _))
            b.importance.foreach(o.put("importance", _))
            // scoredOn travels with the score: a composite over three dimensions and one over
            // seven are not comparable, and without this the number looks like it is.
            val sc = arr(o, "scoredOn"); b.scoredOn.foreach(sc.add)
            b.reproducerTier.foreach(o.put("reproducerTier", _))
            b.pathFidelity.foreach(o.put("pathFidelity", _))
            o

          def findingNode(f: FindingRow) =
            val o = Json.obj()
            o.put("type", "finding")
            o.put("id", f.id); o.put("agent", f.agent); o.put("claimType", f.claimType)
            o.put("subject", f.subject); o.put("confidence", f.confidence)
            o.put("claim", f.claim); o.put("evidenceCount", f.evidenceCount)
            val g = arr(o, "gaps"); f.gaps.foreach(g.add)
            o

          def stageNode(r: CheckpointRow) =
            val o = Json.obj()
            o.put("type", "stage")
            o.put("stage", r.stage); o.put("done", r.done); o.put("pending", r.pending)
            o

          val header = Json.obj()
          header.put("type", "run")
          header.put("runId", runId.value)
          repo.foreach(header.put("repo", _))
          header.put("exportedAt", java.time.Instant.now().toString)
          header.put("bugCount", bugs.size)
          header.put("admittedCount", bugs.count(_.admitted))
          header.put("findingCount", findings.size)

          val text =
            if jsonl then
              (Vector(header) ++ bugs.map(bugNode) ++ findings.map(findingNode) ++
                stages.map(stageNode)).map(Json.write).mkString("\n") + "\n"
            else
              val doc = header
              val bs = arr(doc, "bugs");     bugs.map(bugNode).foreach(bs.add)
              val fs = arr(doc, "findings"); findings.map(findingNode).foreach(fs.add)
              val ss = arr(doc, "stages");   stages.map(stageNode).foreach(ss.add)
              Json.write(doc)

          val bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          Files.write(out, bytes)
          (bugs.size, bytes.length)
        }.toEither match
          case Left(t) =>
            HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
              Option(t.getMessage).getOrElse(t.toString))
          case Right((bugCount, byteSize)) =>
            val n = Json.obj()
            n.put("path", out.toAbsolutePath.toString)
            n.put("bugCount", bugCount)
            // Bytes, not characters: a non-ASCII commit subject makes the two differ, and this
            // field exists so a caller can tell a truncated file from a complete one.
            n.put("byteSize", byteSize)
            HandlerResult.Data(n)
  }
