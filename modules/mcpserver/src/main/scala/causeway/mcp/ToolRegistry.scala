package causeway.mcp

import causeway.core.*
import com.networknt.schema.{Schema, SchemaRegistry, SpecificationVersion}
import tools.jackson.databind.JsonNode

import java.time.{Clock, Instant}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Why an invocation was refused. */
enum InvocationError:
  case NoSuchTool(name: String)
  case NotPermittedForAgent(tool: String, agent: String, permitted: Vector[String])
  case InvalidInput(tool: String, problems: Vector[String])
  case InvalidOutput(tool: String, problems: Vector[String])
  case UndeclaredUnknownReason(tool: String, reason: String, declared: Vector[String])
  case HandlerFailed(tool: String, message: String)

  def explain: String = this match
    case NoSuchTool(n) =>
      s"no tool named '$n' is registered"
    case NotPermittedForAgent(t, a, p) =>
      val who = if p.isEmpty then "(pipeline only)" else p.mkString(", ")
      s"agent '$a' may not call '$t'; permitted: $who"
    case InvalidInput(t, ps) =>
      s"input to '$t' does not match its schema: ${ps.mkString("; ")}"
    case InvalidOutput(t, ps) =>
      s"output of '$t' does not match its schema: ${ps.mkString("; ")}"
    case UndeclaredUnknownReason(t, r, d) =>
      s"'$t' returned Unknown($r) but its schema declares only: ${d.mkString(", ")}"
    case HandlerFailed(t, m) =>
      s"'$t' threw: $m"

/** Serves the tool catalog, and is the only place its rules are enforced.
  *
  * Construction fails if a handler is registered for which no schema exists. That turns the
  * project's schema-before-invocation rule into a startup assertion rather than a convention
  * someone has to remember.
  */
final class ToolRegistry private (
    val specs: Map[String, ToolSpec],
    handlers: Map[String, ToolHandler],
    ledger: EvidenceLedger,
    initialRunId: RunId,
    clock: Clock
):
  /** The MINING run this server is serving, learned from the orchestrator.
    *
    * A server process mints an id at startup because nothing has told it which run it belongs
    * to yet, and a mining run outlives several server processes anyway — this one is on its
    * fifth. Evidence stamped with the process id fragments the run: five ids for one run, and
    * anything asking what the run retrieved answers zero.
    *
    * So the first call carrying an explicit `runId` wins, and the evidence issued before that
    * point is re-attributed rather than stranded. Only the FIRST adoption counts: a later call
    * naming a different run is a caller mistake, and silently following it would scatter the
    * evidence again in the other direction.
    */
  private val currentRun = java.util.concurrent.atomic.AtomicReference(initialRunId)
  @volatile private var adopted = false

  def runId: RunId = currentRun.get()

  private def adoptRunId(args: JsonNode): Unit =
    if !adopted then
      Option(args.get("runId")).filter(_.isTextual).map(_.stringValue())
        .flatMap(RunId(_).toOption)
        .foreach { declared =>
          val previous = currentRun.getAndSet(declared)
          adopted = true
          if previous != declared then ledger.reattribute(previous, declared)
        }
  private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)

  private val compiled: Map[String, (Schema, Schema)] = specs.map { case (n, s) =>
    n -> (schemaRegistry.getSchema(s.inputSchema), schemaRegistry.getSchema(s.outputSchema))
  }

  def toolNames: Vector[String]            = specs.keys.toVector.sorted
  def spec(name: String): Option[ToolSpec] = specs.get(name)
  def servedNames: Vector[String]          = handlers.keys.toVector.sorted

  /** Whether an agent may call a tool.
    *
    * An empty `agents` list means pipeline-only: the orchestrating command may call it, no
    * subagent may. `"orchestrator"` is the caller identity used for command-driven stages.
    */
  def permits(tool: String, agent: String): Boolean =
    specs.get(tool).exists(s => agent == "orchestrator" || s.agents.contains(agent))

  /** Run a tool: check access, validate input, run the handler, validate output, mint and record
    * evidence, and wrap the result.
    */
  def invoke(tool: String, args: JsonNode, agent: String): Either[InvocationError, ToolResponse] =
    for
      s   <- specs.get(tool).toRight(InvocationError.NoSuchTool(tool))
      _   <- Either.cond(
               permits(tool, agent),
               (),
               InvocationError.NotPermittedForAgent(tool, agent, s.agents)
             )
      _   <- validate(compiled(tool)._1, args).left.map(InvocationError.InvalidInput(tool, _))
      // After validation: an id from a call that was going to be rejected anyway must not
      // become the run every later piece of evidence is filed under.
      _    = adoptRunId(args)
      res <- runHandler(tool, args)
      out <- envelope(s, tool, args, res)
    yield out

  private def runHandler(tool: String, args: JsonNode): Either[InvocationError, HandlerResult] =
    handlers.get(tool) match
      case None => Left(InvocationError.NoSuchTool(tool))
      case Some(h) =>
        try Right(h.handle(args))
        catch
          case NonFatal(t) =>
            Left(InvocationError.HandlerFailed(tool, Option(t.getMessage).getOrElse(t.toString)))

  private def envelope(
      s: ToolSpec,
      tool: String,
      args: JsonNode,
      result: HandlerResult
  ): Either[InvocationError, ToolResponse] =
    val canonicalArgs = Json.canonical(args)
    result match
      case HandlerResult.Data(payload) =>
        validate(compiled(tool)._2, payload)
          .left.map(InvocationError.InvalidOutput(tool, _))
          .map(_ => ToolResponse.Ok(record(tool, canonicalArgs, Json.canonical(payload)), payload))

      case HandlerResult.Undetermined(reason, detail, classified) =>
        // A tool may only return the Unknown reasons its schema declares. Otherwise a run's
        // Unknown breakdown — the dataset's honesty report — silently grows categories nobody
        // designed, and stops being comparable between runs.
        if !s.unknownReasons.contains(reason.toString) then
          Left(InvocationError.UndeclaredUnknownReason(tool, reason.toString, s.unknownReasons))
        else
          val marker = s"UNKNOWN|$reason|$detail|${classified.getOrElse("")}"
          Right(ToolResponse.Unknown(record(tool, canonicalArgs, marker), reason, detail, classified))

  /** Mint and persist evidence.
    *
    * The id is derived from the tool name, canonical arguments, and canonical payload, so the
    * same call producing the same result produces the same id — which is what lets the ledger be
    * re-verified after a run rather than merely trusted.
    */
  private def record(tool: String, args: String, payload: String): EvidenceId =
    val id = EvidenceId.mint(tool, args, payload)
    ledger.issue(
      Evidence(
        id = id,
        tool = tool,
        argsHash = EvidenceId.sha256(args),
        payloadHash = EvidenceId.sha256(payload),
        at = Instant.now(clock),
        runId = currentRun.get(),
        provenance = Provenance.ServerIssued
      )
    )
    id

  private def validate(schema: Schema, instance: JsonNode): Either[Vector[String], Unit] =
    val errors = schema.validate(instance).asScala.toVector
    if errors.isEmpty then Right(())
    else Left(errors.map(e => s"${e.getInstanceLocation}: ${e.getMessage}"))

object ToolRegistry:

  /** Build a registry, refusing any handler that has no schema.
    *
    * The reverse — a schema with no handler — is allowed and expected: the catalog is written
    * before the implementations exist. Serving is what requires both.
    */
  def build(
      specs: Vector[ToolSpec],
      handlers: Vector[ToolHandler],
      ledger: EvidenceLedger,
      runId: RunId,
      clock: Clock = Clock.systemUTC()
  ): Either[Vector[String], ToolRegistry] =
    val specMap = specs.map(s => s.name -> s).toMap
    val orphans = handlers.map(_.name).filterNot(specMap.contains)
    val dupes   = handlers.groupBy(_.name).collect { case (n, hs) if hs.size > 1 => n }.toVector

    val problems =
      orphans.map(n => s"handler '$n' has no schema in schemas/tools/ — write the schema first") ++
        dupes.map(n => s"more than one handler registered for '$n'")

    if problems.nonEmpty then Left(problems)
    else
      Right(new ToolRegistry(specMap, handlers.map(h => h.name -> h).toMap, ledger, runId, clock))
