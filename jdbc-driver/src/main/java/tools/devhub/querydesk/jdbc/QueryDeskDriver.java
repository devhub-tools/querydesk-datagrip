package tools.devhub.querydesk.jdbc;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for QueryDesk. It opens no connection to the database: each statement is sent
 * to QueryDesk over HTTPS and runs there, with the member's access, reviews, data protection
 * and audit log applied.
 *
 * <p>{@code jdbc:querydesk://app.querydesk.com/<credential id>}. With no password the driver signs
 * in in the browser; a QueryDesk token as the password is used as it is. {@code ssl=false} talks
 * plain HTTP, for a local server. {@code product} and {@code version} set what the driver
 * reports the database to be (PostgreSQL 16 by default), which tools read to pick their SQL
 * dialect.
 */
public final class QueryDeskDriver implements Driver {
  static final String PREFIX = "jdbc:querydesk:";
  static final int MAJOR_VERSION = 0;
  static final int MINOR_VERSION = 1;

  static {
    try {
      DriverManager.registerDriver(new QueryDeskDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) return null;

    URI uri = URI.create(url.substring(PREFIX.length()));
    Map<String, String> params = new HashMap<>();
    if (uri.getRawQuery() != null) {
      for (String pair : uri.getRawQuery().split("&")) {
        String[] kv = pair.split("=", 2);
        params.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
      }
    }
    for (String key : info.stringPropertyNames()) params.putIfAbsent(key, info.getProperty(key));

    String path = uri.getPath() == null ? "" : uri.getPath();
    String credentialId = path.startsWith("/") ? path.substring(1) : path;
    if (uri.getHost() == null || credentialId.isEmpty()) {
      throw new SQLException("Expected jdbc:querydesk://<host>/<credential id>, got " + url, "08001");
    }

    if (params.containsKey("trace")) Handler.trace = Path.of(params.get("trace"));

    String scheme = "false".equals(params.get("ssl")) ? "http" : "https";
    String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
    String baseUrl = scheme + "://" + uri.getHost() + port;

    // with no token, the user signs in in their browser, which a tool waits for on connect
    String token = params.getOrDefault("password", params.get("token"));
    // An IDE that holds the sign-in (the DataGrip plugin) passes where to get a fresh token when
    // this one expires. Read from the properties only, never the URL, since the secret goes there.
    String renewUrl = info.getProperty("renewUrl");
    Tokens tokens;
    if (token == null || token.isBlank()) tokens = BrowserSignIn.forServer(baseUrl);
    else if (renewUrl != null) tokens = Tokens.renewedBy(token, renewUrl, info.getProperty("renewSecret", ""));
    else tokens = Tokens.fixed(token);
    tokens.current();

    Client client = new Client(baseUrl, credentialId, tokens);

    return new ConnectionHandler(
        client, url, params.getOrDefault("product", "PostgreSQL"), params.getOrDefault("version", "16.0")).proxy();
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    DriverPropertyInfo password = new DriverPropertyInfo("password", info.getProperty("password"));
    password.required = false;
    password.description = "A QueryDesk token. Leave empty to sign in in the browser";
    DriverPropertyInfo product = new DriverPropertyInfo("product", info.getProperty("product", "PostgreSQL"));
    product.description = "The database product the driver reports";
    DriverPropertyInfo version = new DriverPropertyInfo("version", info.getProperty("version", "16.0"));
    version.description = "The database version the driver reports";
    return new DriverPropertyInfo[] {password, product, version};
  }

  @Override
  public int getMajorVersion() {
    return MAJOR_VERSION;
  }

  @Override
  public int getMinorVersion() {
    return MINOR_VERSION;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("The QueryDesk driver doesn't log through java.util.logging");
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
