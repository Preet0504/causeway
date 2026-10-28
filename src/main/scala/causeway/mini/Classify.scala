package causeway.mini

import java.time.Instant
import Json.*

final case class Signal(score: Double, explanation: String)

/** A bug-fix judgment for one commit, as three independent signals. `linked` is None without a PR/issue. */
final case class Scored(sha: String, message: Signal, diff: Signal, linked: Option[Signal])

final case class Rejection(sha: String, reason: String)

object Classify:
  /** A commit counts as a bug fix when its combined score reaches this. */
  val Threshold = 0.6
  val MessageWeight = 0.3
  val DiffWeight = 0.5
  val LinkedWeight = 0.2
  val MaxBatch = 5
  /** Diff text shown to the classifier per commit; the stored diff is longer. */
  val AgentDiffChars = 6000

  /** Weighted mean; with no linked signal the other two weights are rescaled to sum to 1. */
  def combined(s: Scored): Double = s.linked match
    case Some(l) => MessageWeight * s.message.score + DiffWeight * s.diff.score + LinkedWeight * l.score
    case None    => (MessageWeight * s.message.score + DiffWeight * s.diff.score) / (MessageWeight + DiffWeight)

  def isBugFix(s: Scored): Boolean = combined(s) >= Threshold

  /** Strips a ```json fence, which models add even when told not to. */
  private def unfence(text: String): String =
    val t = text.trim
    if t.startsWith("```") then t.linesIterator.drop(1).takeWhile(!_.trim.startsWith("```")).mkString("\n") else t

  private def signal(v: ujson.Value, name: String): Either[String, Signal] =
    (v.obj.get("score"), v.text("explanation").map(_.trim).filter(_.nonEmpty)) match
      case (Some(ujson.Num(s)), Some(e)) if s >= 0.0 && s <= 1.0 => Right(Signal(s, e))
      case (Some(ujson.Num(_)), Some(_)) => Left(s"$name.score must be between 0.0 and 1.0")
      case _ => Left(s"$name needs a numeric score and a non-empty explanation")

  /**
   * Checks the classifier's reply against the run's commits. Valid entries are returned; invalid ones come
   * back with a reason so the caller can re-ask for just those. `known` maps each full sha in the run to
   * whether it has linked PRs/issues.
   */
  def validate(replyText: String, known: Map[String, Boolean]): Either[String, (List[Scored], List[Rejection])] =
    val parsed = scala.util.Try(ujson.read(unfence(replyText))).toEither.left.map(e => s"reply is not valid JSON: ${e.getMessage}")
    parsed.flatMap { json =>
      val items = json match
        case ujson.Arr(a) => Right(a.toList)
        case o: ujson.Obj => o.obj.get("results").collect { case ujson.Arr(a) => a.toList }.toRight("expected a JSON array or {\"results\": [...]}")
        case _            => Left("expected a JSON array or {\"results\": [...]}")
      items.map { list =>
        val ok = List.newBuilder[Scored]
        val bad = List.newBuilder[Rejection]
        val seen = scala.collection.mutable.Set.empty[String]
        list.foreach { item =>
          val claimed = item.text("sha").getOrElse("")
          val matches = if claimed.length >= 7 then known.keys.filter(_.startsWith(claimed.toLowerCase)).toList else Nil
          matches match
            case List(sha) if !seen.contains(sha) =>
              val hasLinks = known(sha)
              val result = for
                m <- item.opt("message").toRight("missing 'message' signal").flatMap(signal(_, "message"))
                d <- item.opt("diff").toRight("missing 'diff' signal").flatMap(signal(_, "diff"))
                l <- (item.opt("linked"), hasLinks) match
                  case (Some(v), true)  => signal(v, "linked").map(Some(_))
                  case (None, true)     => Left("commit has linked PRs/issues, so 'linked' is required")
                  case (_, false)       => Right(None)
              yield Scored(sha, m, d, l)
              result match
                case Right(s)  => seen += sha; ok += s
                case Left(why) => bad += Rejection(sha, why)
            case List(_) => bad += Rejection(claimed, "sha appears more than once in the reply")
            case Nil     => bad += Rejection(claimed, "sha is not a commit of this run (need 7+ hex characters)")
            case _       => bad += Rejection(claimed, "sha prefix is ambiguous")
        }
        (ok.result(), bad.result())
      }
    }

  def batchJson(db: Db, runId: String, size: Int): ujson.Value =
    val batch = db.unclassifiedCommits(runId, math.min(size, MaxBatch))
    val remaining = db.commits(runId).size - db.query("SELECT COUNT(*) FROM classifications WHERE run_id = ?", runId)(_.getInt(1)).head
    ujson.Obj(
      "run" -> runId, "remaining" -> remaining,
      "commits" -> Json.arr(batch.map { c =>
        ujson.Obj(
          "sha" -> c.sha, "message" -> c.message, "files" -> Json.arr(c.files.map(ujson.Str(_))),
          "diff" -> c.diff.take(AgentDiffChars), "diff_truncated" -> (c.diffTruncated || c.diff.length > AgentDiffChars),
          "linked" -> Json.arr(db.links(runId, c.sha).map(l =>
            ujson.Obj("kind" -> l.kind, "number" -> l.number, "title" -> l.title, "body" -> l.body.take(1500),
              "state" -> l.state, "how" -> l.how))))
      }))

  def record(db: Db, runId: String, replyText: String, now: Instant): Either[String, ujson.Value] =
    db.run(runId)
    val known = db.commits(runId).map(c => c.sha -> db.links(runId, c.sha).nonEmpty).toMap
    validate(replyText, known).map { (scored, rejected) =>
      db.tx(scored.foreach(db.saveClassification(runId, _, now.toString)))
      ujson.Obj(
        "run" -> runId, "stored" -> scored.size,
        "bug_fixes_in_reply" -> scored.count(isBugFix),
        "rejected" -> Json.arr(rejected.map(r => ujson.Obj("sha" -> r.sha, "reason" -> r.reason))),
        "status" -> db.runSummary(runId))
    }
