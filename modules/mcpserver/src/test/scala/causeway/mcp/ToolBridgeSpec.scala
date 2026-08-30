package causeway.mcp

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import tools.jackson.databind.JsonNode

import java.time.{Clock, Instant, ZoneOffset}
import scala.jdk.CollectionConverters.*

class ToolBridgeSpec extends AnyFunSuite with Matchers:

  private val runId = RunId.unsafe("run_0123456789abcdef")
  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
  private val specs = SchemaCatalog.load(TestPaths.schemas).toOption.get

  private def diffHandler(result: => HandlerResult): ToolHandler = new ToolHandler:
    val name = "history_diff"
    def handle(args: JsonNode): HandlerResult = result

  private def okPayload: HandlerResult =
    val data  = Json.obj()
    data.set("files", Json.mapper.createArrayNode())
    val stats = Json.obj()
    stats.put("linesAdded", 4); stats.put("linesDeleted", 1)
    stats.put("filesTouched", 1); stats.put("hunkCount", 1)
    data.set("stats", stats)
    data.put("truncated", false)
    HandlerResult.Data(data)

  private def registry(handler: ToolHandler) =
    ToolRegistry.build(specs, Vector(handler), InMemoryEvidenceLedger(), runId, clock).toOption.get

  private def args(pairs: (String, String)*): java.util.Map[String, Object] =
    pairs.toMap.map { case (k, v) => k -> (v: Object) }.asJava

  private val validArgs = args(
    "repoHandle" -> "repo_0123456789abcdef",
    "sha" -> "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
  )

  private def textOf(r: io.modelcontextprotocol.spec.McpSchema.CallToolResult): String =
    r.content().asScala.map(_.toString).mkString

  test("a successful call returns the envelope as text, not an error"):
    val r = ToolBridge.call(registry(diffHandler(okPayload)), "history_diff", validArgs)
    r.isError shouldBe false
    textOf(r) should include("\"ok\":true")
    textOf(r) should include("\"evidenceId\":\"ev_")

  // An Unknown must arrive as data the agent can cite, not as a failure it retries blindly.
  test("an unknown result is NOT an error — it is a citable recorded gap"):
    val handler = diffHandler(HandlerResult.Undetermined(UnknownReason.Truncated, "10k files"))
    val r = ToolBridge.call(registry(handler), "history_diff", validArgs)

    r.isError shouldBe false
    textOf(r) should include("\"unknown\"")
    textOf(r) should include("Truncated")
    textOf(r) should include("\"evidenceId\":\"ev_")

  test("an invocation error IS an error, with an explanation to act on"):
    val bad = args("repoHandle" -> "repo_0123456789abcdef", "sha" -> "abbrev")
    val r = ToolBridge.call(registry(diffHandler(okPayload)), "history_diff", bad)

    r.isError shouldBe true
    textOf(r) should include("\"ok\":false")
    textOf(r) should include("InvalidInput")

  test("an unknown tool name is an error, not a silent no-op"):
    val r = ToolBridge.call(registry(diffHandler(okPayload)), "no_such_tool", validArgs)
    r.isError shouldBe true
    textOf(r) should include("NoSuchTool")

  // _agent is protocol plumbing; leaving it in the arguments would fail schema validation
  // against additionalProperties:false and would change the canonical form the id derives from.
  test("_agent is stripped from arguments before validation and hashing"):
    val withAgent = args(
      "repoHandle" -> "repo_0123456789abcdef",
      "sha" -> "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
      ToolBridge.AgentField -> "path-tracer"
    )
    val (node, agent) = ToolBridge.toJson(withAgent)

    agent shouldBe "path-tracer"
    node.has(ToolBridge.AgentField) shouldBe false
    node.has("sha") shouldBe true

  test("the same call with and without _agent yields the same evidence id"):
    val reg = registry(diffHandler(okPayload))
    val withAgent = args(
      "repoHandle" -> "repo_0123456789abcdef",
      "sha" -> "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
      ToolBridge.AgentField -> "path-tracer"
    )
    val a = textOf(ToolBridge.call(reg, "history_diff", validArgs))
    val b = textOf(ToolBridge.call(reg, "history_diff", withAgent))

    val idOf = (s: String) => s.split("\"evidenceId\":\"")(1).takeWhile(_ != '"')
    idOf(a) shouldBe idOf(b)

  test("a call with no _agent is treated as the orchestrator"):
    ToolBridge.toJson(validArgs)._2 shouldBe "orchestrator"

  test("null arguments are tolerated"):
    val (node, agent) = ToolBridge.toJson(null)
    agent shouldBe "orchestrator"
    node.size() shouldBe 0

  // Defence in depth, not a fence: MCP carries no caller identity, so a caller that lies is
  // not stopped here. The real per-agent ACL is each agent's tools: frontmatter.
  test("a supplied agent identity is still checked against the schema's agent list"):
    val reg = registry(diffHandler(okPayload))
    val asSymptom = args(
      "repoHandle" -> "repo_0123456789abcdef",
      "sha" -> "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
      ToolBridge.AgentField -> "symptom-characterizer"
    )
    val r = ToolBridge.call(reg, "history_diff", asSymptom)
    r.isError shouldBe true
    textOf(r) should include("NotPermittedForAgent")

  test("a tool is described to the protocol with its input schema"):
    val spec = specs.find(_.name == "history_diff").get
    val tool = ToolBridge.describe(spec)

    tool.name() shouldBe "history_diff"
    tool.description() should include("rename detection")
    tool.inputSchema() should not be null

  test("only tools with both a schema and a handler are served"):
    val reg = registry(diffHandler(okPayload))
    reg.servedNames shouldBe Vector("history_diff")
    reg.toolNames.size should be > 40
