package tools.devhub.querydesk.jdbc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the driver against a local stand-in for the statements API answering what
 * StatementControllerTest shows the real one answers. No test framework, so the jar stays
 * free of dependencies; run with ./test.sh.
 */
public final class DriverTest {
  private static final AtomicReference<String> NEXT_BODY = new AtomicReference<>();
  private static final AtomicReference<Integer> NEXT_STATUS = new AtomicReference<>(200);
  private static final AtomicReference<String> NEXT_TYPE = new AtomicReference<>("application/json");
  private static final AtomicReference<String> LAST_ACCEPT = new AtomicReference<>();
  private static final AtomicReference<Map<?, ?>> LAST_REQUEST = new AtomicReference<>();
  private static final AtomicReference<String> LAST_AUTHORIZATION = new AtomicReference<>();
  private static final AtomicReference<String> ACCEPTED_TOKEN = new AtomicReference<>();
  private static final AtomicReference<String> IDE_AUTHORIZATION = new AtomicReference<>();
  private static final AtomicReference<Map<String, String>> AUTHORIZE = new AtomicReference<>();
  private static final AtomicInteger REGISTRATIONS = new AtomicInteger();
  private static final List<String> GRANTS = new CopyOnWriteArrayList<>();
  private static int failures;

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v1/querydesk/statements", exchange -> {
      String authorization = exchange.getRequestHeaders().getFirst("authorization");
      LAST_AUTHORIZATION.set(authorization);
      LAST_ACCEPT.set(exchange.getRequestHeaders().getFirst("accept"));
      LAST_REQUEST.set((Map<?, ?>) Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
      String accepted = ACCEPTED_TOKEN.get();
      if (accepted != null && !("Bearer " + accepted).equals(authorization)) reply(exchange, 401, "");
      else {
        exchange.getResponseHeaders().set("content-type", NEXT_TYPE.get());
        reply(exchange, NEXT_STATUS.get(), NEXT_BODY.get());
      }
    });
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/.well-known/oauth-authorization-server", exchange -> reply(exchange, 200,
        "{\"authorization_endpoint\": \"" + base + "/oauth/authorize\", \"token_endpoint\": \"" + base
            + "/oauth/token\", \"registration_endpoint\": \"" + base + "/oauth/register\"}"));
    server.createContext("/.well-known/oauth-protected-resource/api/v1/querydesk", exchange ->
        reply(exchange, 200, "{\"resource\": \"" + base + "/api/v1/querydesk\"}"));
    server.createContext("/oauth/register", exchange -> {
      REGISTRATIONS.incrementAndGet();
      reply(exchange, 201, "{\"client_id\": \"oc_test\"}");
    });
    server.createContext("/oauth/token", exchange -> {
      Map<String, String> form = new HashMap<>();
      for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
        String[] kv = pair.split("=", 2);
        form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
      }
      GRANTS.add(form.get("grant_type"));
      boolean valid = form.get("grant_type").equals("authorization_code")
          ? form.get("code").equals("code-1") && challenge(form.get("code_verifier")).equals(AUTHORIZE.get().get("code_challenge"))
          : form.get("refresh_token").equals("rt-" + GRANTS.size() / 2);
      int issued = GRANTS.size();
      if (valid) reply(exchange, 200, "{\"access_token\": \"at-" + issued + "\", \"refresh_token\": \"rt-" + issued + "\", \"expires_in\": 3600}");
      else reply(exchange, 400, "{\"error\": \"invalid_grant\"}");
    });
    server.createContext("/api/v1/querydesk/credentials/crd_test/schema", exchange -> reply(exchange, 200,
        "{\"database\": \"shop\", \"tables\": [{\"name\": \"orders\", \"columns\": [{\"name\": \"_id\", \"type\": \"objectId\", \"primary_key\": true},"
            + " {\"name\": \"total\", \"type\": \"decimal\", \"primary_key\": false}]}, {\"name\": \"customers\", \"columns\": [{\"name\": \"address.city\", \"type\": \"null, string\", \"primary_key\": false}]}]}"));
    // the DataGrip plugin's loopback endpoint, handing out the token it renewed
    server.createContext("/ide-token", exchange -> {
      IDE_AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("authorization"));
      reply(exchange, 200, "ide-2");
    });
    server.start();
    String url = base.replace("http://", "jdbc:querydesk://") + "/crd_test?ssl=false";

    try (Connection connection = DriverManager.getConnection(url, "", "member-token")) {
      readsTypedRows(connection);
      readsArrays(connection);
      readsStreamedRows(connection);
      returnsUpdateCounts(connection);
      bindsPreparedStatementParameters(connection);
      reportsQueryDeskErrors(connection);
      refusesTransactions(connection);
      reportsTheConfiguredProduct(connection);
      signsInInTheBrowser(url);
      renewsThroughTheIde(url, base);
      readsMongoCatalogFromQueryDesk(url);
    } finally {
      server.stop(0);
    }

    if (failures > 0) {
      System.err.println(failures + " check(s) failed");
      System.exit(1);
    }
    System.out.println("all checks passed");
  }

  private static void readsTypedRows(Connection connection) throws SQLException {
    respond(200, """
        {"columns": [
          {"name": "integer", "type": "integer"}, {"name": "float", "type": "float"},
          {"name": "decimal", "type": "decimal"}, {"name": "boolean", "type": "boolean"},
          {"name": "text", "type": "text"}, {"name": "binary", "type": "binary"},
          {"name": "date", "type": "date"}, {"name": "timestamptz", "type": "timestamptz"},
          {"name": "json", "type": "json"}, {"name": "null", "type": "null"}],
         "rows": [[1, 1.5, "1.50", true, "hi", "/wA=", "2024-01-02", "2024-01-02T03:04:05.000000Z", {"a": 1}, null]]}
        """);

    try (Statement statement = connection.createStatement()) {
      statement.setMaxRows(500);
      ResultSet rs = statement.executeQuery("SELECT everything");

      check("sends the credential, SQL and max rows", LAST_REQUEST.get(),
          Map.of("credential_id", "crd_test", "sql", "SELECT everything", "max_rows", 500L));
      check("sends the token as a bearer token", LAST_AUTHORIZATION.get(), "Bearer member-token");

      ResultSetMetaData meta = rs.getMetaData();
      check("column count", meta.getColumnCount(), 10);
      check("integer column type", meta.getColumnType(1), Types.BIGINT);
      check("decimal column type", meta.getColumnType(3), Types.NUMERIC);
      check("timestamptz column type", meta.getColumnType(8), Types.TIMESTAMP_WITH_TIMEZONE);
      check("json column type name", meta.getColumnTypeName(9), "json");

      check("has a row", rs.next(), true);
      check("integer", rs.getObject(1), 1L);
      check("integer as int", rs.getInt("integer"), 1);
      check("float", rs.getObject(2), 1.5d);
      check("decimal", rs.getBigDecimal(3), new BigDecimal("1.50"));
      check("boolean", rs.getBoolean(4), true);
      check("text", rs.getString(5), "hi");
      check("binary", Arrays.toString(rs.getBytes(6)), Arrays.toString(new byte[] {(byte) 0xff, 0}));
      check("date", rs.getDate(7).toString(), "2024-01-02");
      check("timestamptz", rs.getTimestamp(8), Timestamp.from(Instant.parse("2024-01-02T03:04:05Z")));
      check("json as text", rs.getString(9), "{\"a\":1}");
      check("null", rs.getString(10), null);
      check("null was null", rs.wasNull(), true);
      check("null as int", rs.getInt(10), 0);
      check("one row", rs.next(), false);
    }
  }

  private static void readsArrays(Connection connection) throws SQLException {
    respond(200, "{\"columns\": [{\"name\": \"oids\", \"type\": \"array\"}], \"rows\": [[[11, 2200, null]]]}");

    try (Statement statement = connection.createStatement()) {
      ResultSet rs = statement.executeQuery("SELECT oids");
      rs.next();
      check("array elements", Arrays.toString((Object[]) rs.getArray(1).getArray()), "[11, 2200, null]");
      check("array base type", rs.getArray(1).getBaseType(), Types.BIGINT);
      check("array slice", Arrays.toString((Object[]) rs.getArray(1).getArray(2, 5)), "[2200, null]");
      check("array class", rs.getArray(1).getArray().getClass(), Long[].class);
    }
  }

  private static void returnsUpdateCounts(Connection connection) throws SQLException {
    respond(200, "{\"command\": \"update\", \"num_rows\": 3}");

    try (Statement statement = connection.createStatement()) {
      check("update count", statement.executeUpdate("UPDATE t SET a = 1"), 3);
      check("execute has no result set", statement.execute("UPDATE t SET a = 1"), false);
      check("update count after execute", statement.getUpdateCount(), 3);
      check("no more results", statement.getMoreResults(), false);
      check("update count after the last result", statement.getUpdateCount(), -1);
    }
  }

  private static void bindsPreparedStatementParameters(Connection connection) throws SQLException {
    respond(200, "{\"columns\": [], \"rows\": []}");

    try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM t WHERE a = ? AND b = '?' AND c = ? AND d = ?")) {
      statement.setString(1, "it's");
      statement.setInt(2, 7);
      statement.setNull(3, Types.VARCHAR);
      statement.executeQuery();

      check("binds parameters outside strings as literals", LAST_REQUEST.get().get("sql"),
          "SELECT * FROM t WHERE a = 'it''s' AND b = '?' AND c = 7 AND d = NULL");
    }

    try (PreparedStatement statement = connection.prepareStatement("SELECT ?")) {
      expect("refuses an unbound parameter", SQLException.class, statement::executeQuery);
    }
  }

  private static void reportsQueryDeskErrors(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      respond(422, "{\"error\": \"relation \\\"missing\\\" does not exist\"}");
      SQLException syntax = expect("a refused statement", SQLSyntaxErrorException.class, () -> statement.execute("SELECT * FROM missing"));
      check("names the database's error", syntax.getMessage(), "relation \"missing\" does not exist");

      respond(409, "{\"error\": \"This query needs review before it runs.\", \"query_id\": \"qry_1\"}");
      SQLException review = expect("a query held for review", SQLException.class, () -> statement.execute("DELETE FROM t"));
      check("names the query to approve", review.getSQLState() + " " + review.getMessage(),
          "QD409 This query needs review before it runs. Query qry_1 is waiting for approval in QueryDesk.");

      respond(401, "");
      expect("a rejected token", SQLInvalidAuthorizationSpecException.class, () -> statement.execute("SELECT 1"));
    }
  }

  private static void refusesTransactions(Connection connection) throws SQLException {
    check("auto-commits", connection.getAutoCommit(), true);
    expect("refuses manual commit mode", SQLFeatureNotSupportedException.class, () -> connection.setAutoCommit(false));
  }

  private static void reportsTheConfiguredProduct(Connection connection) throws SQLException {
    check("product", connection.getMetaData().getDatabaseProductName(), "PostgreSQL");
    check("major version", connection.getMetaData().getDatabaseMajorVersion(), 16);
    check("catalog methods return no rows", connection.getMetaData().getTables(null, null, "%", null).next(), false);
  }

  private static void signsInInTheBrowser(String url) throws Exception {
    AtomicInteger opened = new AtomicInteger();
    // stands in for the user approving in their browser: QueryDesk sends it back to the driver's listener
    BrowserSignIn.browser = authorizeUrl -> {
      opened.incrementAndGet();
      Map<String, String> query = new HashMap<>();
      for (String pair : URI.create(authorizeUrl).getRawQuery().split("&")) {
        String[] kv = pair.split("=", 2);
        query.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
      }
      AUTHORIZE.set(query);
      String callback = query.get("redirect_uri") + "?code=code-1&state=" + query.get("state");
      new Thread(() -> {
        try {
          HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(callback)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      }).start();
    };
    respond(200, "{\"columns\": [], \"rows\": []}");
    ACCEPTED_TOKEN.set("at-1");

    try (Connection connection = DriverManager.getConnection(url, "", "")) {
      check("opens the browser once", opened.get(), 1);
      check("registers the driver", REGISTRATIONS.get(), 1);
      check("asks for QueryDesk's database client resource with PKCE",
          AUTHORIZE.get().get("code_challenge_method") + " " + AUTHORIZE.get().get("scope") + " " + AUTHORIZE.get().get("resource"),
          "S256 querydesk " + url.replace("jdbc:querydesk://", "http://").replace("/crd_test?ssl=false", "/api/v1/querydesk"));
      connection.createStatement().executeQuery("SELECT 1");
      check("sends the signed-in token", LAST_AUTHORIZATION.get(), "Bearer at-1");
    }

    try (Connection connection = DriverManager.getConnection(url, "", "")) {
      check("a second connection reuses the sign-in", opened.get(), 1);

      ACCEPTED_TOKEN.set("at-2");
      connection.createStatement().executeQuery("SELECT 1");
      check("renews a rejected token with the refresh token", GRANTS.toString(), "[authorization_code, refresh_token]");
      check("retries with the renewed token", LAST_AUTHORIZATION.get(), "Bearer at-2");
      check("without the browser", opened.get(), 1);
    } finally {
      ACCEPTED_TOKEN.set(null);
    }
  }

  private static void readsMongoCatalogFromQueryDesk(String url) throws SQLException {
    try (Connection connection = DriverManager.getConnection(url + "&product=MongoDB&version=8.0", "", "member-token")) {
      DatabaseMetaData meta = connection.getMetaData();
      ResultSet catalogs = meta.getCatalogs();
      check("a MongoDB database is the catalog", catalogs.next() ? catalogs.getString("TABLE_CAT") : null, "shop");

      ResultSet tables = meta.getTables("shop", "shop", "ord%", new String[] {"TABLE"});
      check("collections are tables in the database's schema, by pattern",
          tables.next() ? tables.getString("TABLE_SCHEM") + " " + tables.getString("TABLE_NAME") + " " + tables.getString("TABLE_TYPE") : null, "shop orders TABLE");
      check("only the matching ones", tables.next(), false);

      ResultSet columns = meta.getColumns(null, null, "orders", "%");
      check("field paths are columns", columns.next() ? columns.getString("COLUMN_NAME") + " " + columns.getInt("DATA_TYPE") + " " + columns.getInt("ORDINAL_POSITION") : null, "_id " + Types.OTHER + " 1");
      check("with their JDBC type", columns.next() ? columns.getString("TYPE_NAME") + " " + columns.getInt("DATA_TYPE") : null, "decimal " + Types.DECIMAL);

      ResultSet mixed = meta.getColumns("shop", null, "customers", "address.%");
      check("a field seen as null and string is a string", mixed.next() ? mixed.getInt("DATA_TYPE") : null, Types.VARCHAR);

      ResultSet keys = meta.getPrimaryKeys("shop", null, "orders");
      check("_id is the key", keys.next() ? keys.getString("COLUMN_NAME") : null, "_id");
      check("another database has nothing", meta.getTables("other", null, "%", null).next(), false);
      check("nor another schema", meta.getTables("shop", "other", "%", null).next(), false);

      try (Statement statement = connection.createStatement()) {
        respond(200, "{\"columns\": [], \"rows\": []}");
        statement.execute("db.getSiblingDB(\"shop\").getCollection(\"customers\")\n  .find({})\n  .limit(21)");
        check("its own database's sibling is db", LAST_REQUEST.get().get("sql"), "db.getCollection(\"customers\")\n  .find({})\n  .limit(21)");
        statement.execute("db.getSiblingDB('admin').getCollection('users').find()");
        check("another database's is left for QueryDesk", LAST_REQUEST.get().get("sql"), "db.getSiblingDB('admin').getCollection('users').find()");

        check("use of its own database is already so", statement.execute("use shop") || statement.getUpdateCount() != 0, false);
        check("and isn't sent", LAST_REQUEST.get().get("sql"), "db.getSiblingDB('admin').getCollection('users').find()");
        check("use of another is refused", expect("another database", SQLException.class, () -> statement.execute("use admin")).getSQLState(), "3D000");
      }
    }
  }

  private static void renewsThroughTheIde(String url, String base) throws Exception {
    Properties ide = new Properties();
    ide.setProperty("password", "ide-1");
    ide.setProperty("renewUrl", base + "/ide-token");
    ide.setProperty("renewSecret", "s3cret");
    respond(200, "{\"columns\": [], \"rows\": []}");
    ACCEPTED_TOKEN.set("ide-2");

    try (Connection connection = DriverManager.getConnection(url, ide)) {
      connection.createStatement().executeQuery("SELECT 1");
      check("asks the IDE for a new token with its secret", IDE_AUTHORIZATION.get(), "Bearer s3cret");
      check("retries with the IDE's token", LAST_AUTHORIZATION.get(), "Bearer ide-2");
    } finally {
      ACCEPTED_TOKEN.set(null);
    }

    // the secret only ever goes to this machine
    ide.setProperty("renewUrl", "http://example.com/token");
    try (Connection connection = DriverManager.getConnection(url, ide)) {
      check("refuses a renewUrl off this machine", connection.isClosed() ? "closed" : "connected", "refused");
    } catch (SQLException e) {
      check("refuses a renewUrl off this machine", e.getSQLState(), "08001");
    }
  }

  private static String challenge(String verifier) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respond(int status, String body) {
    NEXT_STATUS.set(status);
    NEXT_BODY.set(body);
    NEXT_TYPE.set("application/json");
  }

  private static void respondStream(String... lines) {
    NEXT_STATUS.set(200);
    NEXT_BODY.set(String.join("\n", lines) + "\n");
    NEXT_TYPE.set("application/x-ndjson; charset=utf-8");
  }

  private static void readsStreamedRows(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      respondStream("{\"columns\": [{\"name\": \"n\", \"type\": \"integer\"}]}", "{\"rows\": [[1], [2]]}", "{\"rows\": [[3]]}", "{\"done\": true}");
      ResultSet rs = statement.executeQuery("SELECT n");
      check("asks for the rows as a stream", LAST_ACCEPT.get(), "application/x-ndjson, application/json");
      check("reads the first streamed row", rs.next() && rs.getLong(1) == 1 && rs.isFirst(), true);
      check("reads on into the next chunk", rs.next() && rs.next() && rs.getLong("n") == 3 && rs.isLast(), true);
      check("ends after the last chunk", rs.next() || !rs.isAfterLast(), false);
      check("scrolls back over what it read", rs.absolute(-2) && rs.getLong(1) == 2 && rs.getRow() == 2, true);

      respondStream("{\"command\": \"delete\", \"num_rows\": 4}", "{\"done\": true}");
      check("a streamed write is its count", statement.executeUpdate("DELETE FROM t"), 4);

      respondStream("{\"columns\": [{\"name\": \"n\", \"type\": \"integer\"}]}", "{\"rows\": [[1]]}", "{\"error\": \"division by zero\"}");
      ResultSet failing = statement.executeQuery("SELECT 1 / n");
      check("rows before an error still read", failing.next(), true);
      SQLException partway = expect("an error partway", SQLSyntaxErrorException.class, failing::next);
      check("names the database's error partway", partway.getMessage(), "division by zero");

      respondStream("{\"columns\": [{\"name\": \"n\", \"type\": \"integer\"}]}", "{\"rows\": [[1]]}");
      ResultSet cut = statement.executeQuery("SELECT n");
      check("a stream with no done", cut.next(), true);
      check("is cut short", expect("a cut stream", SQLException.class, cut::next).getSQLState(), "08006");
    }
  }

  private interface Action {
    void run() throws Exception;
  }

  private static <T extends Exception> T expect(String name, Class<T> type, Action action) {
    try {
      action.run();
    } catch (Exception e) {
      if (type.isInstance(e)) return type.cast(e);
      fail(name + ": expected " + type.getSimpleName() + ", got " + e);
      return null;
    }
    fail(name + ": expected " + type.getSimpleName() + ", nothing was thrown");
    return null;
  }

  private static void check(String name, Object actual, Object expected) {
    if (!Objects.equals(actual, expected)) fail(name + ": expected " + expected + ", got " + actual);
  }

  private static void fail(String message) {
    failures++;
    System.err.println("FAIL " + message);
  }
}
