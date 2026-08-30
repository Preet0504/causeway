package causeway.app

import causeway.core.*
import causeway.graphstore.{NoteStore, Neo4jStore}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The recording tools: `findings_record`, and the deliberately-separate note store.
  *
  * This is where the project's founding rule is actually enforced. Everything else validates
  * shapes; this validates PROVENANCE.
  */
final class RecordHandlers(
    ledger: EvidenceLedger,
    notes: NoteStore,
    store: Option[Neo4jStore]
):

  def all: Vector[ToolHandler] = Vector(
    findingsRecord,
    notesWrite,
    notesRead,
    notesListSubjects
  )

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def bool(a: JsonNode, f: String): Option[Boolean] =
    Option(a.get(f)).filter(_.isBoolean).map(_.booleanValue())

  private def strings(a: JsonNode, f: String): Vector[String] =
    Option(a.get(f)).filter(_.isArray).map(_.values().asScala.toVector.map(_.stringValue()))
      .getOrElse(Vector.empty)

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val x = Json.mapper.createArrayNode()
    node.set(field, x)
    x

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(detail: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, detail)

  // ── findings ───────────────────────────────────────────────────────────

  /** The only way a claim gets persisted, and the only place provenance is checked.
    *
    * A rejection is NOT an error response. The agent asked a legitimate question — "may I assert
    * this?" — and got a legitimate answer: no, and here is which ids the ledger never issued.
    * Returning `accepted: false` with the offending ids lets the agent go and retrieve the fact,
    * which is the intended behaviour; an error would invite it to rephrase instead.
    */
  private def findingsRecord: ToolHandler = handler("findings_record") { a =>
    val runIdStr = str(a, "runId")
    val agent    = str(a, "agent")
    val subject  = str(a, "subject")
    val claimTy  = str(a, "claimType")
    val claim    = Option(a.get("claim")).filterNot(_.isNull)
    val cited    = strings(a, "evidence")

    (runIdStr, agent, subject, claimTy, claim) match
      case (Some(rid), Some(ag), Some(subj), Some(ct), Some(c)) =>
        val parsed = cited.map(s => s -> EvidenceId(s))
        val malformed = parsed.collect { case (s, Left(_)) => s }
        val ids       = parsed.collect { case (_, Right(id)) => id }

        // A note id is the shape most likely to turn up here by mistake, and it must never
        // pass: notes influence what an agent DOES, evidence determines what it may SAY.
        val unknown = ids.filterNot(ledger.contains)

        if malformed.nonEmpty || unknown.nonEmpty then
          val n = Json.obj()
          n.put("accepted", false)
          n.put("persisted", false)
          val rejected = arr(n, "rejectedEvidence")
          (malformed ++ unknown.map(_.value)).foreach(rejected.add)
          n.put("rejectionReason",
            if malformed.nonEmpty then
              s"not evidence ids: ${malformed.mkString(", ")}. Notes and other identifiers cannot support a claim."
            else
              s"this run's ledger never issued: ${unknown.map(_.value).mkString(", ")}. " +
                "Retrieve the fact rather than rephrasing the claim.")
          HandlerResult.Data(n)
        else
          val confidence = Option(a.get("confidence")).filter(_.isNumber).map(_.doubleValue()).getOrElse(0.5)
          val gaps = Option(a.get("gaps")).filter(_.isArray).map(_.values().asScala.toVector.flatMap { g =>
            for
              reason <- Option(g.get("reason")).filter(_.isTextual).map(_.stringValue())
              parsedReason <- UnknownReason.values.find(_.toString == reason)
            yield (parsedReason, Option(g.get("detail")).filter(_.isTextual).map(_.stringValue()).getOrElse(""))
          }).getOrElse(Vector.empty)

          RunId(rid) match
            case Left(err) => na(err)
            case Right(runId) =>
              // Build through core's Finding, so the same non-empty-evidence and confidence
              // rules apply here as anywhere else in the system.
              val claimType = ClaimType.values.find(_.toString.equalsIgnoreCase(ct))
                .getOrElse(ClaimType.Strategy)

              Finding.make(Json.write(c), claimType, ag, subj, confidence, ids, ledger, gaps) match
                case Left(rejection) =>
                  val n = Json.obj()
                  n.put("accepted", false)
                  n.put("persisted", false)
                  n.put("rejectionReason", rejection.explain)
                  HandlerResult.Data(n)
                case Right(_) =>
                  val findingId =
                    f"finding_${(rid + ag + subj + ct).hashCode.toLong & 0xffffffffL}%016x"

                  // Accepted and PERSISTED are different facts. A run whose store is
                  // unreachable still validates provenance correctly in memory — and then
                  // loses every finding at exit. Reporting only `accepted` let 58 verdicts be
                  // confirmed and evaporate in a real run, with the warning on stderr where
                  // nobody saw it.
                  val persisted = store.exists { s =>
                    Try(s.putFinding(findingId, runId, ag, claimType.toString, subj, confidence,
                      Json.write(c), ids, gaps)).isSuccess
                  }

                  val n = Json.obj()
                  n.put("accepted", true)
                  n.put("persisted", persisted)
                  n.put("findingId", findingId)
                  if !persisted then
                    n.put("rejectionReason",
                      "accepted in memory only — no durable store is reachable, so this finding " +
                        "will be lost when the server exits")
                  HandlerResult.Data(n)

      case _ => na("runId, agent, subject, claimType and claim are all required")
  }

  // ── notes ──────────────────────────────────────────────────────────────

  private def parseKind(s: String): Option[NoteKind] =
    NoteKind.values.find(_.toString.equalsIgnoreCase(s.replace("_", "")))

  private def kindName(k: NoteKind): String = k match
    case NoteKind.Environment => "ENVIRONMENT"
    case NoteKind.Convention  => "CONVENTION"
    case NoteKind.DeadEnd     => "DEAD_END"
    case NoteKind.Fixture     => "FIXTURE"

  private def notesWrite: ToolHandler = handler("notes_write") { a =>
    (str(a, "repoSlug"), str(a, "runId"), str(a, "agent"), str(a, "kind"),
     str(a, "subject"), str(a, "body")) match
      case (Some(slug), Some(rid), Some(agent), Some(kindStr), Some(subject), Some(body)) =>
        (RunId(rid), parseKind(kindStr)) match
          case (Left(err), _) => na(err)
          case (_, None)      => na(s"unknown note kind '$kindStr'")
          case (Right(runId), Some(kind)) =>
            val supersedes = str(a, "supersedes").flatMap(s => NoteId(s).toOption)
            notes.write(slug, runId, agent, kind, subject, body, supersedes) match
              case Left(err) => HandlerResult.Undetermined(UnknownReason.ToolUnavailable, err)
              case Right((noteId, superseded)) =>
                val n = Json.obj()
                // note_*, never ev_*. findings_record requires ^ev_, so this is structurally
                // uncitable — the separation is a type, not a convention.
                n.put("noteId", noteId.value)
                n.put("supersededCount", superseded)
                HandlerResult.Data(n)
      case _ => na("repoSlug, runId, agent, kind, subject and body are all required")
  }

  private def notesRead: ToolHandler = handler("notes_read") { a =>
    str(a, "repoSlug") match
      case None => na("repoSlug is required")
      case Some(slug) =>
        val kinds = strings(a, "kinds").flatMap(parseKind).toSet
        val (found, truncated) = notes.read(
          slug, kinds, str(a, "subjectContains"),
          bool(a, "includeSuperseded").getOrElse(false),
          int(a, "limit").getOrElse(25)
        )
        val n = Json.obj()
        val list = arr(n, "notes")
        found.foreach { note =>
          val o = Json.obj()
          o.put("noteId", note.noteId.value)
          o.put("kind", kindName(note.kind))
          o.put("subject", note.subject)
          o.put("body", note.body)
          o.put("agent", note.agent)
          o.put("runId", note.runId.value)
          o.put("createdAt", note.createdAt.toString)
          o.put("superseded", note.superseded)
          list.add(o)
        }
        n.put("totalCount", found.size)
        n.put("truncated", truncated)
        HandlerResult.Data(n)
  }

  private def notesListSubjects: ToolHandler = handler("notes_list_subjects") { a =>
    str(a, "repoSlug") match
      case None => na("repoSlug is required")
      case Some(slug) =>
        val (subjects, total) = notes.listSubjects(slug)
        val n = Json.obj()
        val list = arr(n, "subjects")
        subjects.foreach { s =>
          val o = Json.obj()
          o.put("kind", kindName(s.kind))
          o.put("subject", s.subject)
          o.put("count", s.count)
          o.put("lastWrittenAt", s.lastWrittenAt.toString)
          list.add(o)
        }
        n.put("totalNotes", total)
        HandlerResult.Data(n)
  }
