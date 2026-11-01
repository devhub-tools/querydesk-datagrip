package tools.devhub.querydesk.datagrip

import java.net.URI

/** How a QueryDesk server and a credential on it are written as a QueryDesk JDBC URL, and back. */
object QueryDeskServer {
  const val URL_PREFIX = "jdbc:querydesk:"

  /** What the driver reports each engine as, so DataGrip reads its catalog with that dialect. */
  private val PRODUCTS = mapOf(
    "postgres" to ("PostgreSQL" to "16.0"),
    "mysql" to ("MySQL" to "8.0"),
    "sqlserver" to ("Microsoft SQL Server" to "16.0"),
    "oracle" to ("Oracle" to "19.0"),
    "clickhouse" to ("ClickHouse" to "24.0"),
    "mongodb" to ("MongoDB" to "8.0"),
  )

  fun supports(adapter: String) = adapter in PRODUCTS

  /** The driver definition (see querydesk-drivers.xml) for an engine. */
  fun driverId(adapter: String) = "querydesk-$adapter"

  fun jdbcUrl(server: String, credentialId: String, adapter: String): String {
    val uri = URI.create(server)
    val (product, version) = PRODUCTS.getValue(adapter)
    val params = buildList {
      if (uri.scheme == "http") add("ssl=false")
      add("product=" + product.replace(" ", "%20"))
      add("version=$version")
    }
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$URL_PREFIX//${uri.host}$port/$credentialId?${params.joinToString("&")}"
  }

  /** The credential a QueryDesk JDBC URL connects with. */
  fun credentialOf(jdbcUrl: String): String = URI.create(jdbcUrl.removePrefix(URL_PREFIX)).path.removePrefix("/")

  /** The QueryDesk server a QueryDesk JDBC URL points at, which is where its token comes from. */
  fun serverOf(jdbcUrl: String): String {
    val uri = URI.create(jdbcUrl.removePrefix(URL_PREFIX))
    val scheme = if (uri.rawQuery?.split("&")?.contains("ssl=false") == true) "http" else "https"
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://${uri.host}$port"
  }
}
