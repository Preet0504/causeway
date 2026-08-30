package causeway.mcp

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Who invokes a tool, and therefore who may be granted it.
  *
  *   - `P` pipeline: driven by the orchestrating command in fixed stages. Expensive, stateful.
  *   - `E` evidence: agents call these to retrieve facts they reason over. Cheap, read-only.
  *   - `X` experiment: executes agent-authored code. Exactly one tool, always `ask`.
  *   - `R` recording: the only way a claim gets persisted.
  *   - `N` notes: non-evidentiary procedural knowledge. Returns `note_*`, never `ev_*`.
  */
enum ToolClass:
  case P, E, X, R, N

enum Permission:
  case Allow, Ask

/** One tool's contract, as declared in `schemas/tools/<name>.json`.
  *
  * The schema directory is the single source of truth for the catalog: permissions and agent
  * access are generated from `x-causeway`, and the server refuses to serve a tool with no file
  * here.
  */
final case class ToolSpec(
    name: String,
    title: String,
    description: String,
    toolClass: ToolClass,
    module: String,
    permission: Permission,
    agents: Vector[String],
    unknownReasons: Vector[String],
    inputSchema: JsonNode,
    outputSchema: JsonNode
)

final case class SchemaLoadError(file: String, problem: String):
  override def toString = s"$file: $problem"

object SchemaCatalog:

  /** Load every tool schema in a directory, bundling shared `$defs` into each one.
    *
    * Tool files reference shared definitions as `causeway://schemas/_common.json#/$defs/X`.
    * Rather than teach the validator to resolve a custom URI scheme, `_common`'s `$defs` are
    * inlined into each document and those refs rewritten to local `#/$defs/X`. The result is
    * self-contained, needs no validator configuration, and the rewrite is a small pure
    * transformation that can be tested on its own.
    */
  def load(schemasDir: Path): Either[Vector[SchemaLoadError], Vector[ToolSpec]] =
    val commonFile = schemasDir.resolve("_common.json")
    if !Files.isRegularFile(commonFile) then
      Left(Vector(SchemaLoadError("_common.json", "missing from schemas directory")))
    else
      val commonDefs = Option(Json.parse(Files.readString(commonFile)).get("$defs"))
      val toolsDir   = schemasDir.resolve("tools")

      if commonDefs.isEmpty then
        Left(Vector(SchemaLoadError("_common.json", "has no $defs block")))
      else if !Files.isDirectory(toolsDir) then
        Left(Vector(SchemaLoadError("tools/", "missing from schemas directory")))
      else
        val files = Using.resource(Files.list(toolsDir)) { stream =>
          stream
            .iterator()
            .asScala
            .filter(_.getFileName.toString.endsWith(".json"))
            .toVector
            .sortBy(_.getFileName.toString)
        }

        val results = files.map(f => parseOne(f, commonDefs.get))
        val errors  = results.collect { case Left(e) => e }.flatten
        if errors.nonEmpty then Left(errors)
        else Right(results.collect { case Right(s) => s })

  private def parseOne(
      file: Path,
      commonDefs: JsonNode
  ): Either[Vector[SchemaLoadError], ToolSpec] =
    val fileName       = file.getFileName.toString
    def err(p: String) = Vector(SchemaLoadError(fileName, p))

    Try(Json.parse(Files.readString(file))).toEither.left
      .map(t => err(s"unparseable JSON: ${t.getMessage}"))
      .flatMap { root =>
        val nameOpt = text(root, "name")
        val xOpt    = Option(root.get("x-causeway"))

        if nameOpt.isEmpty then Left(err("missing \"name\""))
        else if fileName != s"${nameOpt.get}.json" then
          Left(err(s"""filename does not match name "${nameOpt.get}""""))
        else if xOpt.isEmpty then Left(err("missing x-causeway metadata"))
        else buildSpec(root, xOpt.get, nameOpt.get, commonDefs, err)
      }

  private def buildSpec(
      root: JsonNode,
      xc: JsonNode,
      name: String,
      commonDefs: JsonNode,
      err: String => Vector[SchemaLoadError]
  ): Either[Vector[SchemaLoadError], ToolSpec] =
    val clsOpt  = parseClass(text(xc, "class"))
    val permOpt = parsePermission(text(xc, "permission"))
    val inOpt   = Option(root.get("inputSchema"))
    val outOpt  = Option(root.get("outputSchema"))

    if clsOpt.isEmpty then Left(err("bad or missing x-causeway.class"))
    else if permOpt.isEmpty then Left(err("bad or missing x-causeway.permission"))
    else if inOpt.isEmpty then Left(err("missing inputSchema"))
    else if outOpt.isEmpty then Left(err("missing outputSchema"))
    else if clsOpt.get == ToolClass.X && permOpt.get != Permission.Ask then
      Left(err("class X executes agent-authored code and must be permission 'ask'"))
    else
      Right(
        ToolSpec(
          name = name,
          title = text(root, "title").getOrElse(name),
          description = text(root, "description").getOrElse(""),
          toolClass = clsOpt.get,
          module = text(xc, "module").getOrElse("unknown"),
          permission = permOpt.get,
          agents = strings(xc, "agents"),
          unknownReasons = strings(xc, "unknownReasons"),
          inputSchema = bundle(inOpt.get, commonDefs),
          outputSchema = bundle(outOpt.get, commonDefs)
        )
      )

  /** Inline shared `$defs` and rewrite `causeway://` refs to local ones. */
  private[mcp] def bundle(schema: JsonNode, commonDefs: JsonNode): JsonNode =
    val copy = schema.deepCopy()
    rewriteRefs(copy)
    copy match
      case o: ObjectNode =>
        o.set("$defs", commonDefs.deepCopy())
        o
      case other => other

  private def rewriteRefs(node: JsonNode): Unit = node match
    case o: ObjectNode =>
      Option(o.get("$ref")).filter(_.isTextual).map(_.stringValue()).foreach { ref =>
        val local = ref.replace("causeway://schemas/_common.json#", "#")
        if local != ref then o.put("$ref", local)
      }
      o.propertyNames().asScala.foreach(n => rewriteRefs(o.get(n)))
    case arr if arr != null && arr.isArray =>
      arr.values().asScala.foreach(rewriteRefs)
    case _ => ()

  private def text(n: JsonNode, field: String): Option[String] =
    Option(n.get(field)).filter(_.isTextual).map(_.stringValue())

  private def strings(n: JsonNode, field: String): Vector[String] =
    Option(n.get(field))
      .filter(_.isArray)
      .map(_.values().asScala.map(_.stringValue()).toVector)
      .getOrElse(Vector.empty)

  private def parseClass(s: Option[String]): Option[ToolClass] =
    s.flatMap(v => ToolClass.values.find(_.toString == v))

  private def parsePermission(s: Option[String]): Option[Permission] = s match
    case Some("allow") => Some(Permission.Allow)
    case Some("ask")   => Some(Permission.Ask)
    case _             => None
