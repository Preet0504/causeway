package causeway.graphstore

import causeway.core.*
import tools.jackson.databind.json.JsonMapper

import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class Note(
    noteId: NoteId,
    kind: NoteKind,
    subject: String,
    body: String,
    agent: String,
    runId: RunId,
    createdAt: Instant,
    superseded: Boolean
)

final case class SubjectIndexEntry(kind: NoteKind, subject: String, count: Int, lastWrittenAt: Instant)

/** Per-repository knowledge that is expensive to re-derive, surviving across runs.
  *
  * Deliberately NOT in Neo4j alongside the evidence graph, and deliberately not producing
  * `EvidenceId`s. Notes influence what an agent DOES; evidence determines what an agent may SAY.
  * If a note could be cited, a claim would trace back to an earlier agent's belief rather than
  * to a tool return — which is exactly the laundering the ledger exists to prevent.
  *
  * The separation is enforced by the type system rather than by discipline: this store mints
  * [[NoteId]] (`note_*`), and `Finding` accepts only [[EvidenceId]] (`ev_*`). There is no
  * conversion between them.
  */
final class NoteStore(root: Path):

  private val mapper = JsonMapper()

  private def slugDir(repoSlug: String): Path =
    root.resolve(repoSlug.replace("/", "__"))

  private def file(repoSlug: String): Path =
    slugDir(repoSlug).resolve("notes.jsonl")

  private def mintId(repoSlug: String, kind: NoteKind, subject: String, body: String): NoteId =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(s"$repoSlug|$kind|$subject|$body|${Instant.now().toEpochMilli}".getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    NoteId.unsafe(s"note_${digest.take(16)}")

  def write(
      repoSlug: String,
      runId: RunId,
      agent: String,
      kind: NoteKind,
      subject: String,
      body: String,
      supersedes: Option[NoteId] = None
  ): Either[String, (NoteId, Int)] =
    Try {
      Files.createDirectories(slugDir(repoSlug))

      // Superseding rewrites the file rather than appending a tombstone: an agent reading two
      // contradictory notes has no way to tell which is current, and will pick badly.
      val supersededCount = supersedes match
        case None => 0
        case Some(old) =>
          val existing = readAll(repoSlug)
          val updated  = existing.map(n => if n.noteId == old then n.copy(superseded = true) else n)
          rewrite(repoSlug, updated)
          existing.count(_.noteId == old)

      val id = mintId(repoSlug, kind, subject, body)
      val n  = Note(id, kind, subject, body, agent, runId, Instant.now(), superseded = false)
      Files.writeString(
        file(repoSlug),
        toJson(n) + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      (id, supersededCount)
    }.toEither.left.map(t => s"cannot write note: ${t.getMessage}")

  def read(
      repoSlug: String,
      kinds: Set[NoteKind] = Set.empty,
      subjectContains: Option[String] = None,
      includeSuperseded: Boolean = false,
      limit: Int = 25
  ): (Vector[Note], Boolean) =
    val all = readAll(repoSlug)
      .filter(n => includeSuperseded || !n.superseded)
      .filter(n => kinds.isEmpty || kinds.contains(n.kind))
      .filter(n => subjectContains.forall(s => n.subject.toLowerCase.contains(s.toLowerCase)))
      .sortBy(_.createdAt)
      .reverse
    (all.take(limit), all.size > limit)

  /** Kinds and subjects only, no bodies.
    *
    * Called before `read` so a large note store does not flood agent context with history that
    * turns out to be irrelevant.
    */
  def listSubjects(repoSlug: String): (Vector[SubjectIndexEntry], Int) =
    val live = readAll(repoSlug).filterNot(_.superseded)
    val idx = live
      .groupBy(n => (n.kind, n.subject))
      .toVector
      .map { case ((k, s), ns) => SubjectIndexEntry(k, s, ns.size, ns.map(_.createdAt).max) }
      .sortBy(e => (e.kind.toString, e.subject))
    (idx, live.size)

  private def readAll(repoSlug: String): Vector[Note] =
    val f = file(repoSlug)
    if !Files.isRegularFile(f) then Vector.empty
    else
      Files
        .readAllLines(f).asScala.toVector
        .filter(_.trim.nonEmpty)
        .flatMap(l => fromJson(l).toOption)

  private def rewrite(repoSlug: String, notes: Vector[Note]): Unit =
    Files.writeString(file(repoSlug), notes.map(toJson).mkString("", "\n", "\n"))

  private def toJson(n: Note): String =
    val o = mapper.createObjectNode()
    o.put("noteId", n.noteId.value)
    o.put("kind", n.kind.toString)
    o.put("subject", n.subject)
    o.put("body", n.body)
    o.put("agent", n.agent)
    o.put("runId", n.runId.value)
    o.put("createdAt", n.createdAt.toString)
    o.put("superseded", n.superseded)
    mapper.writeValueAsString(o)

  private def fromJson(line: String): Try[Note] = Try {
    val n = mapper.readTree(line)
    Note(
      noteId = NoteId.unsafe(n.get("noteId").stringValue()),
      kind = NoteKind.valueOf(n.get("kind").stringValue()),
      subject = n.get("subject").stringValue(),
      body = n.get("body").stringValue(),
      agent = n.get("agent").stringValue(),
      runId = RunId.unsafe(n.get("runId").stringValue()),
      createdAt = Instant.parse(n.get("createdAt").stringValue()),
      superseded = n.get("superseded").booleanValue()
    )
  }
