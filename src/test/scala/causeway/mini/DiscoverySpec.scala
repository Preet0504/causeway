package causeway.mini

class DiscoverySpec extends munit.FunSuite:
  private def item(name: String, archived: Boolean = false, fork: Boolean = false, issues: Boolean = true) =
    ujson.Obj("full_name" -> name, "html_url" -> s"https://github.com/$name", "description" -> ujson.Null,
      "language" -> "Scala", "stargazers_count" -> 100, "forks_count" -> 5, "size" -> 900,
      "pushed_at" -> "2026-01-01T00:00:00Z", "default_branch" -> "main", "has_issues" -> issues,
      "archived" -> archived, "fork" -> fork)

  test("builds a qualifier string from a spec"):
    val q = Discovery.buildQuery(SearchSpec(
      language = Some("Scala"), minStars = Some(100), maxStars = Some(5000), pushedAfter = Some("2025-06-01"),
      minSizeKb = Some(500), topics = List("cli"))).toOption.get
    assertEquals(q, "language:Scala stars:100..5000 pushed:>=2025-06-01 size:>=500 topic:cli fork:false archived:false is:public")

  test("a value cannot smuggle in another qualifier"):
    val bad = Discovery.buildQuery(SearchSpec(language = Some("Scala stars:>1000000")))
    assert(bad.left.exists(_.contains("language")))
    assert(Discovery.buildQuery(SearchSpec(keywords = Some("x user:someone"))).isLeft)
    assert(Discovery.buildQuery(SearchSpec(pushedAfter = Some("last week"))).isLeft)

  test("rejects archived, fork, and evidence-free repos; accepts the rest"):
    val ok = Discovery.parseCandidate(item("o/ok"))
    assertEquals(Discovery.rejection(ok, 3, 0), None)
    assertEquals(Discovery.rejection(ok, 0, 2), None)
    assert(Discovery.rejection(ok, 0, 0).exists(_.contains("no closed issues")))
    assert(Discovery.rejection(Discovery.parseCandidate(item("o/a", archived = true)), 5, 5).contains("archived"))
    assert(Discovery.rejection(Discovery.parseCandidate(item("o/f", fork = true)), 5, 5).contains("is a fork"))

  test("search qualifies candidates and stops once enough qualify"):
    val gh = FakeGitHub {
      case ("/search/repositories", _) =>
        ujson.Obj("items" -> ujson.Arr(item("o/empty"), item("o/good1"), item("o/good2"), item("o/never-checked")))
      case ("/search/issues", q) =>
        val query = q("q")
        val total = if query.contains("o/empty") then 0 else 4
        ujson.Obj("total_count" -> total)
    }
    val (_, checked) = Discovery.search(gh, SearchSpec(maxResults = 2)).toOption.get
    assertEquals(checked.map(_.candidate.fullName), List("o/empty", "o/good1", "o/good2"))
    assertEquals(checked.map(_.rejection.isEmpty), List(false, true, true))

  test("a repo with issues disabled skips the closed-issue lookup"):
    val gh = FakeGitHub {
      case ("/search/repositories", _) => ujson.Obj("items" -> ujson.Arr(item("o/noissues", issues = false)))
      case ("/search/issues", q) =>
        assert(!q("q").contains("is:issue"), "should not ask for closed issues")
        ujson.Obj("total_count" -> 7)
    }
    val (_, checked) = Discovery.search(gh, SearchSpec()).toOption.get
    assertEquals(checked.head.closedIssues, Some(0))
    assertEquals(checked.head.mergedPrs, Some(7))
    assert(checked.head.rejection.isEmpty)

  test("search results are stored once per repo and can be re-searched"):
    val gh = FakeGitHub {
      case ("/search/repositories", _) => ujson.Obj("items" -> ujson.Arr(item("o/good")))
      case ("/search/issues", _)       => ujson.Obj("total_count" -> 2)
    }
    val h = CliHarness(gh)
    h.json("search-repos", "--language", "Scala")
    h.json("search-repos", "--language", "Scala")
    val repos = h.json("list-repos")("repos").arr
    assertEquals(repos.size, 1)
    assertEquals(repos.head("full_name").str, "o/good")
