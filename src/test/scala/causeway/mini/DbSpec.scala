package causeway.mini

class DbSpec extends munit.FunSuite:
  private def commit(sha: String, at: String) = CommitRow(sha, at, s"msg $sha", List("f"), "diff", false)
  private def scored(sha: String, s: Double) =
    Scored(sha, Signal(s, "m"), Signal(s, "d"), None)

  private def run(id: String, at: String) =
    RunRow(id, at, "o/r", "u", Some("main"), "a" * 40, "/p", None, 10, None)

  test("list-runs reports commits, classified, and bug fixes per run, newest run first"):
    val db = Db.inMemory()
    db.createRun(run("old", "2026-01-01T00:00:00Z"), List(commit("a1", "2026-01-01T00:00:00Z")))
    db.createRun(run("new", "2026-02-01T00:00:00Z"),
      List(commit("b1", "2026-01-03T00:00:00Z"), commit("b2", "2026-01-02T00:00:00Z"), commit("b3", "2026-01-01T00:00:00Z")))
    db.saveClassification("new", scored("b1", 0.9), "t")
    db.saveClassification("new", scored("b2", 0.1), "t")
    val runs = db.listRuns(10)
    assertEquals(runs.map(_("run").str), List("new", "old"))
    assertEquals((runs.head("commits").num.toInt, runs.head("classified").num.toInt, runs.head("bug_fixes").num.toInt), (3, 2, 1))
    assertEquals(runs(1)("bug_fixes").num.toInt, 0)

  test("reclassifying a commit replaces its verdict instead of duplicating it"):
    val db = Db.inMemory()
    db.createRun(run("r", "2026-01-01T00:00:00Z"), List(commit("a1", "2026-01-01T00:00:00Z")))
    db.saveClassification("r", scored("a1", 0.9), "t")
    db.saveClassification("r", scored("a1", 0.2), "t")
    assertEquals(db.runSummary("r")("classified").num.toInt, 1)
    assertEquals(db.runSummary("r")("bug_fixes").num.toInt, 0)

  test("a verdict for a commit that is not in the run is refused by the database"):
    val db = Db.inMemory()
    db.createRun(run("r", "2026-01-01T00:00:00Z"), List(commit("a1", "2026-01-01T00:00:00Z")))
    intercept[java.sql.SQLException](db.saveClassification("r", scored("ghost", 0.9), "t"))

  test("unclassified commits come back newest first and shrink as verdicts are stored"):
    val db = Db.inMemory()
    db.createRun(run("r", "2026-01-01T00:00:00Z"),
      List(commit("old", "2026-01-01T00:00:00Z"), commit("new", "2026-01-05T00:00:00Z")))
    assertEquals(db.unclassifiedCommits("r", 5).map(_.sha), List("new", "old"))
    db.saveClassification("r", scored("new", 0.9), "t")
    assertEquals(db.unclassifiedCommits("r", 5).map(_.sha), List("old"))

  test("an unknown run id is a usage error that points at list-runs"):
    val e = intercept[UsageError](Db.inMemory().run("nope"))
    assert(e.getMessage.contains("list-runs"))

  test("a failed transaction leaves nothing behind"):
    val db = Db.inMemory()
    intercept[java.sql.SQLException](db.createRun(run("r", "t"), List(commit("a", "t"), commit("a", "t"))))
    assertEquals(db.listRuns(10), Nil)

  test("data written to a file survives reopening it"):
    val path = java.nio.file.Files.createTempDirectory("causeway-db").resolve("c.db")
    val first = Db.open(path)
    first.createRun(run("r", "2026-01-01T00:00:00Z"), List(commit("a1", "2026-01-01T00:00:00Z")))
    first.close()
    val second = Db.open(path)
    assertEquals(second.listRuns(10).map(_("run").str), List("r"))
    second.close()
