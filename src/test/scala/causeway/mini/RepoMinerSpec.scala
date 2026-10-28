package causeway.mini

import java.nio.file.Files
import java.time.Instant

class RepoMinerSpec extends munit.FunSuite:
  test("recognises GitHub URL shapes and rejects the rest"):
    assertEquals(RepoMiner.parseRepoName("https://github.com/sbt/sbt"), Some("sbt/sbt"))
    assertEquals(RepoMiner.parseRepoName("https://github.com/sbt/sbt.git"), Some("sbt/sbt"))
    assertEquals(RepoMiner.parseRepoName("git@github.com:sbt/sbt.git"), Some("sbt/sbt"))
    assertEquals(RepoMiner.parseRepoName("sbt/sbt"), Some("sbt/sbt"))
    assertEquals(RepoMiner.parseRepoName("https://gitlab.com/a/b"), None)
    assertEquals(RepoMiner.parseRepoName("/tmp/some/dir"), None)

  test("reads files and diff of a root commit"):
    val r = TestRepo.create()
    val sha = r.commit(Map("a.txt" -> "hello\n"), "first", 1000)
    val commits = RepoMiner.readCommits(r.dir, sha, None, 10)
    assertEquals(commits.size, 1)
    assertEquals(commits.head.files, List("a.txt"))
    assert(commits.head.diff.contains("+hello"))
    assertEquals(commits.head.authoredAt, Instant.ofEpochSecond(1000).toString)

  test("skips merge commits but keeps both sides' commits"):
    val r = TestRepo.create()
    r.commit(Map("a.txt" -> "1\n"), "base", 1000)
    r.branch("feature")
    r.commit(Map("f.txt" -> "f\n"), "feature work", 2000)
    r.checkout("main")
    r.commit(Map("m.txt" -> "m\n"), "main work", 3000)
    val merge = r.mergeNoFf("feature", 4000)
    val subjects = RepoMiner.readCommits(r.dir, merge, None, 10).map(_.message)
    assertEquals(subjects.toSet, Set("base", "feature work", "main work"))

  test("a backdated commit does not hide the newer history beneath it from a --since window"):
    val r = TestRepo.create()
    r.commit(Map("a" -> "1"), "old", 1000)
    r.commit(Map("a" -> "2"), "recent", 5000)
    val tip = r.commit(Map("a" -> "3"), "backdated, committed last", 500)
    val got = RepoMiner.readCommits(r.dir, tip, Some(Instant.ofEpochSecond(900)), 10).map(_.message)
    assertEquals(got, List("recent", "old"))

  test("limit caps how many commits come back"):
    val r = TestRepo.create()
    (1 to 5).foreach(i => r.commit(Map("a" -> i.toString), s"c$i", 1000L + i))
    val tip = r.git.getRepository.resolve("HEAD").getName
    assertEquals(RepoMiner.readCommits(r.dir, tip, None, 2).map(_.message), List("c5", "c4"))

  test("an oversized diff is truncated and says so"):
    val r = TestRepo.create()
    val sha = r.commit(Map("big.txt" -> ("x" * 50000)), "big", 1000)
    val c = RepoMiner.readCommits(r.dir, sha, None, 1).head
    assert(c.diffTruncated)
    assert(c.diff.length <= RepoMiner.MaxDiffChars)

  test("cloneAt pins an exact older commit, and a later call moves to the new default tip"):
    val origin = TestRepo.create()
    val first = origin.commit(Map("a" -> "1"), "one", 1000)
    origin.commit(Map("a" -> "2"), "two", 2000)
    val dest = Files.createTempDirectory("causeway-clone").resolve("repo")

    val pinned = RepoMiner.cloneAt(origin.dir.toString, dest, None, Some(first))
    assertEquals(pinned.sha, first)
    assertEquals(RepoMiner.readCommits(dest, pinned.sha, None, 10).map(_.message), List("one"))
    assertEquals(Files.readString(dest.resolve("a")), "1")

    val third = origin.commit(Map("a" -> "3"), "three", 3000)
    val moved = RepoMiner.cloneAt(origin.dir.toString, dest, None, None)
    assertEquals(moved.sha, third)
    assertEquals(Files.readString(dest.resolve("a")), "3")

  test("cloneAt reports a missing commit and a missing branch as usage errors"):
    val origin = TestRepo.create()
    origin.commit(Map("a" -> "1"), "one", 1000)
    val dest = Files.createTempDirectory("causeway-clone").resolve("repo")
    intercept[UsageError](RepoMiner.cloneAt(origin.dir.toString, dest, None, Some("deadbeefdeadbeef")))
    intercept[UsageError](RepoMiner.cloneAt(origin.dir.toString, dest, Some("nope"), None))
