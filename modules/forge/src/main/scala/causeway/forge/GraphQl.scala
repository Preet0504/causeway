package causeway.forge

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Query construction and response parsing.
  *
  * Deliberately free of I/O so every parser is tested against recorded responses from the real
  * API rather than against a guess at their shape — the fixtures in `fixtures/forge/` were
  * captured from github.com, not written by hand.
  */
object GraphQl:

  private[forge] val mapper = JsonMapper()

  private val commitFragment =
    """fragment B on Commit{
      |oid messageHeadline message additions deletions changedFilesIfAvailable committedDate
      |associatedPullRequests(first:1){nodes{number title mergedAt
      |  closingIssuesReferences(first:5){nodes{number title state labels(first:10){nodes{name}}}}}}
      |statusCheckRollup{state}}""".stripMargin.replace("\n", " ")

  /** One query for many commits, via GraphQL aliases.
    *
    * This is what makes parallel adjudication affordable: 15 candidates cost one round trip
    * instead of ninety REST calls, and the adjudicator agent arrives with its evidence already
    * in hand rather than spending its own budget fetching it.
    */
  def bundleQuery(owner: String, name: String, shas: Vector[String]): String =
    val aliases = shas.zipWithIndex
      .map { case (sha, i) => s"""c$i: object(expression:"$sha"){...B}""" }
      .mkString(" ")
    val q = s"""query{repository(owner:"$owner",name:"$name"){$aliases}} $commitFragment"""
    mapper.writeValueAsString(mapper.createObjectNode().put("query", q))

  /** Fetch by number, accepting EITHER an issue or a pull request.
    *
    * Found on a real repository, not in a fixture: jsoup squash-merges, so its commit
    * messages end in `(#2580)` — and #2580 is a **pull request**, not an issue. Querying
    * `issue(number:)` returned "Could not resolve to an Issue", which reads as "this project
    * does not link issues" when in fact the link was fine and the number pointed at a PR.
    *
    * A PR body carries the same symptom evidence an issue body would, so refusing to look
    * at one discards exactly the text symptom characterisation needs. `issueOrPullRequest`
    * resolves both, and the parser records which kind came back.
    */
  def issueQuery(owner: String, name: String, number: Int, comments: Int = 20): String =
    val shared =
      s"""number title body state createdAt author{login}
         |labels(first:20){nodes{name}}
         |comments(first:$comments){totalCount nodes{author{login} body createdAt}}
         |participants{totalCount}""".stripMargin.replace("\n", " ")
    val q =
      s"""query{repository(owner:"$owner",name:"$name"){issueOrPullRequest(number:$number){
         |... on Issue{__typename $shared closedAt}
         |... on PullRequest{__typename $shared closedAt: mergedAt}}}}""".stripMargin.replace("\n", " ")
    mapper.writeValueAsString(mapper.createObjectNode().put("query", q))

  def repoQuery(owner: String, name: String): String =
    val q = s"""query{repository(owner:"$owner",name:"$name"){nameWithOwner defaultBranchRef{name}
               |issues{totalCount}}}""".stripMargin.replace("\n", " ")
    mapper.writeValueAsString(mapper.createObjectNode().put("query", q))

  // ── parsing ────────────────────────────────────────────────────────────

  private def str(n: JsonNode, f: String): Option[String] =
    Option(n).flatMap(x => Option(x.get(f))).filter(_.isTextual).map(_.stringValue())

  private def int(n: JsonNode, f: String): Option[Int] =
    Option(n).flatMap(x => Option(x.get(f))).filter(_.isNumber).map(_.intValue())

  private def instant(n: JsonNode, f: String): Option[Instant] =
    str(n, f).flatMap(s => Try(Instant.parse(s)).toOption)

  private def nodes(n: JsonNode, path: String*): Vector[JsonNode] =
    val target = path.foldLeft(Option(n))((acc, p) => acc.flatMap(x => Option(x.get(p))))
    target.flatMap(t => Option(t.get("nodes"))) match
      case Some(arr) if arr.isArray => arr.values().asScala.toVector
      case _                        => Vector.empty

  private def labelNames(issueNode: JsonNode): Vector[String] =
    nodes(issueNode, "labels").flatMap(l => str(l, "name"))

  /** Top-level error handling, shared by every response.
    *
    * GitHub reports rate limiting inside a 200 response's `errors` array, not as an HTTP status,
    * so it has to be detected here rather than at the transport layer.
    */
  def checkErrors(root: JsonNode): Option[ForgeError] =
    Option(root.get("errors")).filter(_.isArray).map(_.values().asScala.toVector) match
      case Some(errs) if errs.nonEmpty =>
        val messages = errs.flatMap(e => str(e, "message"))
        val types    = errs.flatMap(e => str(e, "type"))
        if types.contains("RATE_LIMITED") || messages.exists(_.toLowerCase.contains("rate limit"))
        then Some(ForgeError.RateLimited(messages.mkString("; ")))
        // "Could not resolve to an X" is an ANSWER — the thing is not there — not a
        // failure of the tool. Classifying it as an error would make an absent issue
        // look like an outage, and send an agent retrying instead of recording a gap.
        else if messages.exists(_.toLowerCase.contains("could not resolve")) then
          Some(ForgeError.NotFound(messages.mkString("; ")))
        else Some(ForgeError.GraphQlErrors(messages))
      case _ => None

  def parseBundle(json: String): Either[ForgeError, Vector[CommitBundle]] =
    Try(mapper.readTree(json)).toEither.left
      .map(t => ForgeError.Malformed(t.getMessage))
      .flatMap { root =>
        checkErrors(root) match
          case Some(e) => Left(e)
          case None =>
            // A JSON null is a NullNode, not a Java null, so Option(...) alone would wrap it in
            // Some and this would silently return an empty bundle list instead of NotFound.
            Option(root.get("data"))
              .flatMap(d => Option(d.get("repository")))
              .filterNot(_.isNull) match
              case None => Left(ForgeError.NotFound("repository"))
              case Some(repo) =>
                // Alias order is not guaranteed by JSON object ordering, so sort by the alias
                // index rather than trusting the map's iteration order.
                val entries = repo
                  .propertyNames().asScala.toVector
                  .filter(_.matches("c\\d+"))
                  .sortBy(_.drop(1).toInt)
                Right(entries.flatMap(k => Option(repo.get(k)).filterNot(_.isNull).map(commit)))
      }

  private def commit(c: JsonNode): CommitBundle =
    val pr = nodes(c, "associatedPullRequests").headOption

    CommitBundle(
      sha = str(c, "oid").getOrElse(""),
      headline = str(c, "messageHeadline").getOrElse(""),
      message = str(c, "message").getOrElse(""),
      additions = int(c, "additions").getOrElse(0),
      deletions = int(c, "deletions").getOrElse(0),
      changedFiles = int(c, "changedFilesIfAvailable"),
      committedDate = instant(c, "committedDate"),
      pullRequest = pr.map(p =>
        PullRequestRef(
          number = int(p, "number").getOrElse(0),
          title = str(p, "title").getOrElse(""),
          mergedAt = instant(p, "mergedAt")
        )
      ),
      linkedIssues = pr.toVector.flatMap(p => nodes(p, "closingIssuesReferences")).map { i =>
        IssueRef(
          number = int(i, "number").getOrElse(0),
          title = str(i, "title").getOrElse(""),
          state = str(i, "state").getOrElse("UNKNOWN"),
          labels = labelNames(i)
        )
      },
      ciState = Option(c.get("statusCheckRollup")).filterNot(_.isNull).flatMap(s => str(s, "state"))
    )

  def parseIssue(json: String): Either[ForgeError, IssueDetail] =
    Try(mapper.readTree(json)).toEither.left
      .map(t => ForgeError.Malformed(t.getMessage))
      .flatMap { root =>
        checkErrors(root) match
          case Some(e) => Left(e)
          case None =>
            Option(root.get("data"))
              .flatMap(d => Option(d.get("repository")))
              .filterNot(_.isNull)
              .flatMap(r => Option(r.get("issueOrPullRequest")))
              .filterNot(_.isNull) match
              case None => Left(ForgeError.NotFound("issue or pull request"))
              case Some(i) =>
                Right(
                  IssueDetail(
                    kind = str(i, "__typename").getOrElse("Issue"),
                    number = int(i, "number").getOrElse(0),
                    title = str(i, "title").getOrElse(""),
                    body = str(i, "body").getOrElse(""),
                    state = str(i, "state").getOrElse("UNKNOWN"),
                    labels = labelNames(i),
                    // A deleted account serialises as null, not as a missing field.
                    author = Option(i.get("author")).filterNot(_.isNull).flatMap(a => str(a, "login")),
                    createdAt = instant(i, "createdAt"),
                    closedAt = instant(i, "closedAt"),
                    commentCount = Option(i.get("comments")).flatMap(c => int(c, "totalCount")).getOrElse(0),
                    participantCount =
                      Option(i.get("participants")).flatMap(p => int(p, "totalCount")).getOrElse(0),
                    comments = nodes(i, "comments").map { c =>
                      IssueComment(
                        author = Option(c.get("author")).filterNot(_.isNull)
                          .flatMap(a => str(a, "login")).getOrElse("(deleted)"),
                        body = str(c, "body").getOrElse(""),
                        createdAt = instant(c, "createdAt")
                      )
                    }
                  )
                )
      }
