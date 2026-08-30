package causeway.forge

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Hits the real GitHub API.
  *
  * Cancels rather than fails when `GITHUB_TOKEN` is absent, so a checkout without credentials
  * still gets a green suite — the parsing tests in `GraphQlSpec` cover the logic offline. This
  * one exists to prove the transport works end to end, which no fixture can.
  *
  * Run with: `set -a && . ./.env && set +a && sbt "forge/testOnly *LiveForgeSpec"`
  */
class LiveForgeSpec extends AnyFunSuite with Matchers:

  private def client(): ForgeClient =
    ForgeClient.fromEnv().getOrElse(cancel("GITHUB_TOKEN not set — skipping live API test"))

  test("repoInfo reaches GitHub and reports the default branch"):
    val (branch, issues) = client().repoInfo("jhy", "jsoup").toOption.get
    branch shouldBe "master"
    issues should be > 1000

  test("an Apache project reports zero GitHub issues — it tracks in JIRA"):
    // Recorded as a test because it invalidated the original target-repo recommendation:
    // commons-csv has a full issue history, none of it on GitHub.
    val (_, issues) = client().repoInfo("apache", "commons-csv").toOption.get
    issues shouldBe 0

  test("a batched bundle round-trips against the live API"):
    val shas = Vector(
      "f10c02e12a15ca3d5acf17e732812172acd819ce",
      "ccd90e9cfcc49d7bfba32d8be27c2d73f224fa22"
    )
    val bundles = client().bundleCandidates("jhy", "jsoup", shas).toOption.get
    bundles.size shouldBe 2
    bundles.map(_.sha) shouldBe shas
    bundles.foreach(_.ciState shouldBe Some("SUCCESS"))

  test("a real issue is fetched with its labels"):
    val issue = client().issue("jhy", "jsoup", 2578).toOption.get
    issue.title should include("supplementary")
    issue.labels should contain("bug")

  test("a nonexistent repository is reported, not thrown"):
    client().repoInfo("jhy", "definitely-not-a-real-repo-xyzzy") match
      case Left(ForgeError.NotFound(_))        => succeed
      case Left(ForgeError.GraphQlErrors(_))   => succeed // GitHub reports this as an error node
      case other                               => fail(s"expected a not-found outcome, got $other")

  test("an empty sha list costs no request at all"):
    client().bundleCandidates("jhy", "jsoup", Vector.empty) shouldBe Right(Vector.empty)
