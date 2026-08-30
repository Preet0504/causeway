package causeway.app

import causeway.core.*
import causeway.graphstore.NoteStore
import causeway.mcp.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.nio.file.{Files, Path, Paths}
import java.time.{Clock, Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*

/** The enforcement path, exercised the way an agent actually reaches it.
  *
  * Everything else in the system validates SHAPES. `findings_record` validates PROVENANCE, and
  * the note store exists precisely so that procedural knowledge cannot leak into it.
  */
class RecordHandlersSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  private def schemas: Path =
    Vector(Paths.get("schemas"), Paths.get("..", "..", "schemas"))
      .find(Files.isDirectory(_)).getOrElse(sys.error("schemas/ not found"))

  private def fixture() =
    val ledger  = InMemoryEvidenceLedger()
    val notes   = NoteStore(Files.createTempDirectory("causeway-rec-notes"))
    val handles = Handles(Files.createTempDirectory("causeway-rec-ws"))
    val specs   = SchemaCatalog.load(schemas).toOption.get
    val reg = ToolRegistry.build(
      specs,
      VcsHandlers.all(handles) ++ AnalysisHandlers.all(handles) ++
        RecordHandlers(ledger, notes, None).all,
      ledger, runId, clock
    ).toOption.get
    (reg, ledger)

  private def args(pairs: (String, Object)*): java.util.Map[String, Object] = pairs.toMap.asJava

  private def dataOf(r: io.modelcontextprotocol.spec.McpSchema.CallToolResult): JsonNode =
    val text = r.content().asScala.map(_.toString).mkString
    Json.parse(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))

  /** Mint a genuine evidence id by making a real tool call. */
  private def realEvidence(reg: ToolRegistry): String =
    dataOf(ToolBridge.call(reg, "history_commit_meta", args(
      "repoHandle" -> "repo_ffffffffffffffff",
      "sha" -> ("a" * 40),
      ToolBridge.AgentField -> "path-tracer"
    ))).get("evidenceId").stringValue()

  private def claim(evidence: List[String], agent: String = "bugfix-adjudicator") = args(
    "runId" -> runId.value,
    "agent" -> agent,
    "subject" -> ("a" * 40),
    "claimType" -> "VERDICT",
    "claim" -> Map[String, Object]("verdict" -> "BUG_FIX").asJava,
    "confidence" -> java.lang.Double.valueOf(0.9),
    "evidence" -> evidence.asJava,
    ToolBridge.AgentField -> agent
  )

  test("a claim citing evidence the ledger issued is accepted"):
    val (reg, _) = fixture()
    val ev = realEvidence(reg)

    val body = dataOf(ToolBridge.call(reg, "findings_record", claim(List(ev))))
    body.get("data").get("accepted").booleanValue() shouldBe true
    body.get("data").get("findingId").stringValue() should startWith("finding_")

  // The founding rule, at the boundary an agent actually crosses.
  test("a claim citing an id the ledger never issued is refused"):
    val (reg, _) = fixture()
    val forged = "ev_deadbeefdeadbeef"

    val data = dataOf(ToolBridge.call(reg, "findings_record", claim(List(forged)))).get("data")
    data.get("accepted").booleanValue() shouldBe false
    data.get("rejectedEvidence").values().asScala.map(_.stringValue()).toVector should contain(forged)
    data.get("rejectionReason").stringValue() should include("Retrieve the fact")

  // A rejection is an ANSWER, not an error. The agent asked "may I assert this?" and got "no,
  // and here is what is missing" — which invites retrieval. An error invites rephrasing.
  test("a rejection is a successful call, not a protocol error"):
    val (reg, _) = fixture()
    val result = ToolBridge.call(reg, "findings_record", claim(List("ev_deadbeefdeadbeef")))
    result.isError shouldBe false
    dataOf(result).get("ok").booleanValue() shouldBe true

  test("one forged id spoils an otherwise well-evidenced claim"):
    val (reg, _) = fixture()
    val real = realEvidence(reg)

    val data = dataOf(ToolBridge.call(reg, "findings_record",
      claim(List(real, "ev_deadbeefdeadbeef")))).get("data")
    data.get("accepted").booleanValue() shouldBe false

  test("a claim with no evidence at all is rejected by the schema, before the handler"):
    val (reg, _) = fixture()
    val result = ToolBridge.call(reg, "findings_record", claim(Nil))
    result.isError shouldBe true
    result.content().asScala.mkString should include("InvalidInput")

  // ── the laundering channel, closed end to end ──────────────────────────

  test("a note is written and read back through the tools"):
    val (reg, _) = fixture()
    val written = dataOf(ToolBridge.call(reg, "notes_write", args(
      "repoSlug" -> "jhy/jsoup", "runId" -> runId.value, "agent" -> "build-doctor",
      "kind" -> "ENVIRONMENT", "subject" -> "jdk", "body" -> "needs JDK 11",
      ToolBridge.AgentField -> "build-doctor"
    ))).get("data")

    written.get("noteId").stringValue() should startWith("note_")

    val read = dataOf(ToolBridge.call(reg, "notes_read", args(
      "repoSlug" -> "jhy/jsoup", ToolBridge.AgentField -> "reproducer-synthesist"
    ))).get("data")
    read.get("totalCount").intValue() shouldBe 1
    read.get("notes").get(0).get("body").stringValue() shouldBe "needs JDK 11"

  /** The whole point of keeping notes out of the ledger, demonstrated rather than asserted. */
  test("a note id cannot be laundered into a claim"):
    val (reg, _) = fixture()

    val noteId = dataOf(ToolBridge.call(reg, "notes_write", args(
      "repoSlug" -> "jhy/jsoup", "runId" -> runId.value, "agent" -> "path-tracer",
      "kind" -> "DEAD_END", "subject" -> "harness", "body" -> "the fault is in ConfigLoader",
      ToolBridge.AgentField -> "path-tracer"
    ))).get("data").get("noteId").stringValue()

    noteId should startWith("note_")

    // An agent now tries to cite its own note as support for a claim.
    val result = ToolBridge.call(reg, "findings_record", claim(List(noteId), "path-tracer"))

    // It never even reaches the handler: `evidence` items must match ^ev_ in the schema.
    result.isError shouldBe true
    result.content().asScala.mkString should include("InvalidInput")

  test("even a note-shaped id that passes the pattern is unknown to the ledger"):
    val (reg, _) = fixture()
    // Same 16 hex digits, ev_ prefix — structurally valid, but never issued.
    val data = dataOf(ToolBridge.call(reg, "findings_record",
      claim(List("ev_0123456789abcdef")))).get("data")
    data.get("accepted").booleanValue() shouldBe false

  test("the note index carries no bodies"):
    val (reg, _) = fixture()
    ToolBridge.call(reg, "notes_write", args(
      "repoSlug" -> "r/x", "runId" -> runId.value, "agent" -> "build-doctor",
      "kind" -> "FIXTURE", "subject" -> "resources", "body" -> "a very long body",
      ToolBridge.AgentField -> "build-doctor"))

    val idx = dataOf(ToolBridge.call(reg, "notes_list_subjects", args(
      "repoSlug" -> "r/x", ToolBridge.AgentField -> "build-doctor"))).get("data")

    idx.get("totalNotes").intValue() shouldBe 1
    val entry = idx.get("subjects").get(0)
    entry.get("subject").stringValue() shouldBe "resources"
    entry.has("body") shouldBe false

  test("superseding through the tool hides the stale note"):
    val (reg, _) = fixture()
    def write(body: String, supersedes: Option[String]) =
      val base = Map[String, Object](
        "repoSlug" -> "r/y", "runId" -> runId.value, "agent" -> "build-doctor",
        "kind" -> "ENVIRONMENT", "subject" -> "jdk", "body" -> body,
        ToolBridge.AgentField -> "build-doctor")
      dataOf(ToolBridge.call(reg, "notes_write",
        supersedes.fold(base)(s => base + ("supersedes" -> s)).asJava)).get("data")

    val first = write("needs JDK 8", None).get("noteId").stringValue()
    write("actually needs JDK 11", Some(first)).get("supersededCount").intValue() shouldBe 1

    val live = dataOf(ToolBridge.call(reg, "notes_read", args(
      "repoSlug" -> "r/y", ToolBridge.AgentField -> "build-doctor"))).get("data")
    live.get("totalCount").intValue() shouldBe 1
    live.get("notes").get(0).get("body").stringValue() shouldBe "actually needs JDK 11"

  // ── SZZ over the wire ──────────────────────────────────────────────────

  test("an unimplemented SZZ variant is refused rather than silently substituted"):
    val (reg, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "szz_baseline", args(
      "repoHandle" -> "repo_ffffffffffffffff", "fixSha" -> ("a" * 40), "variant" -> "RA-SZZ"
    )))
    // A result labelled RA-SZZ that was actually computed by R-SZZ would be worse than none.
    body.has("unknown") shouldBe true
    body.get("unknown").get("detail").stringValue() should include("not implemented")

  // A test COUNT is a bonus, not a requirement: an unrecognised build format must cost the
  // count, never produce an invented number.
  test("test counts are parsed from build output, or absent"):
    AnalysisHandlers.parseTestCounts(
      "Tests run: 120, Failures: 2, Errors: 1, Skipped: 3") shouldBe Some((120, 3, 3))

    // Maven prints per-module lines then a total; the last one is the total.
    AnalysisHandlers.parseTestCounts(
      "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0\nTests run: 40, Failures: 1, Errors: 0, Skipped: 2"
    ) shouldBe Some((40, 1, 2))

    AnalysisHandlers.parseTestCounts("312 tests completed, 4 failed") shouldBe Some((312, 4, 0))

    // Nothing recognisable — None, so the field is simply omitted.
    AnalysisHandlers.parseTestCounts("BUILD SUCCESS, no test summary here") shouldBe None

  test("scoverage is refused rather than silently answered with jacoco"):
    val (reg, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "jvm_coverage_run", args(
      "repoHandle" -> "repo_ffffffffffffffff", "commit" -> ("a" * 40),
      "buildHandle" -> "build_ffffffffffffffff", "engine" -> "scoverage"
    )))
    // Labelling a jacoco result as scoverage would be worse than no result.
    body.has("unknown") shouldBe true

  test("coverage without a successful build is a recorded gap, not a zero"):
    val (reg, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "jvm_coverage_run", args(
      "repoHandle" -> "repo_ffffffffffffffff", "commit" -> ("a" * 40),
      "buildHandle" -> "build_ffffffffffffffff", "engine" -> "jacoco"
    )))
    body.has("unknown") shouldBe true
    body.get("unknown").get("reason").stringValue() should (be("NotApplicable") or be("BuildFailed"))

  // ── export ─────────────────────────────────────────────────────────────

  test("an export with no store is a recorded gap, not an empty file"):
    val ledger  = InMemoryEvidenceLedger()
    val notes   = NoteStore(Files.createTempDirectory("causeway-exp-notes"))
    val handles = Handles(Files.createTempDirectory("causeway-exp-ws"))
    val specs   = SchemaCatalog.load(schemas).toOption.get
    val reg = ToolRegistry.build(
      specs,
      GraphHandlers(None, handles).all ++ RecordHandlers(ledger, notes, None).all,
      ledger, runId, clock
    ).toOption.get

    // No agent field: export_run is pipeline-only, and the registry rejects any agent that
    // claims it.
    val body = dataOf(ToolBridge.call(reg, "export_run", args("runId" -> runId.value)))
    // Writing an empty export would be indistinguishable, to whoever reads the file later, from
    // a run that genuinely found nothing.
    body.has("unknown") shouldBe true
    body.get("unknown").get("reason").stringValue() shouldBe "ToolUnavailable"

  // Pairing one commit's build handle with another commit's coordinates would return coverage
  // for a revision the caller never asked about, with nothing in the response to say so.
  test("coverage refuses a build handle that belongs to another revision"):
    val (reg, _) = fixture()
    val body = dataOf(ToolBridge.call(reg, "jvm_coverage_run", args(
      "repoHandle" -> "repo_ffffffffffffffff",
      "commit" -> ("a" * 40),
      "buildHandle" -> "build_ffffffffffffffff",
      "engine" -> "jacoco"
    )))
    body.has("unknown") shouldBe true
    // It stops at the BUILD handle now, not the repo handle: the build directory is what says
    // which revision was compiled, so that is what coverage must be measured against.
    body.get("unknown").get("detail").stringValue() should include("buildHandle")

  // ── run identity ───────────────────────────────────────────────────────

  /** A server process mints its own id at startup; the ORCHESTRATOR owns the mining run's id.
    *
    * Left unreconciled these are two namespaces, and a real jsoup run ended with its evidence
    * split across five server-process ids while its findings sat under one orchestrator id.
    * Nothing broke — findings_record validates by evidence ID, not by run — but anything asking
    * "what did this run retrieve" answered zero.
    */
  private def recordingFixture() =
    val ledger  = RecordingLedger()
    val notes   = NoteStore(Files.createTempDirectory("causeway-run-notes"))
    val handles = Handles(Files.createTempDirectory("causeway-run-ws"))
    val specs   = SchemaCatalog.load(schemas).toOption.get
    val reg = ToolRegistry.build(
      specs,
      VcsHandlers.all(handles) ++ AnalysisHandlers.all(handles) ++
        RecordHandlers(ledger, notes, None).all,
      ledger, RunId.unsafe("run_5e54e400000000ff"), clock
    ).toOption.get
    (reg, ledger)

  /** Remembers reattribution calls, which an in-memory ledger otherwise ignores. */
  private final class RecordingLedger extends EvidenceLedger:
    private val inner = InMemoryEvidenceLedger()
    var moves: Vector[(String, String)] = Vector.empty
    def issue(e: Evidence): Unit             = inner.issue(e)
    def contains(id: EvidenceId): Boolean    = inner.contains(id)
    def get(id: EvidenceId): Option[Evidence] = inner.get(id)
    def size: Int                            = inner.size
    override def reattribute(from: RunId, to: RunId): Int =
      moves = moves :+ (from.value, to.value)
      1

  test("the server adopts the mining run id the orchestrator supplies"):
    val (reg, ledger) = recordingFixture()

    // A call with no runId leaves the server on its startup id.
    ToolBridge.call(reg, "history_commit_meta", args(
      "repoHandle" -> "repo_ffffffffffffffff", "sha" -> ("a" * 40),
      ToolBridge.AgentField -> "path-tracer"))
    reg.runId.value shouldBe "run_5e54e400000000ff"
    ledger.moves shouldBe empty

    // The first call that names one wins, and the evidence issued before it is moved rather
    // than stranded under the process id.
    ToolBridge.call(reg, "notes_write", args(
      "repoSlug" -> "jhy/jsoup", "runId" -> runId.value, "agent" -> "build-doctor",
      "kind" -> "ENVIRONMENT", "subject" -> "jdk", "body" -> "needs JDK 17",
      ToolBridge.AgentField -> "build-doctor"))

    reg.runId shouldBe runId
    ledger.moves shouldBe Vector(("run_5e54e400000000ff", runId.value))

  test("a second, different run id does not steal the run"):
    val (reg, ledger) = recordingFixture()
    def note(run: String) = ToolBridge.call(reg, "notes_write", args(
      "repoSlug" -> "jhy/jsoup", "runId" -> run, "agent" -> "build-doctor",
      "kind" -> "ENVIRONMENT", "subject" -> "jdk", "body" -> "x",
      ToolBridge.AgentField -> "build-doctor"))

    note(runId.value)
    note("run_aaaaaaaaaaaaaaaa")

    // Following the second would scatter the evidence again, in the other direction.
    reg.runId shouldBe runId
    ledger.moves should have size 1

  test("an id from a call that fails validation is never adopted"):
    val (reg, ledger) = recordingFixture()

    // Missing required fields: this call is rejected before the handler runs.
    val result = ToolBridge.call(reg, "notes_write", args(
      "runId" -> "run_bbbbbbbbbbbbbbbb", ToolBridge.AgentField -> "build-doctor"))

    result.isError shouldBe true
    reg.runId.value shouldBe "run_5e54e400000000ff"
    ledger.moves shouldBe empty
