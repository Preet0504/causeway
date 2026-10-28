package causeway.mini

import java.time.Instant

class EnrichSpec extends munit.FunSuite:
  test("closing keywords yield issue numbers for this repo only"):
    val text = "Fixes #12, closes other/repo#9 and Resolved: o/r#3. Also fix #12 again."
    assertEquals(Enrich.closingRefs(text, "o/r"), List(12, 3))

  test("'prefix #3' is not a closing keyword"):
    assertEquals(Enrich.closingRefs("prefix #3", "o/r"), Nil)

  test("bare references are found, but cross-repo refs, anchors, and HTML entities are not"):
    assertEquals(Enrich.mentionedRefs("see #4, don't use &#39; or page#5 or o/r#6; done (#7)"), List(4, 7))

  private def pr(n: Int, body: String, merged: Boolean = true) =
    ujson.Obj("number" -> n, "title" -> s"PR $n", "body" -> body, "state" -> "closed",
      "merged_at" -> (if merged then ujson.Str("2026-01-01T00:00:00Z") else ujson.Null), "html_url" -> s"https://x/pull/$n")
  private def issue(n: Int, isPr: Boolean = false) =
    val o = ujson.Obj("number" -> n, "title" -> s"Issue $n", "body" -> "it crashes", "state" -> "closed",
      "html_url" -> s"https://x/issues/$n")
    if isPr then o("pull_request") = ujson.Obj("url" -> "u")
    o

  private def commit(sha: String, msg: String) = CommitRow(sha, "2026-01-01T00:00:00Z", msg, Nil, "", false)
  private val a = "a" * 40
  private val b = "b" * 40
  private val c = "c" * 40
  private val d = "d" * 40

  private def setup(): (Db, FakeGitHub) =
    val db = Db.inMemory()
    db.createRun(RunRow("r1", "2026-01-01T00:00:00Z", "o/r", "u", None, a, "/x", None, 10, None),
      List(commit(a, "Fix crash on empty input"), commit(b, "Handle nulls (#11)"), commit(c, "Refactor"), commit(d, "Fix thing")))
    val gh = FakeGitHub {
      case (p, _) if p == s"/repos/o/r/commits/$a/pulls" => ujson.Arr(pr(10, "Fixes #5"))
      case (p, _) if p == s"/repos/o/r/commits/$b/pulls" => ujson.Arr()
      case (p, _) if p == s"/repos/o/r/commits/$c/pulls" => ujson.Arr()
      case (p, _) if p == s"/repos/o/r/commits/$d/pulls" => throw GitHubError(500, "boom")
      case ("/repos/o/r/issues/5", _)  => issue(5)
      case ("/repos/o/r/issues/11", _) => issue(11, isPr = true)
    }
    (db, gh)

  test("links PRs from GitHub and issues from closing keywords in the PR body"):
    val (db, gh) = setup()
    Enrich.run(gh, db, "r1", Instant.parse("2026-02-01T00:00:00Z"))
    val links = db.links("r1", a)
    assertEquals(links.map(l => (l.kind, l.number, l.how)), List(("issue", 5, "pr_closing_ref"), ("pr", 10, "github_pr")))
    assertEquals(links.find(_.number == 10).map(_.state), Some("merged"))

  test("a #N in a commit message that is actually a PR is stored as a pr"):
    val (db, gh) = setup()
    Enrich.run(gh, db, "r1", Instant.now())
    assertEquals(db.links("r1", b).map(l => (l.kind, l.number, l.how)), List(("pr", 11, "message_ref")))

  test("commits with nothing linked get no rows, and a failing commit is reported without stopping the rest"):
    val (db, gh) = setup()
    val summary = Enrich.run(gh, db, "r1", Instant.now())
    assertEquals(db.links("r1", c), Nil)
    assertEquals(summary("errors").num.toInt, 1)
    assertEquals(summary("commits_with_links").num.toInt, 2)
    assert(db.run("r1").enrichedAt.isDefined)

  test("running twice does not duplicate links"):
    val (db, gh) = setup()
    Enrich.run(gh, db, "r1", Instant.now())
    Enrich.run(gh, db, "r1", Instant.now())
    assertEquals(db.links("r1", a).size, 2)

  test("a run where every call failed is not marked enriched"):
    val db = Db.inMemory()
    db.createRun(RunRow("r1", "2026-01-01T00:00:00Z", "o/r", "u", None, a, "/x", None, 10, None), List(commit(a, "Fix")))
    val gh = FakeGitHub { case (_, _) => throw GitHubError(401, "Bad credentials") }
    Enrich.run(gh, db, "r1", Instant.now())
    assertEquals(db.run("r1").enrichedAt, None)
