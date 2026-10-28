package causeway.mini

import java.time.Instant
import scala.collection.mutable
import Json.*

/** Finds the pull requests and issues behind each commit. */
object Enrich:
  private val Closing = """(?i)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)[:\s]+([\w.-]+/[\w.-]+)?#(\d+)\b""".r
  // Not preceded by a word char, '/' or '&', so `owner/repo#1`, `page#1` and `&#39;` are not matched.
  private val AnyRef = """(?<![\w/&])#(\d+)\b""".r
  private val MaxBody = 4000

  /** Issue numbers a text claims to close, in this repository only. */
  def closingRefs(text: String, repoFullName: String): List[Int] =
    Closing.findAllMatchIn(text).toList
      .filter(m => Option(m.group(1)).forall(_.equalsIgnoreCase(repoFullName)))
      .map(_.group(2).toInt).distinct

  /** Every bare `#123` in a text. */
  def mentionedRefs(text: String): List[Int] =
    AnyRef.findAllMatchIn(text).map(_.group(1).toInt).toList.distinct

  private def linkFrom(item: ujson.Value, kind: String, how: String): LinkRow = LinkRow(
    kind, item("number").num.toInt, item.text("title").getOrElse(""),
    item.text("body").getOrElse("").take(MaxBody),
    // A merged PR reports state "closed"; "merged" is the more useful word.
    if item.opt("merged_at").isDefined then "merged" else item.text("state").getOrElse("unknown"),
    item.text("html_url").getOrElse(""), how)

  def run(gh: GitHub, db: Db, runId: String, now: Instant): ujson.Value =
    val run = db.run(runId)
    val repo = run.fullName
    // GitHub numbers issues and PRs from one sequence, so a number is enough to key a lookup.
    val lookups = mutable.Map.empty[Int, Option[LinkRow]]
    def lookup(n: Int, how: String): Option[LinkRow] =
      lookups.getOrElseUpdate(n,
        try
          val item = gh.get(s"/repos/$repo/issues/$n")
          Some(linkFrom(item, if item.opt("pull_request").isDefined then "pr" else "issue", how))
        catch case e: GitHubError if e.status == 404 => None
      ).map(_.copy(how = how))

    val errors = List.newBuilder[ujson.Value]
    var linked = 0
    val commits = db.commits(runId)
    commits.foreach { c =>
      try
        // Strongest evidence first; `putIfAbsent` keeps the first reason a number was reached by.
        val found = mutable.LinkedHashMap.empty[Int, LinkRow]
        val prs = gh.get(s"/repos/$repo/commits/${c.sha}/pulls").arr.toList
        prs.foreach(p => found.getOrElseUpdate(p("number").num.toInt, linkFrom(p, "pr", "github_pr")))
        def add(numbers: List[Int], how: String): Unit =
          numbers.filterNot(found.contains).foreach(n => lookup(n, how).foreach(found.update(n, _)))
        add(closingRefs(c.message, repo), "closing_ref")
        prs.foreach(p => add(closingRefs(p.text("title").getOrElse("") + "\n" + p.text("body").getOrElse(""), repo), "pr_closing_ref"))
        add(mentionedRefs(c.message), "message_ref")
        db.replaceLinks(runId, c.sha, found.values.toList)
        if found.nonEmpty then linked += 1
      catch case e: GitHubError =>
        errors += ujson.Obj("sha" -> c.sha, "error" -> e.getMessage)
    }
    // A run where every call failed is not "enriched"; leave it unmarked so the caller sees it.
    val errs = errors.result()
    if errs.size < commits.size || commits.isEmpty then db.markEnriched(runId, now.toString)
    ujson.Obj("run" -> runId, "commits" -> commits.size, "commits_with_links" -> linked,
      "errors" -> errs.size, "error_samples" -> Json.arr(errs.take(3)))
