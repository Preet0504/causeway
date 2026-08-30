package causeway.mcp

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

import scala.jdk.CollectionConverters.*

/** JSON helpers.
  *
  * The project is on Jackson 3 (`tools.jackson`), because json-schema-validator 3.x is built
  * against it. Jackson 2 (`com.fasterxml`) types are not interchangeable with these.
  */
object Json:
  val mapper: JsonMapper = JsonMapper()

  def parse(s: String): JsonNode      = mapper.readTree(s)
  def obj(): tools.jackson.databind.node.ObjectNode = mapper.createObjectNode()
  def write(n: JsonNode): String      = mapper.writeValueAsString(n)

  /** Deterministic serialisation for hashing.
    *
    * Object keys are sorted recursively so two structurally identical payloads produce the same
    * string, and therefore the same evidence id, regardless of the order a handler happened to
    * build its fields in. Written by hand rather than configured on the mapper because the
    * property that matters — same content, same id — should be readable in one function rather
    * than depend on serializer settings that a later change might quietly alter.
    */
  def canonical(n: JsonNode): String =
    val sb = StringBuilder()
    writeCanonical(n, sb)
    sb.toString

  private def writeCanonical(n: JsonNode, sb: StringBuilder): Unit =
    if n == null || n.isNull then sb ++= "null"
    else if n.isObject then
      val names = n.propertyNames().asScala.toVector.sorted
      sb += '{'
      names.zipWithIndex.foreach { case (name, i) =>
        if i > 0 then sb += ','
        sb ++= quote(name)
        sb += ':'
        writeCanonical(n.get(name), sb)
      }
      sb += '}'
    else if n.isArray then
      sb += '['
      n.values().asScala.zipWithIndex.foreach { case (e, i) =>
        if i > 0 then sb += ','
        writeCanonical(e, sb)
      }
      sb += ']'
    else if n.isTextual then sb ++= quote(n.stringValue())
    else sb ++= n.toString

  private def quote(s: String): String =
    val sb = StringBuilder()
    sb += '"'
    s.foreach {
      case '"'  => sb ++= "\\\""
      case '\\' => sb ++= "\\\\"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case '\t' => sb ++= "\\t"
      case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
      case c    => sb += c
    }
    sb += '"'
    sb.toString
