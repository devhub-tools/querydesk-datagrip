package tools.devhub.querydesk.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls QueryDesk's statements API, which runs SQL as the member the token belongs to. */
final class Client {
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final URI endpoint;
  private final String credentialId;
  private final Tokens tokens;

  private final URI schemaEndpoint;

  Client(String baseUrl, String credentialId, Tokens tokens) {
    this.endpoint = URI.create(baseUrl + "/api/v1/querydesk/statements");
    this.schemaEndpoint = URI.create(baseUrl + "/api/v1/querydesk/credentials/" + credentialId + "/schema");
    this.credentialId = credentialId;
    this.tokens = tokens;
  }

  Result execute(String sql, long maxRows) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("credential_id", credentialId);
    body.put("sql", sql);
    body.put("max_rows", maxRows);
    String request = Json.write(body);

    String token = tokens.current();
    HttpResponse<InputStream> response = send(request, token);
    // an expired token is renewed once; a statement rejected for its token never ran
    if (response.statusCode() == 401 && tokens.renew(token)) {
      closeQuietly(response.body());
      response = send(request, tokens.current());
    }

    int status = response.statusCode();
    boolean streamed = response.headers().firstValue("content-type").orElse("").startsWith("application/x-ndjson");
    if (status == 200 && streamed) return streamedResult(response.body());

    if (status == 401) {
      closeQuietly(response.body());
      throw new SQLInvalidAuthorizationSpecException("QueryDesk rejected the token. Sign in again.", "28000");
    }

    Map<?, ?> json = parseObject(readAll(response.body()), status);
    Object error = json.get("error");

    if (status == 409) throw new SQLException(error + " Query " + json.get("query_id") + " is waiting for approval in QueryDesk.", "QD409");
    if (status == 404) throw new SQLNonTransientConnectionException(String.valueOf(error), "08001");
    if (status == 422) throw new SQLSyntaxErrorException(String.valueOf(error), "42000");
    if (status != 200) throw new SQLException("QueryDesk answered " + status + (error == null ? "" : ": " + error), "08006");

    return toResult(json);
  }

  /** The credential's database schema as QueryDesk last synced it: its name and tables with their columns. */
  Map<?, ?> schema() throws SQLException {
    String token = tokens.current();
    HttpResponse<InputStream> response = get(schemaEndpoint, token);
    if (response.statusCode() == 401 && tokens.renew(token)) {
      closeQuietly(response.body());
      response = get(schemaEndpoint, tokens.current());
    }
    int status = response.statusCode();
    Map<?, ?> json = parseObject(readAll(response.body()), status);
    if (status != 200) throw new SQLException("QueryDesk answered " + status + " for the schema: " + json.get("error"), "08006");
    return json;
  }

  private HttpResponse<InputStream> get(URI uri, String token) throws SQLException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .header("authorization", "Bearer " + token)
        .header("accept", "application/json")
        .timeout(Duration.ofMinutes(1))
        .GET()
        .build();
    return send(request);
  }

  /**
   * One statement's rows as QueryDesk reads them: a line naming the columns (or a write's
   * count), a line per chunk of rows, then done. Only the first line is read here.
   */
  private static Result streamedResult(InputStream body) throws SQLException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    Rows rows = Rows.streamed(reader);
    Map<?, ?> first;
    try {
      first = parseObject(reader.readLine(), 200);
    } catch (IOException e) {
      rows.close();
      throw new SQLException("Lost QueryDesk's result: " + e.getMessage(), "08006", e);
    }

    if (first.get("columns") instanceof List<?> columns) {
      List<String> names = new ArrayList<>();
      List<String> types = new ArrayList<>();
      for (Object column : columns) {
        Map<?, ?> map = (Map<?, ?>) column;
        names.add((String) map.get("name"));
        types.add((String) map.get("type"));
      }
      return Result.rows(names, types, rows);
    }

    // a write: its count, then done, which size() reads
    rows.size();
    return Result.count(first.get("num_rows") instanceof Number n ? n.longValue() : 0);
  }

  private HttpResponse<InputStream> send(String json, String token) throws SQLException {
    return send(HttpRequest.newBuilder(endpoint)
        .header("authorization", "Bearer " + token)
        .header("content-type", "application/json")
        // a statement's rows stream back as they're read; several statements come back whole
        .header("accept", "application/x-ndjson, application/json")
        .timeout(Duration.ofMinutes(10))
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build());
  }

  private HttpResponse<InputStream> send(HttpRequest request) throws SQLException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException e) {
      // HttpClient's ConnectException has no message when the port is closed
      String reason = e.getMessage() != null ? e.getMessage()
          : e instanceof java.net.ConnectException ? "connection refused" : e.getClass().getSimpleName();
      throw new SQLNonTransientConnectionException(
          "Could not reach QueryDesk at " + endpoint.getAuthority() + ": " + reason, "08001", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted waiting for QueryDesk", "57014", e);
    }
  }

  private static String readAll(InputStream body) throws SQLException {
    try (body) {
      return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SQLException("Lost QueryDesk's answer: " + e.getMessage(), "08006", e);
    }
  }

  private static void closeQuietly(InputStream body) {
    try {
      body.close();
    } catch (IOException e) {
      // nothing left to read from it either way
    }
  }

  private static Map<?, ?> parseObject(String body, int status) throws SQLException {
    try {
      if (body != null && Json.parse(body) instanceof Map<?, ?> map) return map;
    } catch (RuntimeException e) {
      // falls through to the error below, which names the status
    }
    throw new SQLException("QueryDesk answered " + status + " with a body the driver can't read", "08006");
  }

  private static Result toResult(Map<?, ?> json) {
    if (json.get("columns") instanceof List<?> columns) {
      List<String> names = new ArrayList<>();
      List<String> types = new ArrayList<>();
      for (Object column : columns) {
        Map<?, ?> map = (Map<?, ?>) column;
        names.add((String) map.get("name"));
        types.add((String) map.get("type"));
      }
      List<List<Object>> rows = new ArrayList<>();
      for (Object row : (List<?>) json.get("rows")) rows.add(new ArrayList<>((List<?>) row));
      return Result.rows(names, types, rows);
    }

    // a command's row count, or several statements' summaries, which carry no single count
    return Result.count(json.get("num_rows") instanceof Number n ? n.longValue() : 0);
  }
}
