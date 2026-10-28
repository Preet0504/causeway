package causeway.mini

import Json.*

/**
 * Decides whether a Bash command from a restricted agent may run. Used as a PreToolUse hook, so the limit
 * is enforced by the platform rather than by the agent's own instructions.
 */
object Guard:
  /** Anything that can chain, redirect, substitute, or escape out of a single plain invocation. */
  private val Forbidden = Set(';', '&', '|', '<', '>', '$', '`', '\\', '\n', '\r', '(', ')', '{', '}', '!')
  private val Launchers = Set("tools/causeway", "./tools/causeway")

  /** Splits on spaces, honouring single and double quotes; Left on an unterminated quote. */
  def tokens(command: String): Either[String, List[String]] =
    val out = List.newBuilder[String]
    val cur = new StringBuilder
    var quote: Option[Char] = None
    var started = false
    command.foreach { ch =>
      quote match
        case Some(q) if ch == q => quote = None
        case Some(_)            => cur += ch
        case None if ch == '"' || ch == '\'' => quote = Some(ch); started = true
        case None if ch == ' ' || ch == '\t' =>
          if started || cur.nonEmpty then { out += cur.toString; cur.clear(); started = false }
        case None => cur += ch
    }
    if quote.isDefined then Left("unterminated quote")
    else
      if started || cur.nonEmpty then out += cur.toString
      Right(out.result())

  def check(command: String, allowedSubcommands: Set[String]): Either[String, Unit] =
    command.find(Forbidden) match
      case Some(ch) => Left(s"character '${ch.toString.replace("\n", "\\n")}' is not allowed; run one plain tools/causeway command")
      case None =>
        tokens(command.trim).flatMap {
          case launcher :: sub :: _ if Launchers(launcher) && allowedSubcommands(sub) => Right(())
          case launcher :: sub :: _ if Launchers(launcher) =>
            Left(s"subcommand '$sub' is not allowed for this agent; allowed: ${allowedSubcommands.toList.sorted.mkString(", ")}")
          case _ => Left("only tools/causeway commands are allowed")
        }

  /** PreToolUse payload in, exit code out: 0 allows, 2 blocks and feeds the reason back to the agent. */
  def hook(stdin: String, allowed: Set[String], err: java.io.PrintStream): Int =
    scala.util.Try(ujson.read(stdin)).toOption match
      case None =>
        err.println("blocked: could not read the hook input")
        2
      case Some(payload) =>
        if payload.text("tool_name").exists(_ != "Bash") then 0
        else
          val command = payload.opt("tool_input").flatMap(_.text("command")).getOrElse("")
          check(command, allowed) match
            case Right(()) => 0
            case Left(why) =>
              err.println(s"blocked: $why")
              2
