package causeway.forge

import java.time.Instant

final case class IssueRef(
    number: Int,
    title: String,
    state: String,
    labels: Vector[String]
):
  /** Labels the project itself uses to mark defects. Evidence, not proof — an unlabelled issue
    * is neutral, never evidence that the change is not a fix.
    */
  def looksLikeBugLabel: Boolean =
    labels.map(_.toLowerCase).exists { l =>
      l == "bug" || l == "defect" || l == "regression" || l == "crash" ||
      l.contains("data-loss") || l.contains("security")
    }

final case class PullRequestRef(
    number: Int,
    title: String,
    mergedAt: Option[Instant]
)

/** Everything the adjudicator needs about one candidate, retrieved in a batch.
  *
  * `linkedIssues` comes from GitHub's `closingIssuesReferences`, which in practice is **often
  * empty even when an issue plainly exists** — many projects reference issues in the commit
  * message rather than through a closing keyword on the PR. When it is empty, that is
  * `Unknown(NoLinkedIssue)`, and the caller should fall back to the refs parsed out of the
  * commit message by the vcs module. Treating an empty list as "this project has no issues"
  * would be a recall failure dressed up as a finding.
  */
final case class CommitBundle(
    sha: String,
    headline: String,
    message: String,
    additions: Int,
    deletions: Int,
    changedFiles: Option[Int],
    committedDate: Option[Instant],
    pullRequest: Option[PullRequestRef],
    linkedIssues: Vector[IssueRef],
    ciState: Option[String]
)

final case class IssueComment(author: String, body: String, createdAt: Option[Instant])

final case class IssueDetail(
    /** "Issue" or "PullRequest". Both carry symptom evidence, and a squash-merged
      * repository's commit refs point mostly at PRs. */
    kind: String,
    number: Int,
    title: String,
    body: String,
    state: String,
    labels: Vector[String],
    author: Option[String],
    createdAt: Option[Instant],
    closedAt: Option[Instant],
    commentCount: Int,
    participantCount: Int,
    comments: Vector[IssueComment]
):
  /** A stack trace in the issue text is the strongest symptom evidence available, and it is what
    * makes deterministic crash reproduction (Botsing, rung 1) possible at all. Detected here so
    * the pipeline can route on it rather than an agent having to eyeball every issue.
    */
  def hasStackTrace: Boolean =
    val text = s"$title\n$body\n${comments.map(_.body).mkString("\n")}"
    "(?m)^\\s*at [\\w$.]+\\([\\w.]+:\\d+\\)".r.findFirstIn(text).isDefined ||
      "\\b[\\w.]*Exception\\b[\\s\\S]{0,200}?\\n\\s*at ".r.findFirstIn(text).isDefined

/** Why a forge call could not answer.
  *
  * `RateLimited` is a first-class outcome rather than an exception: a throttled run must degrade
  * into recorded Unknowns, not collapse.
  */
enum ForgeError:
  case RateLimited(detail: String)
  case HttpFailure(status: Int, body: String)
  case GraphQlErrors(messages: Vector[String])
  case NotFound(what: String)
  case Malformed(detail: String)
  case Network(detail: String)

  def explain: String = this match
    case RateLimited(d)     => s"GitHub rate limit reached: $d"
    case HttpFailure(s, b)  => s"HTTP $s: ${b.take(200)}"
    case GraphQlErrors(ms)  => s"GraphQL errors: ${ms.mkString("; ")}"
    case NotFound(w)        => s"not found: $w"
    case Malformed(d)       => s"unexpected response shape: $d"
    case Network(d)         => s"network failure: $d"
