package causeway.mini

import Json.*

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.util.Try

final class GitHubError(val status: Int, message: String) extends RuntimeException(s"GitHub $status: $message")

/** The slice of the GitHub REST API we use. A trait so tests can run offline. */
trait GitHub:
  def get(path: String, query: Map[String, String] = Map.empty): ujson.Value

object GitHubAuth:
  /** GITHUB_TOKEN or GH_TOKEN, else whatever `gh auth token` prints. None means unauthenticated. */
  def resolve(env: Map[String, String] = sys.env): Option[String] =
    env.get("GITHUB_TOKEN").orElse(env.get("GH_TOKEN")).filter(_.nonEmpty).orElse(fromGhCli())

  private def fromGhCli(): Option[String] =
    Try {
      val p = new ProcessBuilder("gh", "auth", "token")
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val out = new String(p.getInputStream.readAllBytes(), UTF_8).trim
      if p.waitFor() == 0 && out.nonEmpty then Some(out) else None
    }.toOption.flatten

final class HttpGitHub(token: Option[String], base: String = "https://api.github.com") extends GitHub:
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  /** Longest we will sit out a rate limit before giving up and saying so. */
  private val MaxWaitSeconds = 90L
  private val MaxAttempts = 3

  private def enc(s: String) = URLEncoder.encode(s, UTF_8)

  def get(path: String, query: Map[String, String] = Map.empty): ujson.Value =
    val qs = if query.isEmpty then "" else query.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("?", "&", "")
    val builder = HttpRequest.newBuilder(URI.create(base + path + qs))
      .timeout(Duration.ofSeconds(30))
      .header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .header("User-Agent", "causeway-mini")
    token.foreach(t => builder.header("Authorization", s"Bearer $t"))
    val request = builder.build()

    var attempt = 1
    while true do
      val res = client.send(request, HttpResponse.BodyHandlers.ofString())
      val status = res.statusCode()
      if status >= 200 && status < 300 then return ujson.read(res.body())
      val limited = (status == 403 || status == 429) &&
        (header(res, "retry-after").isDefined || header(res, "x-ratelimit-remaining").contains("0"))
      if limited then
        val wait = waitSeconds(res)
        if attempt >= MaxAttempts || wait > MaxWaitSeconds then
          throw GitHubError(status, s"rate limited; resets in ${wait}s" +
            (if token.isEmpty then " (no token: set GITHUB_TOKEN or run 'gh auth login')" else ""))
        Thread.sleep(wait * 1000)
        attempt += 1
      else
        val msg = Try(ujson.read(res.body()).text("message")).toOption.flatten.getOrElse(res.body().take(200))
        throw GitHubError(status, msg)
    throw IllegalStateException("unreachable")

  private def header(res: HttpResponse[?], name: String): Option[String] =
    Option(res.headers().firstValue(name).orElse(null))

  private def waitSeconds(res: HttpResponse[?]): Long =
    header(res, "retry-after").flatMap(_.toLongOption)
      .orElse(header(res, "x-ratelimit-reset").flatMap(_.toLongOption)
        .map(reset => reset - System.currentTimeMillis() / 1000))
      .map(s => math.max(s, 1L) + 1L)
      .getOrElse(60L)
