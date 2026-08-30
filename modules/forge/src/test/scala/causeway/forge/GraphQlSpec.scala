package causeway.forge

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}

/** Parsing is tested against responses actually captured from github.com (see
  * `fixtures/forge/`), not against a hand-written guess at the API's shape.
  */
class GraphQlSpec extends AnyFunSuite with Matchers:

  private def fixture(name: String): String =
    val candidates = Vector(
      Paths.get("fixtures", "forge", name),
      Paths.get("..", "..", "fixtures", "forge", name)
    )
    val p: Path = candidates.find(Files.isRegularFile(_))
      .getOrElse(sys.error(s"fixture $name not found from ${Paths.get(".").toAbsolutePath}"))
    Files.readString(p)

  test("a real batched bundle parses, in alias order"):
    val bundles = GraphQl.parseBundle(fixture("jsoup-bundle.json")).toOption.get
    bundles.size shouldBe 6
    bundles.head.sha shouldBe "46b620860af930eedc193a705eaf7cea5aec89a6"
    bundles.map(_.sha).distinct.size shouldBe 6

  test("commit statistics come from the response"):
    val bundles = GraphQl.parseBundle(fixture("jsoup-bundle.json")).toOption.get
    val big = bundles.find(_.sha.startsWith("ccd90e9c")).get
    big.additions shouldBe 164
    big.deletions shouldBe 225
    big.changedFiles shouldBe Some(11)
    big.headline should include("tokenization")

  test("an associated pull request is picked up when present, and absent when not"):
    val bundles = GraphQl.parseBundle(fixture("jsoup-bundle.json")).toOption.get
    bundles.find(_.sha.startsWith("b035b623")).get.pullRequest.map(_.number) shouldBe Some(2579)
    bundles.find(_.sha.startsWith("46b62086")).get.pullRequest shouldBe None

  test("CI status is read from the rollup"):
    val bundles = GraphQl.parseBundle(fixture("jsoup-bundle.json")).toOption.get
    bundles.foreach(_.ciState shouldBe Some("SUCCESS"))

  // The real-world finding that motivated keeping the vcs message-parsing fallback.
  test("closingIssuesReferences is empty even where an issue plainly exists"):
    val bundles = GraphQl.parseBundle(fixture("jsoup-bundle.json")).toOption.get
    // f10c02e1 is "Escape supplementary characters for non-UTF charsets", which is issue #2578 —
    // yet GitHub reports no closing reference, because the link was never made with a keyword.
    val commit = bundles.find(_.sha.startsWith("f10c02e1")).get
    commit.linkedIssues shouldBe empty
    // So an empty list means Unknown(NoLinkedIssue) and a fallback to refs parsed from the
    // commit message — never "this project does not link issues".
    commit.headline should include("supplementary characters")

  test("a real issue parses, labels and all"):
    val issue = GraphQl.parseIssue(fixture("jsoup-issue-2578.json")).toOption.get
    issue.kind shouldBe "Issue"
    issue.number shouldBe 2578
    issue.state shouldBe "CLOSED"
    issue.labels should contain("bug")
    issue.labels should contain("fixed")
    issue.author shouldBe Some("jhy")
    issue.body should include("Supplementary characters")

  // Found on a real repository: jsoup squash-merges, so commit messages end in "(#2580)" — and
  // #2580 is a PULL REQUEST. Querying only `issue(number:)` returned "Could not resolve to an
  // Issue", which reads as "this project does not link issues" when the link was perfectly fine.
  test("a number that is a pull request resolves too, and is labelled as one"):
    val pr = GraphQl.parseIssue(fixture("jsoup-pr-2580.json")).toOption.get
    pr.kind shouldBe "PullRequest"
    pr.number shouldBe 2580
    pr.state shouldBe "MERGED"
    // A PR body carries the same symptom evidence an issue body would; refusing to read it
    // would discard exactly what symptom characterisation needs.
    pr.title should not be empty

  test("a not-found reference is an absence, not a tool failure"):
    val body =
      """{"errors":[{"type":"NOT_FOUND","message":"Could not resolve to an Issue with the number of 99999."}]}"""
    GraphQl.parseIssue(body) match
      case Left(ForgeError.NotFound(m)) => m should include("Could not resolve")
      case other => fail(s"expected NotFound so the caller records a gap, got $other")

  test("a bug label is recognised, and its absence is not treated as a negative"):
    val issue = GraphQl.parseIssue(fixture("jsoup-issue-2578.json")).toOption.get
    IssueRef(issue.number, issue.title, issue.state, issue.labels).looksLikeBugLabel shouldBe true
    IssueRef(1, "t", "CLOSED", Vector.empty).looksLikeBugLabel shouldBe false

  test("a stack trace in the issue body is detected — it is what unlocks rung-1 reproduction"):
    val withTrace = IssueDetail(
      "Issue", 1, "NPE on parse",
      "Got this:\njava.lang.NullPointerException\n\tat org.jsoup.Foo.bar(Foo.java:42)\n",
      "CLOSED", Vector("bug"), Some("someone"), None, None, 0, 1, Vector.empty
    )
    withTrace.hasStackTrace shouldBe true

    val withoutTrace = GraphQl.parseIssue(fixture("jsoup-issue-2578.json")).toOption.get
    withoutTrace.hasStackTrace shouldBe false

  // Rate limiting arrives inside a 200 response, so it must be caught at parse time.
  test("a rate-limit error is a first-class outcome, not an exception"):
    val body =
      """{"errors":[{"type":"RATE_LIMITED","message":"API rate limit exceeded"}]}"""
    GraphQl.parseBundle(body) match
      case Left(ForgeError.RateLimited(d)) => d should include("rate limit")
      case other                           => fail(s"expected RateLimited, got $other")

  test("other GraphQL errors are reported distinctly from rate limiting and not-found"):
    // A genuine protocol-level fault, as opposed to "the thing you asked for is not there".
    val body = """{"errors":[{"message":"Field 'nonsense' doesn't exist on type 'Repository'"}]}"""
    GraphQl.parseBundle(body) match
      case Left(ForgeError.GraphQlErrors(ms)) => ms.head should include("doesn't exist")
      case other                              => fail(s"expected GraphQlErrors, got $other")

  test("a not-found reference is classified as absence, not a protocol error"):
    val body = """{"errors":[{"type":"NOT_FOUND","message":"Could not resolve to a Repository"}]}"""
    GraphQl.parseBundle(body) match
      case Left(ForgeError.NotFound(_)) => succeed
      case other                        => fail(s"expected NotFound, got $other")

  test("a missing repository is reported rather than yielding an empty result"):
    GraphQl.parseBundle("""{"data":{"repository":null}}""") match
      case Left(ForgeError.NotFound(_)) => succeed
      case other                        => fail(s"expected NotFound, got $other")

  test("malformed JSON is reported, not thrown"):
    GraphQl.parseBundle("not json at all") match
      case Left(ForgeError.Malformed(_)) => succeed
      case other                         => fail(s"expected Malformed, got $other")

  test("a null commit alias is skipped rather than producing a blank bundle"):
    val body = """{"data":{"repository":{"c0":null,"c1":{"oid":"abc","messageHeadline":"x",
                 |"message":"x","additions":1,"deletions":0}}}}""".stripMargin
    val bundles = GraphQl.parseBundle(body).toOption.get
    bundles.size shouldBe 1
    bundles.head.sha shouldBe "abc"

  test("a deleted comment author does not break parsing"):
    val body = """{"data":{"repository":{"issueOrPullRequest":{"__typename":"Issue",
                 |"number":1,"title":"t","body":"b","state":"CLOSED",
                 |"author":null,"labels":{"nodes":[]},
                 |"comments":{"totalCount":1,"nodes":[{"author":null,"body":"hi","createdAt":null}]},
                 |"participants":{"totalCount":1}}}}}""".stripMargin
    val issue = GraphQl.parseIssue(body).toOption.get
    issue.author shouldBe None
    issue.comments.head.author shouldBe "(deleted)"

  test("the batched query addresses every sha it was given"):
    val q = GraphQl.bundleQuery("jhy", "jsoup", Vector("aaa", "bbb", "ccc"))
    q should include("c0:")
    q should include("c2:")
    q should include("aaa")
    q should include("ccc")
