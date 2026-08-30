package causeway.app

import causeway.core.*
import causeway.jvm.CallGraphService
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import causeway.metrics.{Churn, Dimensions, Importance}
import causeway.vcs.Candidates
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Metrics and ranking. Pure computation over things already retrieved. */
object MetricsHandlers:

  def all(handles: Handles): Vector[ToolHandler] = Vector(
    churn(handles),
    bugLifetime(handles),
    coverageImpact(handles),
    complexityDelta(handles),
    rankImportance
  )

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def dbl(a: JsonNode, f: String): Option[Double] =
    Option(a.get(f)).filter(_.isNumber).map(_.doubleValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val x = Json.mapper.createArrayNode()
    node.set(field, x)
    x

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(d: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, d)

  private def methodRef(n: JsonNode): Option[MethodRef] =
    for
      node <- Option(n).filterNot(_.isNull)
      fqcn <- str(node, "fqcn"); name <- str(node, "name"); desc <- str(node, "descriptor")
    yield MethodRef(fqcn, name, desc)

  private def refNode(m: MethodRef): ObjectNode =
    val n = Json.obj()
    n.put("fqcn", m.fqcn); n.put("name", m.name); n.put("descriptor", m.descriptor)
    n

  private def churn(handles: Handles): ToolHandler = handler("metrics_churn") { a =>
    (str(a, "repoHandle").flatMap(handles.git), str(a, "fixSha")) match
      case (None, _) => na("no clone registered for that repoHandle")
      case (_, None) => na("fixSha is required")
      case (Some(git), Some(sha)) =>
        git.diff(sha) match
          case Left(err) => na(err)
          case Right(d) =>
            val m = Churn.of(d.allHunks, Candidates.isTestFile)
            val n = Json.obj()
            n.put("linesAdded", m.linesAdded)
            n.put("linesDeleted", m.linesDeleted)
            n.put("filesTouched", m.filesTouched)
            n.put("hunkCount", m.hunkCount)
            n.put("packagesTouched", m.packagesTouched)
            n.put("testLocRatio", m.testLocRatio)
            HandlerResult.Data(n)
  }

  /** Days from introduction to fix.
    *
    * Unknown when SZZ found no inducing commit — a guard-only fix deletes nothing, so blame has
    * nothing to work from. That is emphatically not a bug that lived for zero days.
    */
  private def bugLifetime(handles: Handles): ToolHandler = handler("metrics_bug_lifetime") { a =>
    (str(a, "repoHandle").flatMap(handles.git), str(a, "fixSha")) match
      case (None, _) => na("no clone registered for that repoHandle")
      case (_, None) => na("fixSha is required")
      case (Some(git), Some(fixSha)) =>
        git.commitMeta(fixSha) match
          case Left(err) => na(err)
          case Right(fix) =>
            str(a, "inducingSha") match
              case None => na("no inducing commit supplied — SZZ found no origin for this fix")
              case Some(indSha) =>
                git.commitMeta(indSha) match
                  case Left(err) => na(err)
                  case Right(ind) =>
                    val n = Json.obj()
                    n.put("introducedToFixDays",
                      java.time.Duration.between(ind.commitDate, fix.commitDate).toDays.toInt)
                    str(a, "reportedAt").flatMap(s => Try(Instant.parse(s)).toOption).foreach { r =>
                      n.put("introducedToReportDays",
                        java.time.Duration.between(ind.commitDate, r).toDays.toInt)
                      n.put("reportToFixDays",
                        java.time.Duration.between(r, fix.commitDate).toDays.toInt)
                    }
                    HandlerResult.Data(n)
  }

  /** D3's two coverage-impact metrics.
    *
    * The pre-fix side is queried with OLD-side line numbers against the PARENT's report. Using
    * new-side numbers here would read a different method's lines and produce a confident, wrong
    * answer.
    */
  private def coverageImpact(handles: Handles): ToolHandler =
    handler("metrics_coverage_impact") { a =>
      str(a, "preReportId").flatMap(handles.coverageReport) match
        case None => na("no pre-fix coverage report with that id")
        case Some(pre) =>
          val hunks = Option(a.get("hunks")).filter(_.isArray)
            .map(_.values().asScala.toVector).getOrElse(Vector.empty)

          if hunks.isEmpty then na("hunks are required")
          else
            val n = Json.obj()
            val perLine = arr(n, "preFixFaultyLineCoverage")
            var covered = 0
            var executable = 0

            hunks.foreach { h =>
              val file = Option(h.get("file")).map(_.stringValue()).getOrElse("")
              Option(h.get("oldRange")).filterNot(_.isNull).foreach { r =>
                val range = OldLines(r.get("start").intValue(), r.get("end").intValue())
                pre.linesFor(file, range).foreach { l =>
                  if l.status != CoverageStatus.NotExecutable then
                    executable += 1
                    if l.status == CoverageStatus.Covered then covered += 1
                    val o = Json.obj()
                    o.put("file", file); o.put("line", l.line)
                    o.put("status", if l.status == CoverageStatus.Covered then "COVERED" else "UNCOVERED")
                    perLine.add(o)
                }
              }
            }

            if executable == 0 then
              // No executable faulty lines is not 0% coverage — there is nothing to measure.
              na("no executable lines among the fix's old-side ranges in this report")
            else
              n.put("faultyLinesCoveredFraction", covered.toDouble / executable)
              str(a, "postReportId").flatMap(handles.coverageReport).foreach { post =>
                val files = hunks.flatMap(h => Option(h.get("file")).map(_.stringValue())).distinct
                val deltas = files.flatMap { f =>
                  for
                    (pc, pe) <- pre.fileSummary(f)
                    (qc, qe) <- post.fileSummary(f)
                    if pe > 0 && qe > 0
                  yield (qc.toDouble / qe) - (pc.toDouble / pe)
                }
                if deltas.nonEmpty then n.put("touchedFileCoverageDelta", deltas.sum / deltas.size)
              }
              HandlerResult.Data(n)
    }

  private def complexityDelta(handles: Handles): ToolHandler =
    handler("metrics_complexity_delta") { a =>
      val methods = Option(a.get("methods")).filter(_.isArray)
        .map(_.values().asScala.toVector.flatMap(methodRef)).getOrElse(Vector.empty)

      val before = str(a, "parentBuildHandle").map(handles.buildClasspath).getOrElse(Vector.empty)
      val after  = str(a, "fixBuildHandle").map(handles.buildClasspath).getOrElse(Vector.empty)

      if methods.isEmpty then na("methods are required")
      else if before.isEmpty || after.isEmpty then
        HandlerResult.Undetermined(UnknownReason.BuildFailed,
          "both revisions must have been built before complexity can be compared")
      else
        (CallGraphService.openView(before), CallGraphService.openView(after)) match
          case (Right(pv), Right(fv)) =>
            val n = Json.obj()
            val out = arr(n, "perMethod")
            methods.foreach { m =>
              val b = CallGraphService.methodShapeIn(pv, m).map(_._2).getOrElse(0)
              val f = CallGraphService.methodShapeIn(fv, m).map(_._2).getOrElse(0)
              val o = Json.obj()
              o.set("method", refNode(m))
              // Cyclomatic complexity is branches + 1 for the single entry path.
              o.put("before", b + 1); o.put("after", f + 1); o.put("delta", f - b)
              out.add(o)
            }
            HandlerResult.Data(n)
          case _ => HandlerResult.Undetermined(UnknownReason.BuildFailed, "cannot open one of the builds")
    }

  /** D19: score each dimension independently, composite over the KNOWN ones only.
    *
    * A bug with no characterised symptom must not be penalised for it — the dimension abstains
    * rather than voting zero, and `scoredOn` records which participated so a consumer never
    * silently compares a bug ranked on two dimensions with one ranked on three.
    */
  private def rankImportance: ToolHandler = handler("rank_importance") { a =>
    val ev = EvidenceId.unsafe("ev_0000000000000000")
    val bugs = Option(a.get("bugs")).filter(_.isArray).map(_.values().asScala.toVector)
      .getOrElse(Vector.empty)

    if bugs.isEmpty then na("bugs are required")
    else
      val dims = bugs.flatMap { b =>
        str(b, "fixSha").map { sha =>
          Dimensions(
            subject = sha,
            faultLineCoverage =
              dbl(b, "faultyLinesCoveredFraction").map(Signal.known(_, ev))
                .getOrElse(Signal.unknown(UnknownReason.BuildFailed, "not measured", ev)),
            blastRadius =
              int(b, "blastRadius").map(Signal.known(_, ev))
                .getOrElse(Signal.unknown(UnknownReason.NoDebugInfo, "not measured", ev)),
            symptomDistance =
              int(b, "symptomDistance").map(Signal.known(_, ev))
                .getOrElse(Signal.unknown(UnknownReason.NoSymptom, "no symptom characterised", ev))
          )
        }
      }

      val n = Json.obj()
      val out = arr(n, "ranked")
      Importance.rank(dims).foreach { r =>
        val o = Json.obj()
        o.put("fixSha", r.subject)
        o.put("composite", r.composite)
        val d = Json.obj()
        r.dimensions.foreach { case (k, v) => d.put(k, v) }
        o.set("dimensions", d)
        val s = arr(o, "scoredOn")
        r.scoredOn.foreach(s.add)
        o.put("rank", r.rank)
        out.add(o)
      }
      HandlerResult.Data(n)
  }
