package causeway.mini

/** Thrown for anything the caller got wrong; the CLI prints it and exits 1. */
final class UsageError(msg: String) extends RuntimeException(msg)

/** `command --key value --flag`. A key followed by another key or nothing is "true". */
final case class Args(command: Option[String], opts: Map[String, String]):
  def get(key: String): Option[String] = opts.get(key).filter(_.nonEmpty)

  def req(key: String): String =
    get(key).getOrElse(throw UsageError(s"missing required option --$key"))

  def int(key: String): Option[Int] =
    get(key).map(v => v.toIntOption.getOrElse(throw UsageError(s"--$key must be an integer, got '$v'")))

  def flag(key: String): Boolean = opts.get(key).contains("true")

object Args:
  def parse(argv: Seq[String]): Args =
    val (command, rest) = argv.headOption match
      case Some(c) if !c.startsWith("--") => (Some(c), argv.tail)
      case _                              => (None, argv)
    val opts = Map.newBuilder[String, String]
    var i = 0
    while i < rest.length do
      val token = rest(i)
      if !token.startsWith("--") then throw UsageError(s"unexpected argument '$token'")
      val key = token.drop(2)
      if key.isEmpty then throw UsageError("empty option name '--'")
      if i + 1 < rest.length && !rest(i + 1).startsWith("--") then
        opts += key -> rest(i + 1)
        i += 2
      else
        opts += key -> "true"
        i += 1
    Args(command, opts.result())
