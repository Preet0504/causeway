package causeway.mini

class ArgsSpec extends munit.FunSuite:
  test("parses a command with options and a bare flag"):
    val a = Args.parse(Seq("show-evidence", "--run", "ab12", "--only-bug-fixes"))
    assertEquals(a.command, Some("show-evidence"))
    assertEquals(a.req("run"), "ab12")
    assert(a.flag("only-bug-fixes"))

  test("no command is allowed so 'help' can print"):
    assertEquals(Args.parse(Seq.empty).command, None)

  test("a missing required option names the option"):
    val e = intercept[UsageError](Args.parse(Seq("enrich")).req("run"))
    assert(e.getMessage.contains("--run"))

  test("a non-numeric integer option is a usage error, not a crash"):
    val a = Args.parse(Seq("list-runs", "--limit", "many"))
    intercept[UsageError](a.int("limit"))

  test("a stray positional argument is rejected"):
    intercept[UsageError](Args.parse(Seq("enrich", "oops")))

  test("an option value may contain spaces when passed as one argument"):
    assertEquals(Args.parse(Seq("search-repos", "--keywords", "web framework")).get("keywords"), Some("web framework"))
