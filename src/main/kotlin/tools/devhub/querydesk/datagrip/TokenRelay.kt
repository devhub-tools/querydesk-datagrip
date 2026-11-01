package tools.devhub.querydesk.datagrip

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Hands the driver a fresh access token when QueryDesk rejects an expired one, so a connection
 * held open past the hour keeps working. DataGrip runs drivers in their own process, and only
 * this IDE may spend the refresh token (it rotates on every use), so the driver asks here: a
 * listener on 127.0.0.1 that answers only a request carrying this IDE run's secret.
 */
@Service(Service.Level.APP)
class TokenRelay : Disposable {
  val secret: String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
    Base64.getUrlEncoder().withoutPadding().encodeToString(it)
  }

  private val listener = lazy {
    HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
      // renewing calls QueryDesk and the password safe, so not on the listener's own thread
      executor = AppExecutorUtil.getAppExecutorService()
      createContext("/token", ::answer)
      start()
    }
  }
  private val server: HttpServer by listener

  /** Where the driver of a connection to [server] asks for a new token. */
  fun url(server: String): String =
    "http://127.0.0.1:${this.server.address.port}/token?server=" + URLEncoder.encode(server, StandardCharsets.UTF_8)

  private fun answer(exchange: HttpExchange) {
    exchange.use {
      val authorization = it.requestHeaders.getFirst("authorization").orEmpty()
      val server = it.requestURI.rawQuery?.removePrefix("server=")?.let { query -> URLDecoder.decode(query, StandardCharsets.UTF_8) }
      val authorized = MessageDigest.isEqual(authorization.toByteArray(), "Bearer $secret".toByteArray())
      // never signs in: a background renewal mustn't open the browser
      val token = if (authorized && server != null) runCatching { QueryDeskAuth.getInstance().accessToken(server) }.getOrNull() else null

      if (token == null) {
        it.sendResponseHeaders(if (authorized) 404 else 401, -1)
      } else {
        val body = token.toByteArray(StandardCharsets.UTF_8)
        it.sendResponseHeaders(200, body.size.toLong())
        it.responseBody.write(body)
      }
    }
  }

  override fun dispose() {
    // only stops a listener that was started
    if (listener.isInitialized()) server.stop(0)
  }

  companion object {
    fun getInstance(): TokenRelay = service()
  }
}
