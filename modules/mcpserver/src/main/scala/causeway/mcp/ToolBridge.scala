package causeway.mcp

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.databind.JsonNode

import scala.jdk.CollectionConverters.*

/** Adapts between the MCP protocol and [[ToolRegistry]].
  *
  * Kept separate from the server wiring so the interesting part — how a call is validated,
  * executed and rendered — is testable without opening a stdio transport.
  */
object ToolBridge:

  val jsonMapper: McpJsonMapper = JacksonMcpJsonMapper(Json.mapper)

  /** The argument an agent may pass to identify itself.
    *
    * ## Why this exists, and what it is NOT
    *
    * MCP carries no caller identity, and in Claude Code every subagent shares the parent
    * session's connection to this server. The transport therefore CANNOT tell the
    * `path-tracer` from the `bugfix-adjudicator`, and no amount of server-side code changes
    * that.
    *
    * So the real per-agent access control is each agent's `tools:` frontmatter — an agent
    * simply is not offered a tool that is not listed there, and `tools/sync-catalog.mjs`
    * verifies that listing against `x-causeway.agents` in both directions.
    *
    * This field is defence in depth, not the fence. When an agent supplies it, the registry
    * checks it; when it does not, the call is treated as coming from the orchestrator. Nothing
    * here should be mistaken for a security boundary against a caller that lies.
    */
  val AgentField = "_agent"

  /** Convert MCP's `Map[String, Object]` arguments into a Jackson node, dropping `_agent`.
    *
    * `_agent` is protocol plumbing, not a tool argument: leaving it in would fail input
    * validation against schemas that set `additionalProperties: false`, and would change the
    * canonical form the evidence id is derived from.
    */
  def toJson(args: java.util.Map[String, Object]): (JsonNode, String) =
    val scalaArgs = Option(args).map(_.asScala.toMap).getOrElse(Map.empty)
    val agent = scalaArgs.get(AgentField).map(_.toString).filter(_.nonEmpty).getOrElse("orchestrator")
    val node = Json.mapper.valueToTree[JsonNode]((scalaArgs - AgentField).asJava)
    (node, agent)

  /** Run one call and render the result.
    *
    * Both branches return a `CallToolResult` rather than throwing:
    *
    *   - a successful call, including one whose payload is `unknown`, is `isError = false`.
    *     An Unknown is a retrieved fact ("we asked and could not tell, for this reason") and
    *     must reach the agent as data it can cite, not as a failure it will retry blindly.
    *   - an invocation error — bad input, refused access, a handler that threw — is
    *     `isError = true` with an explanation, because that IS something the agent should act
    *     on differently.
    */
  def call(registry: ToolRegistry, name: String, args: java.util.Map[String, Object]): McpSchema.CallToolResult =
    val (node, agent) = toJson(args)

    registry.invoke(name, node, agent) match
      case Right(response) =>
        McpSchema.CallToolResult
          .builder()
          .addTextContent(Json.write(ToolResponse.toJson(response)))
          .isError(false)
          .build()

      case Left(err) =>
        val payload = Json.obj()
        payload.put("ok", false)
        payload.put("error", err.getClass.getSimpleName.replace("$", ""))
        payload.put("detail", err.explain)
        McpSchema.CallToolResult
          .builder()
          .addTextContent(Json.write(payload))
          .isError(true)
          .build()

  /** Describe one tool to the protocol.
    *
    * Only the input schema is published. The output schema stays server-side because it
    * describes the `data` payload alone, whereas what actually goes over the wire is the
    * envelope — publishing the inner schema would tell clients to expect a shape they never
    * receive.
    */
  def describe(spec: ToolSpec): McpSchema.Tool =
    McpSchema.Tool
      .builder()
      .name(spec.name)
      .title(spec.title)
      .description(spec.description)
      .inputSchema(jsonMapper, Json.write(spec.inputSchema))
      .build()
