package causeway.graphstore

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class NoteStoreSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private def store() = NoteStore(Files.createTempDirectory("causeway-notes"))

  test("a note round-trips"):
    val s = store()
    val (id, _) = s.write("jhy/jsoup", runId, "build-doctor", NoteKind.Environment,
      "jdk", "needs JDK 11; the maven wrapper is broken before 2021").toOption.get

    val (notes, truncated) = s.read("jhy/jsoup")
    truncated shouldBe false
    notes.head.noteId shouldBe id
    notes.head.kind shouldBe NoteKind.Environment
    notes.head.body should include("JDK 11")

  // The integrity property the whole note design rests on.
  test("a note id is note_*, never ev_* — so it cannot be cited as evidence"):
    val s = store()
    val (id, _) = s.write("r/x", runId, "build-doctor", NoteKind.Environment, "s", "b").toOption.get

    id.value should startWith("note_")
    // and it is not even parseable as an EvidenceId, so there is no accidental conversion
    EvidenceId(id.value).isLeft shouldBe true

  test("a note cannot back a Finding, because Finding accepts only EvidenceId"):
    val s = store()
    val (noteId, _) = s.write("r/x", runId, "path-tracer", NoteKind.DeadEnd, "s", "b").toOption.get
    val ledger = InMemoryEvidenceLedger()

    // The nearest an agent could get is trying to pass the note's string as an evidence id.
    EvidenceId(noteId.value) match
      case Right(_) => fail("a note id must never validate as an evidence id")
      case Left(_) =>
        // and a fabricated one is rejected by the ledger check anyway
        val forged = EvidenceId.unsafe("ev_deadbeefdeadbeef")
        Finding.make("claim", ClaimType.Path, "path-tracer", "subj", 0.5,
          Vector(forged), ledger) shouldBe Left(FindingRejection.UnknownEvidence(Vector(forged)))

  test("notes are per-repository and do not leak between them"):
    val s = store()
    s.write("a/one", runId, "x", NoteKind.Convention, "s", "note for one")
    s.write("b/two", runId, "x", NoteKind.Convention, "s", "note for two")

    s.read("a/one")._1.map(_.body) shouldBe Vector("note for one")
    s.read("b/two")._1.map(_.body) shouldBe Vector("note for two")

  test("reading an unknown repository yields nothing rather than failing"):
    store().read("never/seen")._1 shouldBe empty

  test("notes can be filtered by kind"):
    val s = store()
    s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "e")
    s.write("r/x", runId, "a", NoteKind.DeadEnd, "harness", "d")
    s.write("r/x", runId, "a", NoteKind.Fixture, "resources", "f")

    s.read("r/x", kinds = Set(NoteKind.DeadEnd))._1.map(_.kind) shouldBe Vector(NoteKind.DeadEnd)
    s.read("r/x", kinds = Set(NoteKind.DeadEnd, NoteKind.Fixture))._1.size shouldBe 2

  test("notes can be filtered by subject, case-insensitively"):
    val s = store()
    s.write("r/x", runId, "a", NoteKind.Environment, "JDK version", "e")
    s.write("r/x", runId, "a", NoteKind.Environment, "maven wrapper", "m")

    s.read("r/x", subjectContains = Some("jdk"))._1.size shouldBe 1

  // Two contradictory notes give the next agent no way to choose, so superseding hides the old.
  test("superseding hides the old note instead of leaving both"):
    val s = store()
    val (old, _) = s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "needs JDK 8").toOption.get
    val (_, count) = s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "actually needs JDK 11",
      supersedes = Some(old)).toOption.get

    count shouldBe 1
    val (live, _) = s.read("r/x")
    live.map(_.body) shouldBe Vector("actually needs JDK 11")

    val (all, _) = s.read("r/x", includeSuperseded = true)
    all.size shouldBe 2

  test("the subject index carries no bodies, so it is cheap to call first"):
    val s = store()
    s.write("r/x", runId, "a", NoteKind.DeadEnd, "harness altitude", "a very long body " * 50)
    s.write("r/x", runId, "a", NoteKind.DeadEnd, "harness altitude", "another long body " * 50)
    s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "short")

    val (idx, total) = s.listSubjects("r/x")
    total shouldBe 3
    idx.find(_.subject == "harness altitude").get.count shouldBe 2
    idx.map(_.subject).distinct.size shouldBe 2

  test("superseded notes are excluded from the index"):
    val s = store()
    val (old, _) = s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "8").toOption.get
    s.write("r/x", runId, "a", NoteKind.Environment, "jdk", "11", supersedes = Some(old))
    s.listSubjects("r/x")._2 shouldBe 1

  test("reads are newest-first and honour the limit, reporting truncation"):
    val s = store()
    (1 to 5).foreach(i => s.write("r/x", runId, "a", NoteKind.Convention, s"s$i", s"body $i"))
    val (notes, truncated) = s.read("r/x", limit = 2)
    notes.size shouldBe 2
    truncated shouldBe true

  test("the kind enum offers no way to record a claim about a bug"):
    // If a FACT or FINDING kind ever appears here, the laundering channel is open again.
    NoteKind.values.map(_.toString).toSet shouldBe
      Set("Environment", "Convention", "DeadEnd", "Fixture")
