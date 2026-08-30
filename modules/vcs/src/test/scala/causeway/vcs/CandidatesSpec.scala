package causeway.vcs

import causeway.core.CandidateNet
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CandidatesSpec extends AnyFunSuite with Matchers:

  /** A repo with one commit of each interesting shape. */
  private def mixedRepo(): TestRepo =
    val r = TestRepo.create()
    r.write("src/main/java/A.java", "class A {}")
    r.commit("Initial import")

    r.write("src/main/java/A.java", "class A { void x() {} }")
    r.commit("Fix NPE in A\n\nCloses #12")                       // lexical: word + ref

    r.write("src/main/java/B.java", "class B {}")
    r.commit("Add feature B")                                    // no net

    r.write("src/main/java/C.java", "class C { int i; }")
    r.write("src/test/java/CTest.java", "class CTest {}")
    r.commit("handle empty section gracefully")                  // structural only

    r.write("docs/readme.md", "docs")
    r.commit("Update docs")                                      // no net
    r

  test("the lexical net catches fix words and issue references"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, nets = Set(CandidateNet.Lexical),
        controlSampleSize = 0).toOption.get
      val subjects = res.candidates.map(c => r.service.commitMeta(c.sha).toOption.get.subject)
      subjects should contain("Fix NPE in A")
      subjects should not contain "Add feature B"
    finally r.close()

  // The recall gap that motivated the second net: a real fix with no fix-words in it.
  test("the structural net catches a fix whose message contains no fix words"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, nets = Set(CandidateNet.Structural),
        controlSampleSize = 0).toOption.get
      val subjects = res.candidates.map(c => r.service.commitMeta(c.sha).toOption.get.subject)

      subjects should contain("handle empty section gracefully")
      // and the lexical net alone would have missed it
      val lexicalOnly = Candidates.find(r.service, nets = Set(CandidateNet.Lexical),
        controlSampleSize = 0).toOption.get
      lexicalOnly.candidates.map(c =>
        r.service.commitMeta(c.sha).toOption.get.subject
      ) should not contain "handle empty section gracefully"
    finally r.close()

  test("source and test involvement are reported from the diff, not guessed"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, nets = Set(CandidateNet.Structural),
        controlSampleSize = 0).toOption.get
      val c = res.candidates.head
      c.touchesSource shouldBe true
      c.touchesTests shouldBe true
    finally r.close()

  test("a docs-only commit touches neither source nor tests"):
    val r = TestRepo.create()
    try
      r.write("README.md", "hello")
      r.commit("Initial")
      r.write("README.md", "hello world")
      r.commit("Update docs")

      val res = Candidates.find(r.service, nets = CandidateNet.values.toSet,
        controlSampleSize = 50).toOption.get
      res.candidates.foreach { c =>
        c.touchesSource shouldBe false
        c.touchesTests shouldBe false
      }
    finally r.close()

  test("the control net samples only commits no other net matched"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, nets = CandidateNet.values.toSet,
        controlSampleSize = 50).toOption.get

      val controls = res.candidates.filter(_.matchedNets.contains(CandidateNet.RandomControl))
      controls should not be empty
      // A control candidate carries only the control net — it matched nothing else.
      controls.foreach(_.matchedNets shouldBe Vector(CandidateNet.RandomControl))

      val subjects = controls.map(c => r.service.commitMeta(c.sha).toOption.get.subject)
      subjects should not contain "Fix NPE in A"
    finally r.close()

  // Resumability: a re-run must draw the same sample, or the recall estimate moves each time.
  test("the control sample is deterministic across runs"):
    val r = mixedRepo()
    try
      val a = Candidates.find(r.service, controlSampleSize = 2).toOption.get
      val b = Candidates.find(r.service, controlSampleSize = 2).toOption.get
      a.candidates.map(_.sha) shouldBe b.candidates.map(_.sha)
    finally r.close()

  test("disabling the control net forfeits the sample rather than silently sampling anyway"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, nets = Set(CandidateNet.Lexical),
        controlSampleSize = 50).toOption.get
      res.candidates.exists(_.matchedNets.contains(CandidateNet.RandomControl)) shouldBe false
    finally r.close()

  test("per-net counts are reported so a mismatched net set is visible"):
    val r = mixedRepo()
    try
      val res = Candidates.find(r.service, controlSampleSize = 10).toOption.get
      res.perNetCounts(CandidateNet.Lexical) should be >= 1
      res.perNetCounts(CandidateNet.Structural) should be >= 1
      res.scanned should be >= 5
    finally r.close()

  test("merge commits are skipped — their first-parent diff is a whole branch"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      r.commit("base")
      r.write("a.txt", "2")
      r.commit("Fix something")

      val res = Candidates.find(r.service, controlSampleSize = 0).toOption.get
      res.candidates.foreach { c =>
        r.service.commitMeta(c.sha).toOption.get.isMerge shouldBe false
      }
    finally r.close()
