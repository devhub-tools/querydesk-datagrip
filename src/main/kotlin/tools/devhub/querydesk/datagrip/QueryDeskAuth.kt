package tools.devhub.querydesk.datagrip

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

class SignInException(message: String) : IOException(message)

/**
 * Signs in to QueryDesk with OAuth in the browser (authorization code with PKCE, back to a
 * one-off listener on 127.0.0.1) and hands out fresh access tokens.
 *
 * The client id and refresh token are kept in the IDE's password safe, which is the OS
 * keychain on macOS, Credential Manager on Windows and libsecret or KeePass on Linux, so the
 * IDE stays signed in across restarts. The refresh token rotates on every use and QueryDesk
 * revokes a sign-in whose old one is replayed, so only this service ever spends it.
 */
@Service(Service.Level.APP)
class QueryDeskAuth {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
  private val gson = Gson()
  private val access = mutableMapOf<String, Pair<String, Instant>>()

  /** A valid access token for [server], refreshing it when it's close to expiring. Null when signed out. */
  @Synchronized
  fun accessToken(server: String): String? {
    access[server]?.let { (token, expiresAt) -> if (Instant.now().isBefore(expiresAt.minusSeconds(60))) return token }
    val stored = stored(server) ?: return null
    val response = postForm(
      endpoints(server).token,
      mapOf("grant_type" to "refresh_token", "refresh_token" to stored.refreshToken, "client_id" to stored.clientId),
    )
    if (response == null) {
      // refused: the sign-in was revoked or expired, so the user signs in again
      signOut(server)
      return null
    }
    return store(server, stored.clientId, response)
  }

  fun isSignedIn(server: String): Boolean = stored(server) != null

  /**
   * Runs the browser sign-in. Blocks until the user finishes it or the progress it runs under
   * is cancelled, so call it off the UI thread. Not synchronized: a sign-in the user abandoned
   * in the browser mustn't hold up the next one.
   */
  fun signIn(server: String): String {
    val endpoints = endpoints(server)
    // RFC 9728: the metadata of a resource with a path sits under that path
    val resource = getJson("$server/.well-known/oauth-protected-resource/api/v1/querydesk").get("resource").asString
    val verifier = randomUrlSafe(32)
    val state = randomUrlSafe(16)

    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
      val redirectUri = "http://127.0.0.1:${listener.localPort}$CALLBACK_PATH"
      val clientId = stored(server)?.clientId ?: register(endpoints.registration)

      val query = mapOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "code_challenge" to challenge(verifier),
        "code_challenge_method" to "S256",
        "scope" to SCOPE,
        "resource" to resource,
        "state" to state,
      )
      BrowserUtil.browse("${endpoints.authorization}?${encode(query)}")

      val callback = awaitCallback(listener)
      if (callback["state"] != state) throw SignInException("the sign-in response didn't match this request")
      callback["error"]?.let { throw SignInException(it) }

      val response = postForm(
        endpoints.token,
        mapOf(
          "grant_type" to "authorization_code",
          "code" to callback["code"].orEmpty(),
          "redirect_uri" to redirectUri,
          "client_id" to clientId,
          "code_verifier" to verifier,
        ),
      ) ?: throw SignInException("QueryDesk refused the sign-in")
      return store(server, clientId, response)
    }
  }

  @Synchronized
  fun signOut(server: String) {
    access.remove(server)
    PasswordSafe.instance.set(attributes(server), null)
  }

  @Synchronized
  private fun store(server: String, clientId: String, response: JsonObject): String {
    val token = response.get("access_token").asString
    val expiresIn = response.get("expires_in")?.asLong ?: 3600
    access[server] = token to Instant.now().plusSeconds(expiresIn)
    response.get("refresh_token")?.asString?.let { PasswordSafe.instance.set(attributes(server), Credentials(clientId, it)) }
    return token
  }

  private data class Stored(val clientId: String, val refreshToken: String)

  private fun stored(server: String): Stored? {
    val credentials = PasswordSafe.instance.get(attributes(server)) ?: return null
    val clientId = credentials.userName ?: return null
    val refreshToken = credentials.getPasswordAsString() ?: return null
    return Stored(clientId, refreshToken)
  }

  private fun attributes(server: String) = CredentialAttributes(generateServiceName("QueryDesk", server))

  private data class Endpoints(val authorization: String, val token: String, val registration: String)

  private fun endpoints(server: String): Endpoints {
    val metadata = getJson("$server/.well-known/oauth-authorization-server")
    return Endpoints(
      metadata.get("authorization_endpoint").asString,
      metadata.get("token_endpoint").asString,
      metadata.get("registration_endpoint").asString,
    )
  }

  private fun register(registrationEndpoint: String): String {
    val body = mapOf(
      "client_name" to "QueryDesk for DataGrip",
      // QueryDesk accepts any port on a registered loopback redirect (RFC 8252)
      "redirect_uris" to listOf("http://127.0.0.1$CALLBACK_PATH"),
      "grant_types" to listOf("authorization_code", "refresh_token"),
      "response_types" to listOf("code"),
      "token_endpoint_auth_method" to "none",
      "scope" to SCOPE,
    )
    val request = HttpRequest.newBuilder(URI.create(registrationEndpoint))
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
      .build()
    val response = send(request)
    if (response.statusCode() != 201) throw SignInException("QueryDesk refused to register DataGrip (${response.statusCode()})")
    return gson.fromJson(response.body(), JsonObject::class.java).get("client_id").asString
  }

  private fun getJson(url: String): JsonObject {
    val response = send(HttpRequest.newBuilder(URI.create(url)).GET().build())
    if (response.statusCode() != 200) throw SignInException("$url answered ${response.statusCode()}")
    return gson.fromJson(response.body(), JsonObject::class.java)
  }

  /** Null for a refused grant, so a revoked refresh token sends the user back to sign in. */
  private fun postForm(url: String, form: Map<String, String>): JsonObject? {
    val request = HttpRequest.newBuilder(URI.create(url))
      .header("content-type", "application/x-www-form-urlencoded")
      .header("accept", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
      .build()
    val response = send(request)
    return when {
      response.statusCode() == 200 -> gson.fromJson(response.body(), JsonObject::class.java)
      response.statusCode() < 500 -> null
      else -> throw SignInException("QueryDesk's token endpoint answered ${response.statusCode()}")
    }
  }

  private fun send(request: HttpRequest): HttpResponse<String> =
    try {
      http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
      throw SignInException("couldn't reach ${request.uri().host}: ${e.message}")
    }

  companion object {
    private const val CALLBACK_PATH = "/callback"

    // the scope database clients sign in with: QueryDesk shows it as a database client, not an AI app
    private const val SCOPE = "querydesk"
    private val SIGN_IN_TIMEOUT: Duration = Duration.ofMinutes(5)

    fun getInstance(): QueryDeskAuth = service()

    /** Waits for the browser to come back with the code, answering it with a page saying it can close. */
    private fun awaitCallback(listener: ServerSocket): Map<String, String> {
      val deadline = Instant.now().plus(SIGN_IN_TIMEOUT)
      // wakes twice a second so cancelling the progress stops the wait
      listener.soTimeout = 500
      while (Instant.now().isBefore(deadline)) {
        ProgressManager.checkCanceled()
        try {
          listener.accept().use { socket ->
            socket.soTimeout = 5000
            val requestLine = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine().orEmpty()
            val target = requestLine.split(" ").getOrElse(1) { "" }
            val callback = target.startsWith("$CALLBACK_PATH?")
            val body = if (callback) {
              "<!doctype html><title>QueryDesk</title><body style=\"font-family:system-ui;padding:3rem\">" +
                "<h1>Signed in to QueryDesk</h1><p>You can close this tab and go back to DataGrip.</p>"
            } else {
              ""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            socket.getOutputStream().write(
              ("HTTP/1.1 ${if (callback) "200 OK" else "404 Not Found"}\r\ncontent-type: text/html; charset=utf-8\r\n" +
                "content-length: ${bytes.size}\r\nconnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII) + bytes,
            )
            if (callback) return decode(target.substring(CALLBACK_PATH.length + 1))
          }
        } catch (e: SocketTimeoutException) {
          continue
        }
      }
      throw SignInException("it didn't finish within ${SIGN_IN_TIMEOUT.toMinutes()} minutes")
    }

    private fun challenge(verifier: String): String {
      val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private fun randomUrlSafe(bytes: Int): String {
      val random = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    private fun encode(params: Map<String, String>): String =
      params.entries.joinToString("&") {
        URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
      }

    private fun decode(query: String): Map<String, String> =
      query.split("&").associate { pair ->
        val (key, value) = pair.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
      }
  }
}
