package causeway.vcs

import causeway.core.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class GitServiceSpec extends AnyFunSuite with Matchers:

  test("history is walked newest-first, and truncation is reported not hidden"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val c1 = r.commit("first")
      r.write("a.txt", "2")
      val c2 = r.commit("second")
      r.write("a.txt", "3")
      val c3 = r.commit("third")

      val (all, truncated) = r.service.listCommits(maxCount = 10).toOption.get
      all.map(_.sha) shouldBe Vector(c3, c2, c1)
      truncated shouldBe false

      val (capped, wasTruncated) = r.service.listCommits(maxCount = 2).toOption.get
      capped.size shouldBe 2
      wasTruncated shouldBe true
    finally r.close()

  test("commit metadata is read, not inferred"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val sha = r.commit("Fix the thing\n\nCloses #412")
      val info = r.service.commitMeta(sha).toOption.get

      info.sha shouldBe sha
      info.author shouldBe "Test Author"
      info.subject shouldBe "Fix the thing"
      info.parents shouldBe empty
      info.isMerge shouldBe false
    finally r.close()

  // Recall matters here: a project writing CSV-118 is not a project without linked issues.
  test("issue references are recognised across the conventions projects actually use"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val sha = r.commit("Fix parser\n\nCloses #412, GH-7 and CSV-118")
      val refs = r.service.commitMeta(sha).toOption.get.issueRefs

      refs should contain("#412")
      refs should contain("GH-7")
      refs should contain("CSV-118")
    finally r.close()

  test("a commit with no issue reference simply has none — that is neutral, not an error"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val sha = r.commit("handle empty section gracefully")
      r.service.commitMeta(sha).toOption.get.issueRefs shouldBe empty
    finally r.close()

  // The property the Old/New split exists to protect, checked against a real diff.
  test("a fix replacing one line with four reports old 42..42 and new 42..45"):
    val (r, _, fix) = TestRepo.configLoaderBug()
    try
      val d = r.service.diff(fix).toOption.get
      d.filesTouched shouldBe 1
      d.files.head.file shouldBe "src/main/java/cfg/ConfigLoader.java"

      val hunk = d.allHunks.head
      hunk.oldRange shouldBe Some(OldLines(42, 42))
      hunk.newRange shouldBe Some(NewLines(42, 45))

      hunk.deletedLines.head.trim shouldBe "return s.lookup(key);"
      hunk.addedLines.head.trim shouldBe "if (s == null) {"
    finally r.close()

  test("added and deleted line counts come from the diff, not from a guess"):
    val (r, _, fix) = TestRepo.configLoaderBug()
    try
      val d = r.service.diff(fix).toOption.get
      d.linesDeleted shouldBe 1
      d.linesAdded shouldBe 4
    finally r.close()

  // Assumption A6, made concrete. A fix that only ADDS a guard deletes nothing, so blame-based
  // SZZ has no line to work backwards from. Recording this as a real, expected shape rather
  // than letting it surface later as a mysterious empty SZZ result.
  test("a guard-only fix deletes nothing, so SZZ will have no line to blame"):
    val (r, _, fix) = TestRepo.guardOnlyFix()
    try
      val d = r.service.diff(fix).toOption.get
      d.linesDeleted shouldBe 0
      d.linesAdded shouldBe 3

      val hunk = d.allHunks.head
      hunk.oldRange shouldBe None
      hunk.deletedLines shouldBe empty
      hunk.newRange shouldBe Some(NewLines(42, 44))
    finally r.close()

  test("a pure insertion has no old range, and a pure deletion has no new range"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "line1\nline2\n")
      r.commit("base")
      r.write("a.txt", "line1\ninserted\nline2\n")
      val ins = r.commit("insert")

      val hunk = r.service.diff(ins).toOption.get.allHunks.head
      hunk.oldRange shouldBe None
      hunk.newRange shouldBe defined

      r.write("a.txt", "line1\n")
      val del = r.commit("delete")
      val delHunk = r.service.diff(del).toOption.get.allHunks.head
      delHunk.oldRange shouldBe defined
      delHunk.newRange shouldBe None
    finally r.close()

  // Without rename detection this reads as delete-all + add-all, which destroys blame lineage
  // and makes SZZ blame the rename instead of the fault.
  test("a rename is detected as a rename, not as a delete plus an add"):
    val r = TestRepo.create()
    try
      val body = (1 to 30).map(i => s"line $i").mkString("\n")
      r.write("old/Name.java", body)
      r.commit("add")

      r.move("old/Name.java", "new/Name.java")
      val renamed = r.commit("move it")

      val d = r.service.diff(renamed).toOption.get
      d.files.size shouldBe 1
      d.files.head.status shouldBe HunkStatus.Renamed
      d.files.head.oldPath shouldBe Some("old/Name.java")
      d.files.head.file shouldBe "new/Name.java"
    finally r.close()

  test("file contents are read at the requested revision, not at HEAD"):
    val (r, parent, fix) = TestRepo.configLoaderBug()
    try
      val atParent = r.service.fileAt(parent, "src/main/java/cfg/ConfigLoader.java").toOption.get
      val atFix    = r.service.fileAt(fix, "src/main/java/cfg/ConfigLoader.java").toOption.get

      atParent should not include "if (s == null)"
      atFix should include("if (s == null)")
    finally r.close()

  test("a missing file at a revision is an error, not an empty string"):
    val (r, parent, _) = TestRepo.configLoaderBug()
    try r.service.fileAt(parent, "does/not/Exist.java").isLeft shouldBe true
    finally r.close()

  test("blame attributes the faulty line to the commit that wrote it"):
    val (r, parent, _) = TestRepo.configLoaderBug()
    try
      val lines = r.service.blame(parent, "src/main/java/cfg/ConfigLoader.java", OldLines(42, 42))
        .toOption.get
      lines.size shouldBe 1
      lines.head.line shouldBe 42
      lines.head.commit shouldBe parent
      lines.head.author shouldBe "Test Author"
    finally r.close()

  test("blame ignores whitespace-only reformatting"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "alpha\nbeta\ngamma\n")
      val original = r.commit("write content")

      // A reformat that changes only leading whitespace must not become the author.
      r.write("a.txt", "    alpha\n    beta\n    gamma\n")
      val reformat = r.commit("reformat everything")

      val lines = r.service.blame(reformat, "a.txt", OldLines(1, 3)).toOption.get
      lines.map(_.commit).distinct shouldBe Vector(original)
    finally r.close()

  test("an unresolvable revision is reported rather than silently returning nothing"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      r.commit("first")
      r.service.resolve("no-such-branch").isLeft shouldBe true
      r.service.commitMeta("f" * 40).isLeft shouldBe true
    finally r.close()

  // Caught by driving the real server against a real repository: a root commit has no parent,
  // and passing null as the old tree made JGit throw, which the surrounding Try turned into
  // "no files changed". The first commit of every repository looked empty.
  test("a root commit diffs against the empty tree, not against nothing"):
    val r = TestRepo.create()
    try
      r.write("src/main/java/A.java", "class A {}")
      val root = r.commit("Add A")

      val d = r.service.diff(root).toOption.get
      d.filesTouched shouldBe 1
      d.files.head.file shouldBe "src/main/java/A.java"
      d.files.head.status shouldBe HunkStatus.Added
      d.linesAdded should be > 0
    finally r.close()

  test("the recall nets see a root commit's files"):
    val r = TestRepo.create()
    try
      r.write("src/main/java/A.java", "class A {}")
      r.write("src/test/java/ATest.java", "class ATest {}")
      r.commit("initial import with tests")

      val res = Candidates.find(r.service, controlSampleSize = 0).toOption.get
      // Source and test in one commit is the structural net's signal; before the fix this
      // reported false for both and the commit was invisible to it.
      res.candidates.headOption.map(c => (c.touchesSource, c.touchesTests)) shouldBe Some((true, true))
    finally r.close()

  // Found on a real repository, not in a fixture: a jsoup commit about character escaping was
  // reported as referencing issue "ISO-8859". The JIRA pattern cannot tell CSV-118 from
  // ISO-8859 on shape alone.
  test("standards and encodings are not mistaken for issue keys"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val sha = r.commit("Escape supplementary characters for non-UTF charsets such as ISO-8859-1")
      val refs = r.service.commitMeta(sha).toOption.get.issueRefs
      refs should not contain "ISO-8859"
      refs shouldBe empty
    finally r.close()

  test("a genuine project key is still recognised alongside a standard"):
    val r = TestRepo.create()
    try
      r.write("a.txt", "1")
      val sha = r.commit("Fix ISO-8859-1 handling. Closes CSV-118 and #42")
      val refs = r.service.commitMeta(sha).toOption.get.issueRefs
      refs should contain("CSV-118")
      refs should contain("#42")
      refs should not contain "ISO-8859"
    finally r.close()

  // `until` was accepted by the schema and dropped by the handler for the whole of this
  // project's life so far. An ignored upper bound does not fail — it returns MORE history than
  // was asked for, which reads as a complete answer.
  test("a date window bounds the walk from both ends"):
    val r = TestRepo.create()
    try
      // TestRepo stamps commit N at 2024-01-01T00:00:00Z + N hours.
      r.write("a.txt", "1"); val c1 = r.commit("first")   // 01:00
      r.write("a.txt", "2"); val c2 = r.commit("second")  // 02:00
      r.write("a.txt", "3"); val c3 = r.commit("third")   // 03:00

      def window(since: Option[String], until: Option[String]) =
        r.service.listCommits(
          since = since.map(java.time.Instant.parse),
          until = until.map(java.time.Instant.parse)
        ).toOption.get._1.map(_.sha)

      window(None, Some("2024-01-01T02:00:00Z")) shouldBe Vector(c2, c1)
      window(Some("2024-01-01T02:00:00Z"), None) shouldBe Vector(c3, c2)
      window(Some("2024-01-01T02:00:00Z"), Some("2024-01-01T02:00:00Z")) shouldBe Vector(c2)

      // Both bounds are INCLUSIVE, and an empty window is empty rather than unbounded.
      window(Some("2024-01-01T09:00:00Z"), None) shouldBe empty
      window(None, Some("2023-01-01T00:00:00Z")) shouldBe empty
    finally r.close()

  test("a fetch on a repository with no remote fails as a value, not an exception"):
    val r = TestRepo.create()
    try
      // A stale-but-usable clone still answers every historical question, so this must be a
      // reportable outcome rather than something that takes the whole call down.
      r.service.fetch().isLeft shouldBe true
    finally r.close()

  test("a path filter keeps only commits that TOUCHED those paths"):
    val r = TestRepo.create()
    try
      r.write("src/a.txt", "1");  val c1 = r.commit("touch a")
      r.write("docs/b.txt", "1"); val c2 = r.commit("touch b")
      r.write("src/a.txt", "2");  val c3 = r.commit("touch a again")

      def walk(paths: Vector[String]) =
        r.service.listCommits(paths = paths).toOption.get._1.map(_.sha)

      walk(Vector("src")) shouldBe Vector(c3, c1)
      walk(Vector("docs")) shouldBe Vector(c2)
      walk(Vector("src", "docs")) shouldBe Vector(c3, c2, c1)

      // No filter means no filtering — an empty vector is not an empty result set.
      walk(Vector.empty) shouldBe Vector(c3, c2, c1)

      // A path nothing ever touched yields nothing, rather than everything.
      walk(Vector("nope")) shouldBe empty
    finally r.close()

  // Without ANY_DIFF alongside the path group, JGit keeps every commit whose TREE contains the
  // path — which is nearly all of them once the file exists, so the filter would look applied
  // and do almost nothing.
  test("the path filter excludes commits that merely contain the path"):
    val r = TestRepo.create()
    try
      r.write("src/a.txt", "1"); val c1 = r.commit("create a")
      r.write("other.txt", "x"); r.commit("unrelated, but src/a.txt still exists")

      r.service.listCommits(paths = Vector("src/a.txt")).toOption.get._1.map(_.sha) shouldBe
        Vector(c1)
    finally r.close()
