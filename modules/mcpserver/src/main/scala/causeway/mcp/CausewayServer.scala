package causeway.mcp

import causeway.core.{EvidenceLedger, RunId}
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Builds and runs the causeway MCP server over stdio.
  *
  * Thin by design: the transport is the SDK's, the rules live in [[ToolRegistry]], and the
  * protocol adaptation lives in [[ToolBridge]]. Nothing worth testing happens in this file.
  */
object CausewayServer:

  final case class Config(
      schemasDir: Path,
      runId: RunId,
      serverName: String = "causeway",
      version: String = "0.1.0"
  )

  /** Assemble the registry from the schema catalog and the supplied handlers.
    *
    * Fails loudly rather than starting degraded: a server that silently omits tools would look
    * to an agent exactly like a repository with nothing to find.
    */
  def buildRegistry(
      config: Config,
      handlers: Vector[ToolHandler],
      ledger: EvidenceLedger
  ): Either[Vector[String], ToolRegistry] =
    SchemaCatalog.load(config.schemasDir) match
      case Left(errors) => Left(errors.map(_.toString))
      case Right(specs) => ToolRegistry.build(specs, handlers, ledger, config.runId)

  /** Build the server over arbitrary streams.
    *
    * Separated from [[serve]] so the transport wiring can be driven in-process by a test over
    * piped streams — otherwise the only way to exercise it would be a subprocess, and the one
    * part that could actually be wrong (tool publication and dispatch) would go untested.
    *
    * Only tools that have BOTH a schema and a handler are published. A schema without a handler
    * is a catalog entry for something not yet implemented; advertising it would let an agent
    * plan around a capability that does not exist.
    */
  def buildServer(
      registry: ToolRegistry,
      config: Config,
      in: java.io.InputStream,
      out: java.io.OutputStream
  ): io.modelcontextprotocol.server.McpSyncServer =
    val transport = StdioServerTransportProvider(ToolBridge.jsonMapper, in, out)
    assemble(registry, config, transport)

  /** Start the stdio server. Blocks until the transport closes. */
  def serve(registry: ToolRegistry, config: Config): Unit =
    val transport = StdioServerTransportProvider(ToolBridge.jsonMapper)
    val server    = assemble(registry, config, transport)

    // Stdio is the protocol channel: anything written to stdout that is not a JSON-RPC frame
    // corrupts the stream. Diagnostics go to stderr, always.
    System.err.println(
      s"causeway ${config.version} serving ${registry.servedNames.size} of " +
        s"${registry.toolNames.size} catalogued tools"
    )

    sys.addShutdownHook(server.closeGracefully())
    Thread.currentThread().join()

  private def assemble(
      registry: ToolRegistry,
      config: Config,
      transport: StdioServerTransportProvider
  ): io.modelcontextprotocol.server.McpSyncServer =
    val served = registry.servedNames.flatMap(registry.spec)

    val builder = McpServer
      .sync(transport)
      .serverInfo(config.serverName, config.version)
      .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
      // The SDK validates tool inputs itself by default, against the same schema we publish.
      // Turned off deliberately so ToolRegistry is the single authority: it validates, applies
      // the per-agent ACL, and mints evidence, and every failure it reports has the same
      // structured shape — {ok:false, error, detail}. With both validators active an agent sees
      // two different error formats for one class of problem, and cannot reliably tell a
      // malformed argument from a refused one. Nothing is lost: the schema is identical.
      .validateToolInputs(false)
      .instructions(
        """Deterministic services for mining symptom-to-fault paths from repository history.
          |
          |Every response is { ok, evidenceId, data | unknown }. An `unknown` result is a
          |SUCCESS: it means the tool was called and could not determine the value, and the
          |reason is given. It carries an evidenceId and should be cited as a recorded gap
          |rather than retried blindly.
          |
          |Claims are persisted only through findings_record, which rejects any evidenceId this
          |run's ledger did not issue.""".stripMargin
      )

    served.foreach { spec =>
      builder.toolCall(
        ToolBridge.describe(spec),
        (_, request) => ToolBridge.call(registry, spec.name, request.arguments())
      )
    }

    builder.build()
