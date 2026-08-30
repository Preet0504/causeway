package causeway.mcp

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.time.{Clock, Instant, ZoneOffset}

class ToolRegistrySpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

  private val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get

  /** A stand-in for history_diff that returns a schema-valid payload. */
  private def diffHandler(): ToolHandler = new ToolHandler:
    val name = "history_diff"
    def handle(args: JsonNode): HandlerResult =
      val data = Json.obj()
      data.set("files", Json.mapper.createArrayNode())
      val stats = Json.obj()
      stats.put("linesAdded", 4)
      stats.put("linesDeleted", 1)
      stats.put("filesTouched", 1)
      stats.put("hunkCount", 1)
      data.set("stats", stats)
      data.put("truncated", false)
      HandlerResult.Data(data)

  private def validDiffArgs: JsonNode =
    val a = Json.obj()
    a.put("repoHandle", "repo_0123456789abcdef")
    a.put("sha", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678")
    a

  private def build(handlers: Vector[ToolHandler]) =
    val ledger = InMemoryEvidenceLedger()
    ToolRegistry.build(specs, handlers, ledger, runId, clock).map(r => (r, ledger))

  // The schema-before-invocation rule, as a startup assertion.
  test("a handler with no schema is refused at construction"):
    val rogue = new ToolHandler:
      val name = "totally_made_up_tool"
      def handle(args: JsonNode) = HandlerResult.Data(Json.obj())

    build(Vector(rogue)) match
      case Left(problems) => problems.head should include("write the schema first")
      case Right(_)       => fail("registry should refuse a handler with no schema")

  test("two handlers for the same tool are refused"):
    build(Vector(diffHandler(), diffHandler())) match
      case Left(problems) => problems.head should include("more than one handler")
      case Right(_)       => fail("registry should refuse duplicate handlers")

  test("a schema with no handler is fine — the catalog is written before the code"):
    build(Vector(diffHandler())).isRight shouldBe true

  test("a permitted agent can invoke, and gets an evidence id back"):
    val (reg, ledger) = build(Vector(diffHandler())).toOption.get
    reg.invoke("history_diff", validDiffArgs, "bugfix-adjudicator") match
      case Right(ToolResponse.Ok(ev, _)) =>
        ledger.contains(ev) shouldBe true
        ledger.size shouldBe 1
      case other => fail(s"expected Ok, got $other")

  test("an agent not named in the schema is refused"):
    val (reg, ledger) = build(Vector(diffHandler())).toOption.get
    // path-tracer has history_diff; symptom-characterizer does not.
    reg.invoke("history_diff", validDiffArgs, "symptom-characterizer") match
      case Left(e: InvocationError.NotPermittedForAgent) =>
        e.explain should include("may not call")
      case other => fail(s"expected NotPermittedForAgent, got $other")
    // and nothing was recorded — a refused call is not evidence
    ledger.size shouldBe 0

  test("the orchestrator may call pipeline tools that no agent may"):
    val (reg, _) = build(Vector(diffHandler())).toOption.get
    reg.permits("repo_clone", "orchestrator") shouldBe true
    reg.permits("repo_clone", "path-tracer") shouldBe false

  test("input that violates the schema is rejected before the handler runs"):
    var ran = false
    val handler = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) = { ran = true; HandlerResult.Data(Json.obj()) }

    val (reg, _) = build(Vector(handler)).toOption.get
    val bad = Json.obj()
    bad.put("repoHandle", "repo_0123456789abcdef")
    bad.put("sha", "abbrev") // not a 40-hex sha

    reg.invoke("history_diff", bad, "path-tracer") match
      case Left(_: InvocationError.InvalidInput) => ran shouldBe false
      case other                                 => fail(s"expected InvalidInput, got $other")

  test("output that violates the schema is rejected, so a malformed payload never becomes evidence"):
    val liar = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) =
        val d = Json.obj()
        d.put("files", "not an array")
        HandlerResult.Data(d)

    val (reg, ledger) = build(Vector(liar)).toOption.get
    reg.invoke("history_diff", validDiffArgs, "path-tracer") match
      case Left(_: InvocationError.InvalidOutput) => ledger.size shouldBe 0
      case other                                  => fail(s"expected InvalidOutput, got $other")

  test("an exception in a handler becomes an invocation error, not a finding"):
    val boom = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) = throw RuntimeException("jgit exploded")

    val (reg, ledger) = build(Vector(boom)).toOption.get
    reg.invoke("history_diff", validDiffArgs, "path-tracer") match
      case Left(e: InvocationError.HandlerFailed) =>
        e.explain should include("jgit exploded")
        ledger.size shouldBe 0
      case other => fail(s"expected HandlerFailed, got $other")

  // Unknown is a SUCCESS carrying evidence — the whole neutrality design depends on this.
  test("an Unknown result succeeds and is itself recorded as citable evidence"):
    val undetermined = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) =
        HandlerResult.Undetermined(UnknownReason.Truncated, "10k files, capped")

    val (reg, ledger) = build(Vector(undetermined)).toOption.get
    reg.invoke("history_diff", validDiffArgs, "path-tracer") match
      case Right(u: ToolResponse.Unknown) =>
        u.reason shouldBe UnknownReason.Truncated
        ledger.contains(u.evidence) shouldBe true
      case other => fail(s"expected Unknown response, got $other")

  test("a tool may not return an Unknown reason its schema does not declare"):
    val offSpec = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) =
        // history_diff declares only Truncated
        HandlerResult.Undetermined(UnknownReason.RateLimited, "not declared here")

    val (reg, ledger) = build(Vector(offSpec)).toOption.get
    reg.invoke("history_diff", validDiffArgs, "path-tracer") match
      case Left(e: InvocationError.UndeclaredUnknownReason) =>
        e.explain should include("declares only")
        ledger.size shouldBe 0
      case other => fail(s"expected UndeclaredUnknownReason, got $other")

  test("evidence ids are deterministic: same call, same result, same id"):
    val (reg1, _) = build(Vector(diffHandler())).toOption.get
    val (reg2, _) = build(Vector(diffHandler())).toOption.get
    val a = reg1.invoke("history_diff", validDiffArgs, "path-tracer").toOption.get
    val b = reg2.invoke("history_diff", validDiffArgs, "path-tracer").toOption.get
    a.evidenceId shouldBe b.evidenceId

  test("argument key order does not change the evidence id"):
    val (reg, _) = build(Vector(diffHandler())).toOption.get

    val ordered = Json.obj()
    ordered.put("repoHandle", "repo_0123456789abcdef")
    ordered.put("sha", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678")

    val reversed = Json.obj()
    reversed.put("sha", "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678")
    reversed.put("repoHandle", "repo_0123456789abcdef")

    val a = reg.invoke("history_diff", ordered, "path-tracer").toOption.get
    val b = reg.invoke("history_diff", reversed, "path-tracer").toOption.get
    a.evidenceId shouldBe b.evidenceId

  test("different arguments produce different evidence ids"):
    val (reg, _) = build(Vector(diffHandler())).toOption.get
    val other = Json.obj()
    other.put("repoHandle", "repo_0123456789abcdef")
    other.put("sha", "ffffffffffffffffffffffffffffffffffffffff")

    val a = reg.invoke("history_diff", validDiffArgs, "path-tracer").toOption.get
    val b = reg.invoke("history_diff", other, "path-tracer").toOption.get
    a.evidenceId should not be b.evidenceId

  test("the envelope carries ok and evidenceId, and puts unknown in its own field"):
    val undetermined = new ToolHandler:
      val name = "history_diff"
      def handle(args: JsonNode) =
        HandlerResult.Undetermined(UnknownReason.Truncated, "capped", Some("LIMIT"))

    val (reg, _) = build(Vector(undetermined)).toOption.get
    val resp = reg.invoke("history_diff", validDiffArgs, "path-tracer").toOption.get
    val json = ToolResponse.toJson(resp)

    json.get("ok").booleanValue() shouldBe true
    json.get("evidenceId").stringValue() should startWith("ev_")
    json.get("unknown").get("reason").stringValue() shouldBe "Truncated"
    json.get("unknown").get("classified").stringValue() shouldBe "LIMIT"
    json.get("data") shouldBe null
