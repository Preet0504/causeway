package causeway.core

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class IdentifiersSpec extends AnyFunSuite with Matchers:

  private val fullSha = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"

  test("a full 40-hex sha is accepted and round-trips"):
    CommitId(fullSha).map(_.value) shouldBe Right(fullSha)

  test("an abbreviated sha is refused — ambiguity is not resolvable downstream"):
    CommitId("a1b2c3d").isLeft shouldBe true
    CommitId(fullSha.take(39)).isLeft shouldBe true

  test("uppercase and non-hex are refused"):
    CommitId(fullSha.toUpperCase).isLeft shouldBe true
    CommitId("z" * 40).isLeft shouldBe true

  test("evidence ids and note ids are different types with different prefixes"):
    EvidenceId("ev_1111111111111111").isRight shouldBe true
    EvidenceId("note_1111111111111111").isLeft shouldBe true
    NoteId("note_1111111111111111").isRight shouldBe true
    NoteId("ev_1111111111111111").isLeft shouldBe true

  test("minting is deterministic for identical content"):
    val a = EvidenceId.mint("history_diff", """{"sha":"abc"}""", """{"files":[]}""")
    val b = EvidenceId.mint("history_diff", """{"sha":"abc"}""", """{"files":[]}""")
    a shouldBe b

  test("minting distinguishes tool, arguments, and payload"):
    val base    = EvidenceId.mint("history_diff", """{"sha":"abc"}""", """{"files":[]}""")
    val tool    = EvidenceId.mint("history_blame", """{"sha":"abc"}""", """{"files":[]}""")
    val args    = EvidenceId.mint("history_diff", """{"sha":"xyz"}""", """{"files":[]}""")
    val payload = EvidenceId.mint("history_diff", """{"sha":"abc"}""", """{"files":[1]}""")

    Set(base, tool, args, payload).size shouldBe 4

  test("a minted id satisfies the EvidenceId format"):
    val id = EvidenceId.mint("t", "a", "p")
    EvidenceId(id.value).isRight shouldBe true

  test("run ids are validated"):
    RunId("run_0123456789abcdef").isRight shouldBe true
    RunId("0123456789abcdef").isLeft shouldBe true
    RunId("run_xyz").isLeft shouldBe true
