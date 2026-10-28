package causeway.mini

import Json.*

final case class Candidate(
    fullName: String, url: String, description: Option[String], language: Option[String],
    stars: Int, forks: Int, sizeKb: Int, pushedAt: Option[String], defaultBranch: Option[String],
    hasIssues: Boolean, archived: Boolean, isFork: Boolean
)

/** A candidate plus what we learned about it; rejection None means it qualifies. */
final case class Checked(candidate: Candidate, closedIssues: Option[Int], mergedPrs: Option[Int], rejection: Option[String])

final case class SearchSpec(
    keywords: Option[String] = None, language: Option[String] = None,
    minStars: Option[Int] = None, maxStars: Option[Int] = None, minForks: Option[Int] = None,
    pushedAfter: Option[String] = None, createdBefore: Option[String] = None,
    minSizeKb: Option[Int] = None, maxSizeKb: Option[Int] = None,
    topics: List[String] = Nil, license: Option[String] = None, maxResults: Int = 10
)

object Discovery:
  private val Word = "^[A-Za-z0-9+#._-]+$".r
  private val Keywords = "^[A-Za-z0-9 ._-]+$".r
  private val DatePattern = "^\\d{4}-\\d{2}-\\d{2}$".r

  def specFrom(a: Args): SearchSpec = SearchSpec(
    keywords = a.get("keywords"), language = a.get("language"),
    minStars = a.int("min-stars"), maxStars = a.int("max-stars"), minForks = a.int("min-forks"),
    pushedAfter = a.get("pushed-after"), createdBefore = a.get("created-before"),
    minSizeKb = a.int("min-size-kb"), maxSizeKb = a.int("max-size-kb"),
    topics = a.get("topics").map(_.split(",").map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil),
    license = a.get("license"), maxResults = a.int("max-results").getOrElse(10))

  private def range(min: Option[Int], max: Option[Int]): Option[String] = (min, max) match
    case (Some(a), Some(b)) => Some(s"$a..$b")
    case (Some(a), None)    => Some(s">=$a")
    case (None, Some(b))    => Some(s"<=$b")
    case _                  => None

  /** Builds GitHub's `q` string. Every value is validated, so a value cannot smuggle in extra qualifiers. */
  def buildQuery(spec: SearchSpec): Either[String, String] =
    def word(name: String, v: String) =
      if Word.matches(v) then Right(v) else Left(s"$name '$v' has characters GitHub qualifiers do not allow")
    def date(name: String, v: String) =
      if DatePattern.matches(v) then Right(v) else Left(s"$name must look like 2025-01-31, got '$v'")

    val parts = List.newBuilder[Either[String, String]]
    spec.keywords.foreach(k =>
      parts += (if Keywords.matches(k) then Right(k) else Left(s"keywords '$k' may only contain letters, digits, spaces, '.', '_', '-'")))
    spec.language.foreach(l => parts += word("language", l).map(v => s"language:$v"))
    range(spec.minStars, spec.maxStars).foreach(r => parts += Right(s"stars:$r"))
    spec.minForks.foreach(n => parts += Right(s"forks:>=$n"))
    spec.pushedAfter.foreach(d => parts += date("pushed-after", d).map(v => s"pushed:>=$v"))
    spec.createdBefore.foreach(d => parts += date("created-before", d).map(v => s"created:<=$v"))
    range(spec.minSizeKb, spec.maxSizeKb).foreach(r => parts += Right(s"size:$r"))
    spec.topics.foreach(t => parts += word("topic", t).map(v => s"topic:$v"))
    spec.license.foreach(l => parts += word("license", l).map(v => s"license:$v"))
    // Forks and archived repos share history with, or no longer track, the project we want to study.
    parts ++= List(Right("fork:false"), Right("archived:false"), Right("is:public"))

    val all = parts.result()
    all.collectFirst { case Left(err) => err } match
      case Some(err) => Left(err)
      case None      => Right(all.collect { case Right(p) => p }.mkString(" "))

  def parseCandidate(item: ujson.Value): Candidate = Candidate(
    fullName = item("full_name").str, url = item("html_url").str,
    description = item.text("description"), language = item.text("language"),
    stars = item.intOr("stargazers_count", 0), forks = item.intOr("forks_count", 0),
    sizeKb = item.intOr("size", 0), pushedAt = item.text("pushed_at"),
    defaultBranch = item.text("default_branch"), hasIssues = item.boolOr("has_issues", true),
    archived = item.boolOr("archived", false), isFork = item.boolOr("fork", false))

  /** A repo with no closed issue and no merged PR can never yield commit-to-issue evidence. */
  def rejection(c: Candidate, closedIssues: Int, mergedPrs: Int): Option[String] =
    if c.archived then Some("archived")
    else if c.isFork then Some("is a fork")
    else if closedIssues + mergedPrs == 0 then Some("no closed issues and no merged pull requests")
    else None

  private def count(gh: GitHub, q: String): Int =
    gh.get("/search/issues", Map("q" -> q, "per_page" -> "1")).intOr("total_count", 0)

  /** Searches, then qualifies candidates one by one until `maxResults` qualify or the page is exhausted. */
  def search(gh: GitHub, spec: SearchSpec): Either[String, (String, List[Checked])] =
    buildQuery(spec).map { q =>
      val pageSize = math.min(100, math.max(spec.maxResults * 2, 10))
      val items = gh.get("/search/repositories",
        Map("q" -> q, "sort" -> "stars", "order" -> "desc", "per_page" -> pageSize.toString))
        .opt("items").map(_.arr.toList).getOrElse(Nil)
      val checked = List.newBuilder[Checked]
      var qualifying = 0
      val it = items.iterator
      while qualifying < spec.maxResults && it.hasNext do
        val c = parseCandidate(it.next())
        val closed = if c.hasIssues then count(gh, s"repo:${c.fullName} is:issue is:closed") else 0
        val merged = count(gh, s"repo:${c.fullName} is:pr is:merged")
        val reason = rejection(c, closed, merged)
        if reason.isEmpty then qualifying += 1
        checked += Checked(c, Some(closed), Some(merged), reason)
      (q, checked.result())
    }

  def toJson(searchId: Long, q: String, checked: List[Checked]): ujson.Value =
    ujson.Obj(
      "search_id" -> searchId, "query" -> q,
      "checked" -> checked.size, "qualifying" -> checked.count(_.rejection.isEmpty),
      "repos" -> Json.arr(checked.map { ch =>
        val c = ch.candidate
        ujson.Obj(
          "full_name" -> c.fullName, "url" -> c.url, "description" -> str(c.description),
          "language" -> str(c.language), "stars" -> c.stars, "forks" -> c.forks, "size_kb" -> c.sizeKb,
          "pushed_at" -> str(c.pushedAt), "closed_issues" -> num(ch.closedIssues),
          "merged_prs" -> num(ch.mergedPrs), "qualifies" -> ch.rejection.isEmpty,
          "rejection_reason" -> str(ch.rejection))
      }))
