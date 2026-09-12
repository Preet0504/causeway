package causeway.mini

/** The one compiled entry point for Causeway Mini. Every capability is a
  * subcommand, `causeway <subcommand> [args...]`, invoked through the
  * `tools/causeway` launcher script rather than `sbt runMain` directly.
  * sbt is a build tool: recompiling on change, resolving dependencies, and
  * so on, exactly the right layer for active development, but the wrong
  * layer for something an agent invokes repeatedly and needs to behave
  * predictably. This object, plus the launcher's cached classpath, is what
  * gives the orchestrator a stable command surface (fixed subcommand names,
  * fixed argument shapes, fixed output format) that doesn't depend on
  * sbt's build state or server lifecycle at call time. sbt still runs, but
  * only to rebuild when the code has actually changed, never on every
  * single invocation.
  *
  * Adding a subcommand means adding one case here that delegates to that
  * capability's own `run(Array[String])`, the capability itself stays a
  * plain object with no knowledge of being wrapped in a CLI.
  */
object Causeway:

  def main(args: Array[String]): Unit =
    if args.isEmpty then fail(usage)
    val subcommand = args(0)
    val rest = args.drop(1)
    subcommand match
      case "inspect-repo"     => InspectRepo.run(rest)
      case "inspect-commits"  => EnrichCommits.run(rest)
      case "store"            => Store.run(rest)
      case "-h" | "--help"    => println(usage)
      case other              => fail(s"Unknown subcommand '$other'.\n\n$usage")

  private def usage: String =
    """Usage: causeway <subcommand> [args...]
      |
      |Subcommands:
      |  inspect-repo      Discover a repo's remotes/branches and extract a commit window as evidence.
      |                    Modes: --mode list-remotes | list-branches | count | write
      |  inspect-commits   Enrich an evidence file's commits with GitHub PRs/issues and JGit diffs.
      |  store             Upsert an evidence/enriched/classified JSON file into the SQLite catalog.
      |""".stripMargin

  private def fail(msg: String): Nothing =
    System.err.println(msg)
    sys.exit(1)

end Causeway
