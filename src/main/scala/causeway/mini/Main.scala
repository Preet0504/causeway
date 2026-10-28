package causeway.mini

object Main:
  def main(argv: Array[String]): Unit =
    sys.exit(Cli(Env.fromProcess()).run(argv.toSeq))
