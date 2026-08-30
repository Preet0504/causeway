package causeway.vcs

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class SzzSpec extends AnyFunSuite with Matchers:

  test("contiguous line numbers collapse into runs"):
    Szz.runs(Vector.empty) shouldBe Vector.empty
    Szz.runs(Vector(5)) shouldBe Vector((5, 5))
    Szz.runs(Vector(1, 2, 3)) shouldBe Vector((1, 3))
    Szz.runs(Vector(1, 2, 5, 6, 7, 10)) shouldBe Vector((1, 2), (5, 7), (10, 10))
    Szz.runs(Vector(3, 1, 2)) shouldBe Vector((1, 3))

  // The fix REPLACES the faulty line, so blame has something to work backwards from.
  test("a fix that deletes a line blames the commit that wrote it"):
    val (r, parent, fix) = TestRepo.configLoaderBug()
    try
      val res = Szz.inducingCommits(r.service, fix).toOption.get
      res.blameless shouldBe false
      res.candidates.map(_.sha) shouldBe Vector(parent)
      res.candidates.head.viaLines should contain(42)
      res.filtersApplied should contain("ignore-whitespace")
    finally r.close()

  // Assumption A6, and the measured 14%: a guard-only fix deletes nothing, so blame has no line
  // to start from. This is the method failing to see an origin, NOT the bug lacking one.
  test("a guard-only fix is blameless — SZZ cannot see its origin at all"):
    val (r, _, fix) = TestRepo.guardOnlyFix()
    try
      val res = Szz.inducingCommits(r.service, fix).toOption.get
      res.deletedLineCount shouldBe 0
      res.blameless shouldBe true
      res.candidates shouldBe empty
    finally r.close()

  test("R-SZZ selects only the most recent candidate"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "one\ntwo\nthree\nfour\n")
      r.commit("first author writes everything")

      r.write("a.txt", "one\nTWO-EDITED\nthree\nfour\n")
      val second = r.commit("second commit rewrites line 2")

      // A fix deleting lines 2 and 3 blames both commits; R-SZZ keeps the newer.
      r.write("a.txt", "one\nfour\n")
      val fix = r.commit("Fix by removing lines 2 and 3")

      val rszz = Szz.inducingCommits(r.service, fix, SzzVariant.RSzz).toOption.get
      rszz.candidates.size shouldBe 1
      rszz.candidates.head.sha shouldBe second
      rszz.candidates.head.confidence shouldBe 1.0
      rszz.filtersApplied should contain("most-recent-only")

      val bszz = Szz.inducingCommits(r.service, fix, SzzVariant.BSzz).toOption.get
      bszz.candidates.size should be >= 2
      // B-SZZ shares confidence across candidates rather than asserting one.
      bszz.candidates.map(_.confidence).sum shouldBe 1.0 +- 0.001
    finally r.close()

  test("B-SZZ returns candidates newest-first"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "one\ntwo\n")
      r.commit("c1")
      r.write("a.txt", "one\nTWO\n")
      r.commit("c2")
      r.write("a.txt", "\n")
      val fix = r.commit("Fix by deleting both")

      val res = Szz.inducingCommits(r.service, fix, SzzVariant.BSzz).toOption.get
      val dates = res.candidates.map(_.date.toEpochMilli)
      dates shouldBe dates.sorted.reverse
    finally r.close()

  // The original paper's meta-change filter: a commit made after the bug was already reported
  // cannot have caused it.
  test("commits made after the bug was reported are excluded"):
    val (r, parent, fix) = TestRepo.configLoaderBug()
    try
      val parentDate = r.service.commitMeta(parent).toOption.get.commitDate

      val before = Szz.inducingCommits(r.service, fix,
        issueDate = Some(parentDate.plusSeconds(60))).toOption.get
      before.candidates.map(_.sha) shouldBe Vector(parent)
      before.filtersApplied should contain("exclude-commits-after-report")

      // Reported before the culprit was even written: nothing survives the filter.
      val after = Szz.inducingCommits(r.service, fix,
        issueDate = Some(parentDate.minusSeconds(3600))).toOption.get
      after.candidates shouldBe empty
    finally r.close()

  test("omitting the issue date skips that filter rather than guessing one"):
    val (r, _, fix) = TestRepo.configLoaderBug()
    try
      val res = Szz.inducingCommits(r.service, fix, issueDate = None).toOption.get
      res.filtersApplied should not contain "exclude-commits-after-report"
      res.candidates should not be empty
    finally r.close()

  // Blaming a reformat attributes the fault to whoever last touched the whitespace.
  test("cosmetic deletions are not blamed"):
    val r = TestRepo.create()
    try
      r.write("a.java", "class A {\n    int x = 1;\n}\n")
      r.commit("write class")

      // Delete only a closing brace and a blank line — nothing substantive.
      r.write("a.java", "class A {\n    int x = 1;\n")
      val fix = r.commit("Fix: drop trailing brace")

      val res = Szz.inducingCommits(r.service, fix).toOption.get
      res.deletedLineCount shouldBe 0
      res.blameless shouldBe true
    finally r.close()

  test("the fix commit never blames itself"):
    val (r, _, fix) = TestRepo.configLoaderBug()
    try
      Szz.inducingCommits(r.service, fix).toOption.get.candidates.map(_.sha) should not contain fix
    finally r.close()

  test("a root commit has no parent and is reported as such"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "one\n")
      val root = r.commit("initial")
      Szz.inducingCommits(r.service, root) match
        case Left(msg) => msg should include("root commit")
        case Right(_)  => fail("a root commit cannot have an inducing commit")
    finally r.close()

  test("confidence reflects how many removed lines a candidate accounts for"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1\n2\n3\n4\n5\n6\n")
      r.commit("author of most lines")
      r.write("a.txt", "1\n2\n3\n4\nFIVE\n6\n")
      val minor = r.commit("author of one line")
      r.write("a.txt", "6\n")
      val fix = r.commit("Fix by deleting lines 1-5")

      val res = Szz.inducingCommits(r.service, fix, SzzVariant.BSzz).toOption.get
      val minorC = res.candidates.find(_.sha == minor).get
      val majorC = res.candidates.find(_.sha != minor).get
      majorC.confidence should be > minorC.confidence
    finally r.close()
