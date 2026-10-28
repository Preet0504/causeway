package causeway.mini

/** The whole pipeline through the real CLI entry point, against a local git repo and a fake GitHub. */
class CliSpec extends munit.FunSuite:
  private def fixture() =
    val origin = TestRepo.create()
    origin.commit(Map("Parser.scala" -> "def parse(s: String) = s.trim\n"), "Add parser", 1000)
    val fix = origin.commit(Map("Parser.scala" -> "def parse(s: String) = Option(s).map(_.trim).getOrElse(\"\")\n"),
      "Fix NullPointerException when input is null\n\nFixes #7", 2000)
    val tip = origin.commit(Map("README.md" -> "docs\n"), "Update README", 3000)
    val gh = FakeGitHub {
      case (p, _) if p == s"/repos/o/r/commits/$fix/pulls" => ujson.Arr(ujson.Obj("number" -> 8, "title" -> "Null-safe parse",
        "body" -> "Fixes #7", "state" -> "closed", "merged_at" -> "2026-01-02T00:00:00Z", "html_url" -> "https://x/pull/8"))
      case (p, _) if p.endsWith("/pulls") => ujson.Arr()
      case ("/repos/o/r/issues/7", _) => ujson.Obj("number" -> 7, "title" -> "NPE in parse", "body" -> "parse(null) throws",
        "state" -> "closed", "html_url" -> "https://x/issues/7")
    }
    (origin, fix, tip, gh)

  private def reply(fix: String, other: String) =
    s"""[{"sha":"$fix","message":{"score":0.95,"explanation":"says Fix NullPointerException"},
       |  "diff":{"score":0.9,"explanation":"wraps the argument in Option"},
       |  "linked":{"score":0.9,"explanation":"issue 7 reports parse(null) throwing"}},
       | {"sha":"$other","message":{"score":0.05,"explanation":"docs update"},
       |  "diff":{"score":0.02,"explanation":"only README changed"},"linked":null}]""".stripMargin

  test("discover → inspect → enrich → classify → list, end to end"):
    val (origin, fix, tip, gh) = fixture()
    val h = CliHarness(gh)

    val inspected = h.json("inspect-repo", "--url", origin.dir.toString, "--name", "o/r", "--scan-limit", "10")
    val run = inspected("run").str
    assertEquals(inspected("pinned_sha").str, tip)
    assertEquals(inspected("commits").num.toInt, 3)

    val enriched = h.json("enrich", "--run", run)
    assertEquals(enriched("commits_with_links").num.toInt, 1)

    val batch = h.json("next-batch", "--run", run)
    assertEquals(batch("commits").arr.size, 3)
    val fixEntry = batch("commits").arr.find(_("sha").str == fix).get
    assertEquals(fixEntry("linked").arr.map(_("number").num.toInt).toSet, Set(7, 8))
    assert(fixEntry("diff").str.contains("Option(s)"))

    val readme = batch("commits").arr.find(_("message").str == "Update README").get("sha").str
    val noLinks = batch("commits").arr.find(_("message").str == "Add parser").get("sha").str
    val recorded = ujson.read(h.runWith(reply(fix, readme), "record-classification", "--run", run)._2)
    assertEquals(recorded("stored").num.toInt, 2)

    val status = h.json("run-status", "--run", run)
    assertEquals(status("unclassified").num.toInt, 1)
    assertEquals(h.json("next-batch", "--run", run)("commits").arr.map(_("sha").str).toList, List(noLinks))

    val evidence = h.json("show-evidence", "--run", run, "--only-bug-fixes")("commits").arr
    assertEquals(evidence.map(_("sha").str).toList, List(fix))
    assertEquals(evidence.head("evidence")("diff")("explanation").str, "wraps the argument in Option")
    assertEquals(evidence.head("evidence")("linked")("score").num, 0.9)

    val runs = h.json("list-runs")("runs").arr
    assertEquals((runs.head("run").str, runs.head("commits").num.toInt, runs.head("classified").num.toInt, runs.head("bug_fixes").num.toInt),
      (run, 3, 2, 1))

  test("a second inspect of the same repo is a separate run"):
    val (origin, _, _, gh) = fixture()
    val h = CliHarness(gh)
    val one = h.json("inspect-repo", "--url", origin.dir.toString, "--name", "o/r")("run").str
    val two = h.json("inspect-repo", "--url", origin.dir.toString, "--name", "o/r")("run").str
    assertNotEquals(one, two)
    assertEquals(h.json("list-runs")("runs").arr.size, 2)

  test("--commit pins the run to that commit"):
    val (origin, fix, _, gh) = fixture()
    val h = CliHarness(gh)
    val r = h.json("inspect-repo", "--url", origin.dir.toString, "--name", "o/r", "--commit", fix)
    assertEquals(r("pinned_sha").str, fix)
    assertEquals(r("commits").num.toInt, 2)

  test("inspect-repo on a non-GitHub path without --name explains what to do"):
    val (origin, _, _, gh) = fixture()
    val (code, _, err) = CliHarness(gh).run("inspect-repo", "--url", origin.dir.toString)
    assertEquals(code, 1)
    assert(err.contains("--name"))

  test("unknown run ids and unknown commands exit 1 with a message"):
    val h = CliHarness(FakeGitHub(PartialFunction.empty))
    val (c1, _, e1) = h.run("enrich", "--run", "nope")
    assertEquals(c1, 1); assert(e1.contains("list-runs"))
    val (c2, _, e2) = h.run("frobnicate")
    assertEquals(c2, 1); assert(e2.contains("unknown command"))

  test("record-classification with an unparseable reply stores nothing and exits 1"):
    val (origin, _, _, gh) = fixture()
    val h = CliHarness(gh)
    val run = h.json("inspect-repo", "--url", origin.dir.toString, "--name", "o/r")("run").str
    val (code, _, _) = h.runWith("looks like a bug fix to me", "record-classification", "--run", run)
    assertEquals(code, 1)
    assertEquals(h.json("run-status", "--run", run)("status")("classified").num.toInt, 0)

  test("guard-bash blocks through the CLI with exit code 2"):
    val h = CliHarness(FakeGitHub(PartialFunction.empty))
    val bad = """{"tool_name":"Bash","tool_input":{"command":"cat /etc/passwd"}}"""
    val good = """{"tool_name":"Bash","tool_input":{"command":"tools/causeway search-repos --language Scala"}}"""
    assertEquals(h.runWith(bad, "guard-bash", "--allow", "search-repos")._1, 2)
    assertEquals(h.runWith(good, "guard-bash", "--allow", "search-repos")._1, 0)
