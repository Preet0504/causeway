package causeway.app

import causeway.core.{RunId, UnknownReason}
import causeway.graphstore.{BugRow, CheckpointRow, Csv, DatasetAssembler, FindingRow, FindingWithEvidence, Neo4jStore}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Persistence and run bookkeeping. */
final class GraphHandlers(store: Option[Neo4jStore], handles: Handles):

  def all: Vector[ToolHandler] =
    Vector(upsertBug, query, resumeState, checkpoint, exportRun, exportDataset,
           bugList, bugDetail, listRuns)

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
    *
    * Every field is OPTIONAL, and a field the record omits leaves the graph's existing value
    * untouched (`Neo4jStore.putBug` coalesces) rather than resetting it to absent. This is what
    * lets the phase commands call this repeatedly across separate invocations — S4-S5 writing
    * verdict and fault location, S6 writing admission, S8 writing importance — without one call
    * clobbering what an earlier one wrote. `admitted` in particular is read as present-or-absent,
    * not defaulted to `false`: a record that never mentions admission is silent on it, not a
    * rejection.
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
                def ranges(field: String): Vector[causeway.graphstore.RangeRow] =
                  Option(rec.get(field)).filter(_.isArray).map(_.values().asScala.toVector).getOrElse(Vector.empty)
                    .flatMap { n =>
                      for
                        file  <- str(n, "file")
                        start <- Option(n.get("start")).filter(_.isNumber).map(_.intValue())
                        end   <- Option(n.get("end")).filter(_.isNumber).map(_.intValue())
                      yield causeway.graphstore.RangeRow(file, start, end)
                    }
                Try {
                  s.putBug(
                    repo = repo, fixSha = fix,
                    admitted = Option(rec.get("admitted")).filter(_.isBoolean).map(_.booleanValue()),
                    rejectedFor = str(rec, "rejectedFor"),
                    importance = Option(rec.get("importance")).filter(_.isNumber).map(_.doubleValue()),
                    scoredOn = Option(rec.get("scoredOn")).filter(_.isArray)
                      .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty),
                    reproducerTier = str(rec, "reproducerTier"),
                    pathFidelity = str(rec, "pathFidelity"),
                    parentSha = str(rec, "parentSha"),
                    oldRanges = ranges("oldRanges"),
                    newRanges = ranges("newRanges")
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

  /** The deliverable: five files a researcher can open with no prior context on this project.
    *
    * `graph_upsert_bug` reads eight scalar fields off an untyped `record` and drops everything
    * else without complaint — a note from a real run caught this. `export_run`'s own output
    * schema never described what was inside the file it wrote, only the wrapper around it.
    * Neither is "the dataset" in any sense a researcher could rely on; this is, and it is built
    * by querying the graph directly rather than trusting whatever an agent happened to pass
    * through a prior write.
    *
    * Findings are joined through `Bug.fixSha`, not filtered by this one runId — see
    * `Neo4jStore.bugFindings` for why a strict runId filter would silently omit real findings
    * recorded under an earlier, un-adopted server-process id for the same mining effort.
    */
  private def exportDataset: ToolHandler = handler("export_dataset") { a =>
    (str(a, "runId").flatMap(r => RunId(r).toOption), store) match
      case (None, _) => na("a valid runId is required")
      case (_, None) =>
        HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
          "no graph store connected; there is nothing persisted to export")
      case (Some(runId), Some(st)) =>
        Try {
          val repo = st.repoForRun(runId).getOrElse("")
          if repo.isEmpty then
            throw new RuntimeException(s"no repository is associated with run '${runId.value}'")

          val bugs        = st.bugsForRepo(repo)
          val bugFindings = st.bugFindings(repo)
          val runFindings = st.runLevelFindings(repo)

          val records = DatasetAssembler.bugRecords(runId.value, bugs, bugFindings)
          val hops    = DatasetAssembler.pathHops(runId.value, bugFindings)
          val allFindings = bugFindings ++ runFindings

          val evidenceIds = allFindings.flatMap(_.evidenceIds).distinct
          val evidenceRows = st.evidenceByIds(evidenceIds)

          val outDir = handles.workspace.resolve("exports").resolve(runId.value).resolve("dataset")
          Files.createDirectories(outDir)

          def writeFile(name: String, content: String): ObjectNode =
            val bytes = content.getBytes(StandardCharsets.UTF_8)
            Files.write(outDir.resolve(name), bytes)
            val o = Json.obj()
            o.put("name", name); o.put("byteSize", bytes.length)
            o

          val runMetadata =
            buildRunMetadata(runId.value, repo, records.size, records.count(_.admitted), runFindings)

          val files = Vector(
            "bugs.csv" -> Csv.file(DatasetAssembler.BugColumns, records.map(DatasetAssembler.bugRow)),
            "path_hops.csv" -> Csv.file(DatasetAssembler.PathHopColumns, hops.map(DatasetAssembler.pathHopRow)),
            "findings.csv" -> Csv.file(DatasetAssembler.FindingColumns, allFindings.map(DatasetAssembler.findingRow)),
            "evidence.csv" -> Csv.file(DatasetAssembler.EvidenceColumns, evidenceRows.map(DatasetAssembler.evidenceRow)),
            "run_metadata.json" -> Json.write(runMetadata),
            "CODEBOOK.md" -> DatasetAssembler.codebook(runId.value, java.time.Instant.now().toString)
          ).map(writeFile)

          (outDir, files, records.size, records.count(_.admitted))
        }.toEither match
          case Left(t) =>
            HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
              Option(t.getMessage).getOrElse(t.toString))
          case Right((outDir, files, bugCount, admittedCount)) =>
            val n = Json.obj()
            n.put("datasetDir", outDir.toAbsolutePath.toString)
            val fs = arr(n, "files")
            files.foreach(fs.add)
            n.put("bugCount", bugCount)
            n.put("admittedCount", admittedCount)
            HandlerResult.Data(n)
  }

  /** `BuildRecipe` and `Strategy` describe the run itself, not any one bug, so they live here
    * rather than as columns repeated identically on every row of `bugs.csv` — a value that never
    * varies by row is a sign of the wrong grain, not a sign the schema needs a confidence rule.
    */
  private def buildRunMetadata(
      runId: String, repo: String, bugCount: Int, admittedCount: Int,
      runFindings: Vector[FindingWithEvidence]
  ): ObjectNode =
    val meta = Json.obj()
    meta.put("schemaVersion", DatasetAssembler.SchemaVersion)
    meta.put("runId", runId)
    meta.put("repo", repo)
    meta.put("exportedAt", java.time.Instant.now().toString)
    meta.put("bugCount", bugCount)
    meta.put("admittedCount", admittedCount)

    def findingNode(f: FindingWithEvidence): ObjectNode =
      val o = Json.obj()
      o.put("findingId", f.findingId); o.put("agent", f.agent); o.put("confidence", f.confidence)
      o.set("claim", causeway.graphstore.ClaimField.parse(f.claimJson))
      val ev = arr(o, "evidenceIds"); f.evidenceIds.foreach(ev.add)
      o

    runFindings.find(_.claimType == "BuildRecipe").foreach(f => meta.set("buildRecipe", findingNode(f)))
    val strategies = arr(meta, "strategy")
    runFindings.filter(_.claimType == "Strategy").foreach(f => strategies.add(findingNode(f)))
    meta

  /** Resolve repo, bugs and per-bug findings once, the way every read-side handler below needs
    * them. Shared rather than repeated, so `/inspect-bugs`, `/inspect-bug` and `export_dataset`
    * can never quietly drift into disagreeing about the same bug.
    */
  private def loadRepo(st: Neo4jStore, runId: RunId): Option[(String, Vector[BugRow], Vector[causeway.graphstore.FindingWithEvidence])] =
    st.repoForRun(runId).map { repo =>
      (repo, st.bugsForRepo(repo), st.bugFindings(repo))
    }

  private def compactBugNode(r: causeway.graphstore.BugRecord): ObjectNode =
    val o = Json.obj()
    o.put("fixSha", r.fixSha); o.put("admitted", r.admitted)
    r.admittedReason.foreach(o.put("admittedReason", _))
    r.verdict.foreach(o.put("verdict", _))
    r.symptomClass.foreach(o.put("symptomClass", _))
    r.reproducerTier.foreach(o.put("reproducerTier", _))
    r.pathTier.foreach(o.put("pathTier", _))
    r.pathFidelity.foreach(o.put("pathFidelity", _))
    r.importance.foreach(o.put("importance", _))
    o

  /** Every bug for a repository, compact — the list `/inspect-bugs` and `/list-runs` render. */
  private def bugList: ToolHandler = handler("graph_bug_list") { a =>
    withStore { s =>
      str(a, "runId").flatMap(r => RunId(r).toOption) match
        case None => na("a valid runId is required")
        case Some(runId) =>
          loadRepo(s, runId) match
            case None => na(s"no repository is associated with run '${runId.value}'")
            case Some((repo, bugs, findings)) =>
              val records = DatasetAssembler.bugRecords(runId.value, bugs, findings)
              val n = Json.obj()
              n.put("repo", repo)
              val bs = arr(n, "bugs")
              records.foreach(r => bs.add(compactBugNode(r)))
              n.put("count", records.size)
              HandlerResult.Data(n)
    }
  }

  /** Everything known about one bug: the full curated record plus its ordered path hops.
    * `NotApplicable` when the fixSha names no bug at all, distinct from a bug that exists but
    * has not reached later stages — the same distinction `Signal[A]` makes everywhere else.
    */
  private def bugDetail: ToolHandler = handler("graph_bug_detail") { a =>
    withStore { s =>
      (str(a, "runId").flatMap(r => RunId(r).toOption), str(a, "fixSha")) match
        case (None, _) => na("a valid runId is required")
        case (_, None) => na("fixSha is required")
        case (Some(runId), Some(fixSha)) =>
          loadRepo(s, runId) match
            case None => na(s"no repository is associated with run '${runId.value}'")
            case Some((repo, bugs, findings)) =>
              bugs.find(_.fixSha == fixSha) match
                case None => na(s"no bug '$fixSha' recorded for $repo")
                case Some(bug) =>
                  val record = DatasetAssembler.bugRecords(runId.value, Vector(bug), findings).head
                  val hops = DatasetAssembler.pathHops(runId.value, findings.filter(_.subject == fixSha))

                  val n = Json.obj()
                  n.put("repo", repo); n.put("fixSha", record.fixSha); n.put("admitted", record.admitted)
                  record.admittedReason.foreach(n.put("admittedReason", _))
                  record.parentSha.foreach(n.put("parentSha", _))
                  val or = arr(n, "oldRanges"); record.oldRanges.foreach(rg => or.add(rg.toString))
                  val nr = arr(n, "newRanges"); record.newRanges.foreach(rg => nr.add(rg.toString))
                  record.verdict.foreach(n.put("verdict", _))
                  record.verdictConfidence.foreach(n.put("verdictConfidence", _))
                  record.verdictRationale.foreach(n.put("verdictRationale", _))
                  record.symptomClass.foreach(n.put("symptomClass", _))
                  record.symptomDescription.foreach(n.put("symptomDescription", _))
                  record.symptomEntryPoint.foreach(n.put("symptomEntryPoint", _))
                  val sg = arr(n, "symptomGaps"); record.symptomGaps.foreach(sg.add)
                  record.reproducerTier.foreach(n.put("reproducerTier", _))
                  record.reproducerTestSelector.foreach(n.put("reproducerTestSelector", _))
                  val rd = arr(n, "reproducerDiffersOn"); record.reproducerDiffersOn.foreach(rd.add)
                  record.pathTier.foreach(n.put("pathTier", _))
                  record.pathFidelity.foreach(n.put("pathFidelity", _))
                  record.pathObservedSteps.foreach(n.put("pathObservedSteps", _))
                  record.pathHopCount.foreach(n.put("pathHopCount", _))

                  val hopsArr = arr(n, "hops")
                  hops.foreach { h =>
                    val ho = Json.obj()
                    ho.put("hopN", h.hopN)
                    h.fromMethod.foreach(ho.put("fromMethod", _))
                    h.toMethod.foreach(ho.put("toMethod", _))
                    h.relation.foreach(ho.put("relation", _))
                    h.carrier.foreach(ho.put("carrier", _))
                    h.oldLines.foreach(ho.put("oldLines", _))
                    h.executed.foreach(ho.put("executed", _))
                    hopsArr.add(ho)
                  }

                  n.put("findingCount", record.findingCount)
                  n.put("evidenceCount", record.evidenceCount)
                  HandlerResult.Data(n)
    }
  }

  /** Every run this server has ever seen. The only handler in this file that does not need a
    * runId, because its whole purpose is answering "what have I mined" for someone who does not
    * have one yet.
    */
  private def listRuns: ToolHandler = handler("graph_list_runs") { a =>
    withStore { s =>
      val runs = s.allRuns()
      val n = Json.obj()
      val rs = arr(n, "runs")
      runs.foreach { run =>
        val stages = RunId(run.runId).toOption.map(s.resumeState).getOrElse(Vector.empty)
        val o = Json.obj()
        o.put("runId", run.runId); o.put("repo", run.repo); o.put("lastSeen", run.lastSeen)
        o.put("complete", stages.nonEmpty && stages.forall(_.pending == 0))
        o.put("bugCount", s.bugCount(run.repo))
        o.put("admittedCount", s.bugCount(run.repo, admittedOnly = true))
        rs.add(o)
      }
      n.put("count", runs.size)
      HandlerResult.Data(n)
    }
  }
