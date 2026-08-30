package causeway.app

import causeway.core.UnknownReason
import causeway.forge.{ForgeClient, ForgeError}
import causeway.mcp.{HandlerResult, Json, ToolHandler}
import causeway.vcs.Candidates
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.{ArrayNode, ObjectNode}

import scala.jdk.CollectionConverters.*

/** GitHub retrieval. Deterministic (D6) — no judgement lives here.
  *
  * The repository slug is derived from the clone URL rather than asked for, so an agent cannot
  * accidentally query one repository's issues while analysing another's commits.
  */
final class ForgeHandlers(handles: Handles, client: Option[ForgeClient]):

  def all: Vector[ToolHandler] = Vector(
    issue, issueComments, prForCommit, ciStatus, bundleCandidates
  )

  private def str(a: JsonNode, f: String): Option[String] =
    Option(a.get(f)).filter(_.isTextual).map(_.stringValue())

  private def int(a: JsonNode, f: String): Option[Int] =
    Option(a.get(f)).filter(_.isNumber).map(_.intValue())

  private def arr(node: ObjectNode, field: String): ArrayNode =
    val x = Json.mapper.createArrayNode()
    node.set(field, x)
    x

  private def handler(n: String)(f: JsonNode => HandlerResult): ToolHandler = new ToolHandler:
    val name = n
    def handle(args: JsonNode): HandlerResult = f(args)

  private def na(d: String) = HandlerResult.Undetermined(UnknownReason.NotApplicable, d)

  /** Map a forge failure onto the right Unknown reason.
    *
    * Rate limiting in particular must NOT be an error: a throttled run has to degrade into
    * recorded gaps rather than collapse, and `RateLimited` is a citable fact about why a field
    * is missing.
    */
  private def asUnknown(e: ForgeError): HandlerResult = e match
    case ForgeError.RateLimited(d) => HandlerResult.Undetermined(UnknownReason.RateLimited, d)
    case ForgeError.NotFound(w)    => HandlerResult.Undetermined(UnknownReason.NotApplicable, s"not found: $w")
    case other                     => HandlerResult.Undetermined(UnknownReason.ToolUnavailable, other.explain)

  /** owner/name for a registered clone, parsed from its remote URL. */
  private def slugOf(repoHandle: String): Option[(String, String)] =
    handles.remoteUrl(repoHandle).flatMap { url =>
      val cleaned = url.stripSuffix(".git").replace(92.toChar, '/')
      val parts   = cleaned.split('/').filter(_.nonEmpty).toVector
      if parts.size >= 2 then Some((parts(parts.size - 2), parts.last)) else None
    }

  private def withClient(a: JsonNode)(f: (ForgeClient, String, String) => HandlerResult): HandlerResult =
    client match
      case None =>
        HandlerResult.Undetermined(UnknownReason.ToolUnavailable,
          "GITHUB_TOKEN is not set; forge lookups are unavailable")
      case Some(c) =>
        str(a, "repoHandle").flatMap(slugOf) match
          case Some((owner, name)) => f(c, owner, name)
          case None =>
            // Falling back to a guessed repository would silently answer about the wrong
            // project, which is worse than answering nothing.
            na("cannot determine owner/name — pass a repoHandle for a clone with a GitHub remote")

  private def issue: ToolHandler = handler("forge_issue") { a =>
    withClient(a) { (c, owner, name) =>
      int(a, "number") match
        case None => na("number is required")
        case Some(number) =>
          c.issue(owner, name, number) match
            case Left(e) => asUnknown(e)
            case Right(i) =>
              val n = Json.obj()
              n.put("number", i.number)
              n.put("title", i.title)
              n.put("body", i.body)
              n.put("state", i.state)
              val labels = arr(n, "labels")
              i.labels.foreach(labels.add)
              i.author.foreach(n.put("author", _))
              i.createdAt.foreach(d => n.put("createdAt", d.toString))
              i.closedAt.foreach(d => n.put("closedAt", d.toString))
              n.put("commentCount", i.commentCount)
              n.put("participantCount", i.participantCount)
              HandlerResult.Data(n)
    }
  }

  private def issueComments: ToolHandler = handler("forge_issue_comments") { a =>
    withClient(a) { (c, owner, name) =>
      int(a, "number") match
        case None => na("number is required")
        case Some(number) =>
          val limit = int(a, "limit").getOrElse(50)
          c.issue(owner, name, number, limit) match
            case Left(e) => asUnknown(e)
            case Right(i) =>
              val n = Json.obj()
              n.put("number", i.number)
              val cs = arr(n, "comments")
              i.comments.foreach { cm =>
                val o = Json.obj()
                o.put("author", cm.author)
                o.put("body", cm.body)
                cm.createdAt.foreach(d => o.put("createdAt", d.toString))
                cs.add(o)
              }
              n.put("truncated", i.commentCount > i.comments.size)
              HandlerResult.Data(n)
    }
  }

  private def prForCommit: ToolHandler = handler("forge_pr_for_commit") { a =>
    withClient(a) { (c, owner, name) =>
      str(a, "sha") match
        case None => na("sha is required")
        case Some(sha) =>
          c.bundleCandidates(owner, name, Vector(sha)) match
            case Left(e) => asUnknown(e)
            case Right(bundles) =>
              bundles.headOption.flatMap(_.pullRequest) match
                case None => na(s"no pull request associated with $sha")
                case Some(pr) =>
                  val n = Json.obj()
                  n.put("prNumber", pr.number)
                  n.put("title", pr.title)
                  pr.mergedAt.foreach(d => n.put("mergedAt", d.toString))
                  val issues = arr(n, "linkedIssues")
                  bundles.head.linkedIssues.foreach(i => issues.add(i.number))
                  HandlerResult.Data(n)
    }
  }

  private def ciStatus: ToolHandler = handler("forge_ci_status") { a =>
    withClient(a) { (c, owner, name) =>
      str(a, "sha") match
        case None => na("sha is required")
        case Some(sha) =>
          c.bundleCandidates(owner, name, Vector(sha)) match
            case Left(e) => asUnknown(e)
            case Right(bundles) =>
              val n = Json.obj()
              n.put("sha", sha)
              val checks = arr(n, "checks")
              bundles.headOption.flatMap(_.ciState).foreach { state =>
                val o = Json.obj()
                o.put("name", "statusCheckRollup")
                o.put("conclusion", state)
                checks.add(o)
              }
              HandlerResult.Data(n)
    }
  }

  /** One round trip for many candidates.
    *
    * `touchesSource` and `touchesTests` are computed from the LOCAL diff, not from GitHub —
    * the API reports a changed-file count but not the paths, and reporting `false` because we
    * did not look would be a claim rather than a gap.
    */
  private def bundleCandidates: ToolHandler = handler("forge_bundle_candidates") { a =>
    withClient(a) { (c, owner, name) =>
      val shas = Option(a.get("shas")).filter(_.isArray)
        .map(_.values().asScala.toVector.map(_.stringValue())).getOrElse(Vector.empty)
      val git = str(a, "repoHandle").flatMap(handles.git)

      if shas.isEmpty then na("shas is required")
      else
        c.bundleCandidates(owner, name, shas) match
          case Left(e) => asUnknown(e)
          case Right(bundles) =>
            val n = Json.obj()
            val list = arr(n, "bundles")
            bundles.foreach { b =>
              val o = Json.obj()
              o.put("sha", b.sha)
              o.put("subject", b.headline)
              o.put("message", b.message)
              o.put("linesAdded", b.additions)
              o.put("linesDeleted", b.deletions)
              o.put("filesTouched", b.changedFiles.getOrElse(0))

              val files = git.flatMap(_.diff(b.sha).toOption)
                .map(_.files.map(_.file)).getOrElse(Vector.empty)
              o.put("touchesSource", files.exists(Candidates.isSourceFile))
              o.put("touchesTests", files.exists(Candidates.isTestFile))

              b.ciState.foreach(o.put("ciConclusion", _))
              b.linkedIssues.headOption.foreach { i =>
                val io = Json.obj()
                io.put("number", i.number)
                io.put("title", i.title)
                io.put("state", i.state)
                val ls = arr(io, "labels")
                i.labels.foreach(ls.add)
                o.set("issue", io)
              }
              list.add(o)
            }
            HandlerResult.Data(n)
    }
  }
