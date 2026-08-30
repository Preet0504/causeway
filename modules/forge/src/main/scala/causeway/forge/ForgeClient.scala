package causeway.forge

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.util.Try

/** Thin HTTP layer over GitHub's GraphQL v4 endpoint.
  *
  * Everything interesting — query construction and parsing — lives in [[GraphQl]] and is pure.
  * This class only moves bytes, so the parts worth testing can be tested without a network or a
  * token.
  *
  * D6: this is a deterministic service. No agent reasons here; agents receive what it returns.
  */
final class ForgeClient(
    token: String,
    endpoint: URI = URI.create("https://api.github.com/graphql"),
    timeout: Duration = Duration.ofSeconds(30)
):
  private val http = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

  private def post(body: String): Either[ForgeError, String] =
    val req = HttpRequest
      .newBuilder(endpoint)
      .timeout(timeout)
      .header("Authorization", s"Bearer $token")
      .header("Content-Type", "application/json")
      .header("User-Agent", "causeway-mcp")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    Try(http.send(req, HttpResponse.BodyHandlers.ofString())).toEither match
      case Left(t) => Left(ForgeError.Network(Option(t.getMessage).getOrElse(t.toString)))
      case Right(res) =>
        res.statusCode() match
          case 200 => Right(res.body())
          // Secondary rate limits arrive as 403/429 rather than inside the GraphQL errors array,
          // so both paths have to map onto the same first-class outcome.
          case 403 | 429 => Left(ForgeError.RateLimited(s"HTTP ${res.statusCode()}"))
          case s         => Left(ForgeError.HttpFailure(s, res.body()))

  /** One round trip for many commits. Chunked because GraphQL complexity limits bite well
    * before the useful batch size does.
    */
  def bundleCandidates(
      owner: String,
      name: String,
      shas: Vector[String],
      chunkSize: Int = 20
  ): Either[ForgeError, Vector[CommitBundle]] =
    if shas.isEmpty then Right(Vector.empty)
    else
      shas
        .grouped(chunkSize)
        .foldLeft[Either[ForgeError, Vector[CommitBundle]]](Right(Vector.empty)) {
          case (Left(e), _) => Left(e)
          case (Right(acc), chunk) =>
            post(GraphQl.bundleQuery(owner, name, chunk.toVector))
              .flatMap(GraphQl.parseBundle)
              .map(acc ++ _)
        }

  def issue(owner: String, name: String, number: Int, comments: Int = 20): Either[ForgeError, IssueDetail] =
    post(GraphQl.issueQuery(owner, name, number, comments)).flatMap(GraphQl.parseIssue)

  /** Confirm the credentials actually work, returning the authenticated login.
    *
    * Holding a non-empty token string is NOT the same as being authenticated, and reporting the
    * former as the latter is the exact failure this project is built to avoid. A run once spent
    * twenty minutes with every forge call returning 401 because startup announced
    * "authenticated" on the strength of a string being present.
    */
  def verify(): Either[ForgeError, String] =
    post("""{"query":"query{viewer{login}}"}""").flatMap { json =>
      val root = GraphQl.mapper.readTree(json)
      GraphQl.checkErrors(root) match
        case Some(e) => Left(e)
        case None =>
          Option(root.get("data"))
            .flatMap(d => Option(d.get("viewer")))
            .filterNot(_.isNull)
            .flatMap(v => Option(v.get("login")))
            .map(_.stringValue())
            .toRight(ForgeError.Malformed("no viewer in response"))
    }

  /** Cheap reachability and shape check, used before committing budget to a repository. */
  def repoInfo(owner: String, name: String): Either[ForgeError, (String, Int)] =
    post(GraphQl.repoQuery(owner, name)).flatMap { json =>
      val root = GraphQl.mapper.readTree(json)
      GraphQl.checkErrors(root) match
        case Some(e) => Left(e)
        case None =>
          Option(root.get("data")).flatMap(d => Option(d.get("repository"))).filterNot(_.isNull) match
            case None => Left(ForgeError.NotFound(s"$owner/$name"))
            case Some(r) =>
              val branch = Option(r.get("defaultBranchRef")).filterNot(_.isNull)
                .flatMap(b => Option(b.get("name"))).map(_.stringValue()).getOrElse("HEAD")
              val issues = Option(r.get("issues")).flatMap(i => Option(i.get("totalCount")))
                .map(_.intValue()).getOrElse(0)
              Right((branch, issues))
    }

object ForgeClient:
  /** Read the token from the environment. Never from a file that could be committed. */
  def fromEnv(): Option[ForgeClient] =
    sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty).map(ForgeClient(_))
