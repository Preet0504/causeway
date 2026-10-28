package causeway.mini

class ClassifySpec extends munit.FunSuite:
  private def sig(s: Double) = Signal(s, "because")

  test("combined score is the weighted mean when all three signals exist"):
    val s = Scored("a" * 40, sig(1.0), sig(0.5), Some(sig(0.0)))
    assertEqualsDouble(Classify.combined(s), 0.3 * 1.0 + 0.5 * 0.5 + 0.2 * 0.0, 1e-9)

  test("without a linked signal the remaining weights are rescaled, not penalised"):
    val s = Scored("a" * 40, sig(0.8), sig(0.8), None)
    assertEqualsDouble(Classify.combined(s), 0.8, 1e-9)
    assert(Classify.isBugFix(s))

  test("threshold decides the verdict"):
    assert(Classify.isBugFix(Scored("a" * 40, sig(0.6), sig(0.6), None)))
    assert(!Classify.isBugFix(Scored("a" * 40, sig(0.59), sig(0.59), None)))

  private val sha1 = "1" * 40
  private val sha2 = "2" * 40
  private val known = Map(sha1 -> false, sha2 -> true)

  private def entry(sha: String, linked: Option[String] = None) =
    s"""{"sha":"$sha","message":{"score":0.9,"explanation":"says fix"},
       |"diff":{"score":0.8,"explanation":"adds null check"},
       |"linked":${linked.getOrElse("null")}}""".stripMargin

  private val linkedOk = """{"score":0.7,"explanation":"issue is a bug report"}"""

  test("accepts a valid reply, including a short sha and a code fence"):
    val reply = s"```json\n[${entry("1111111")}, ${entry(sha2, Some(linkedOk))}]\n```"
    val (ok, bad) = Classify.validate(reply, known).toOption.get
    assertEquals(ok.map(_.sha), List(sha1, sha2))
    assertEquals(bad, Nil)
    assertEquals(ok(1).linked.map(_.score), Some(0.7))

  test("accepts an object wrapping the array"):
    val (ok, _) = Classify.validate(s"""{"results":[${entry(sha1)}]}""", known).toOption.get
    assertEquals(ok.size, 1)

  test("rejects a score outside 0..1, a missing explanation, and an unknown sha, but keeps the valid ones"):
    val outOfRange = entry(sha1).replace("0.9", "1.7")
    val noExplanation = entry(sha2, Some("""{"score":0.5,"explanation":""}"""))
    val unknown = entry("f" * 40)
    val (ok, bad) = Classify.validate(s"[${entry(sha1)}, $outOfRange, $noExplanation, $unknown]", known).toOption.get
    assertEquals(ok.map(_.sha), List(sha1))
    assertEquals(bad.size, 3)

  test("a commit with linked PRs/issues must carry a linked signal"):
    val (ok, bad) = Classify.validate(s"[${entry(sha2)}]", known).toOption.get
    assertEquals(ok, Nil)
    assert(bad.head.reason.contains("'linked' is required"))

  test("a linked signal for a commit with no links is ignored rather than stored"):
    val (ok, _) = Classify.validate(s"[${entry(sha1, Some(linkedOk))}]", known).toOption.get
    assertEquals(ok.head.linked, None)

  test("the same sha twice is rejected the second time"):
    val (ok, bad) = Classify.validate(s"[${entry(sha1)}, ${entry(sha1)}]", known).toOption.get
    assertEquals(ok.size, 1)
    assertEquals(bad.size, 1)

  test("a prefix shared by two commits is ambiguous"):
    val (ok, bad) = Classify.validate(s"[${entry("1111111")}]", Map("1111111aaaa" -> false, "1111111bbbb" -> false)).toOption.get
    assertEquals(ok, Nil)
    assert(bad.head.reason.contains("ambiguous"))

  test("non-JSON reply is an error, not a crash"):
    assert(Classify.validate("I think commit 1 is a bug fix", known).isLeft)
