package tools.devhub.querydesk.jdbc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

  Client(String baseUrl, String credentialId, Tokens tokens) {
    this.endpoint = URI.create(baseUrl + "/api/v1/querydesk/statements");
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
    HttpResponse<String> response = send(request, token);
    // an expired token is renewed once; a statement rejected for its token never ran
    if (response.statusCode() == 401 && tokens.renew(token)) response = send(request, tokens.current());

    int status = response.statusCode();
    if (status == 401) throw new SQLInvalidAuthorizationSpecException("QueryDesk rejected the token. Sign in again.", "28000");

    Map<?, ?> json = parseObject(response.body(), status);
    Object error = json.get("error");

    if (status == 409) throw new SQLException(error + " Approve " + json.get("query_id") + " in QueryDesk, then run it again.", "QD409");
    if (status == 404) throw new SQLNonTransientConnectionException(String.valueOf(error), "08001");
    if (status == 422) throw new SQLSyntaxErrorException(String.valueOf(error), "42000");
    if (status != 200) throw new SQLException("QueryDesk answered " + status + (error == null ? "" : ": " + error), "08006");

    return toResult(json);
  }

  private HttpResponse<String> send(String json, String token) throws SQLException {
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .header("authorization", "Bearer " + token)
        .header("content-type", "application/json")
        .header("accept", "application/json")
        .timeout(Duration.ofMinutes(10))
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();

    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
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

  private static Map<?, ?> parseObject(String body, int status) throws SQLException {
    try {
      if (Json.parse(body) instanceof Map<?, ?> map) return map;
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
