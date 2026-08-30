package causeway.mcp

import causeway.core.{EvidenceId, UnknownReason}
import tools.jackson.databind.JsonNode

/** What a tool handler returns, before the envelope is applied.
  *
  * Note there is no failure case. A tool that cannot determine something returns
  * [[HandlerResult.Undetermined]], which becomes a SUCCESSFUL response carrying an evidence id.
  * "We called the tool and it could not tell us, for this reason" is a retrievable fact and must
  * be citable; turning it into an exception would erase it.
  *
  * Genuine faults — a bug in the handler, a broken invariant — still throw, and are caught at
  * the registry boundary and reported as an invocation error rather than a finding.
  */
enum HandlerResult:
  case Data(payload: JsonNode)
  case Undetermined(reason: UnknownReason, detail: String, classified: Option[String] = None)

/** The universal response envelope.
  *
  * Every tool returns this shape, which is why no individual tool schema restates it — their
  * `outputSchema` describes the `data` payload only.
  */
enum ToolResponse:
  case Ok(evidence: EvidenceId, data: JsonNode)
  case Unknown(
      evidence: EvidenceId,
      reason: UnknownReason,
      detail: String,
      classified: Option[String]
  )

  def evidenceId: EvidenceId = this match
    case Ok(ev, _)           => ev
    case Unknown(ev, _, _, _) => ev

  def isOk: Boolean = this match
    case Ok(_, _)            => true
    case Unknown(_, _, _, _) => false

object ToolResponse:

  def toJson(r: ToolResponse): JsonNode =
    val node = Json.obj()
    node.put("ok", true)
    node.put("evidenceId", r.evidenceId.value)
    r match
      case Ok(_, data) =>
        node.set("data", data)
      case Unknown(_, reason, detail, classified) =>
        val u = Json.obj()
        u.put("reason", reason.toString)
        u.put("detail", detail)
        classified.foreach(c => u.put("classified", c))
        node.set("unknown", u)
    node

/** A tool the server can serve.
  *
  * Deterministic by construction: given the same arguments it must produce the same result,
  * because its output is hashed into an evidence id that is expected to be reproducible.
  */
trait ToolHandler:
  def name: String
  def handle(args: JsonNode): HandlerResult
