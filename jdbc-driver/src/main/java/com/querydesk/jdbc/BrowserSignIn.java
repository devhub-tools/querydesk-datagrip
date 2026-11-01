package com.querydesk.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs in to QueryDesk with OAuth in the user's browser (authorization code with PKCE, back
 * to a one-off listener on 127.0.0.1) and keeps the access token fresh with the refresh token.
 *
 * <p>Tokens live in this process's memory only, so a tool signs in once per run. Every
 * connection to one QueryDesk shares them: the refresh token rotates, and QueryDesk revokes
 * every token of a sign-in whose old refresh token is replayed.
 */
final class BrowserSignIn implements Tokens {
  private static final Map<String, BrowserSignIn> BY_SERVER = new ConcurrentHashMap<>();
  private static final Duration SIGN_IN_TIMEOUT = Duration.ofMinutes(5);
  private static final String CALLBACK_PATH = "/callback";
  // the scope database clients sign in with: QueryDesk shows it as a database client, not an AI app
  private static final String SCOPE = "querydesk";

  interface Browser {
    void open(String url) throws IOException;
  }

  /** What opens the sign-in page. Tests stand in for the user's browser here. */
  static volatile Browser browser = BrowserSignIn::openBrowser;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String baseUrl;
  private String clientId;
  private String accessToken;
  private String refreshToken;
  private Instant expiresAt = Instant.EPOCH;

  private BrowserSignIn(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  static BrowserSignIn forServer(String baseUrl) {
    return BY_SERVER.computeIfAbsent(baseUrl, BrowserSignIn::new);
  }

  @Override
  public synchronized String current() throws SQLException {
    if (accessToken != null && Instant.now().isBefore(expiresAt.minusSeconds(60))) return accessToken;
    if (refreshToken != null && refresh()) return accessToken;
    signIn();
    return accessToken;
  }

  @Override
  public synchronized boolean renew(String rejected) throws SQLException {
    // another connection may have renewed it already
    if (accessToken != null && !accessToken.equals(rejected)) return true;
    accessToken = null;
    current();
    return true;
  }

  private boolean refresh() throws SQLException {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", refreshToken);
    form.put("client_id", clientId);
    Map<?, ?> response = postForm(endpoints().get("token_endpoint"), form, true);
    if (response == null) {
      refreshToken = null;
      return false;
    }
    store(response);
    return true;
  }

  private void signIn() throws SQLException {
    Map<String, String> endpoints = endpoints();
    String resource = resource();
    String verifier = randomUrlSafe(32);
    String state = randomUrlSafe(16);

    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      String redirectUri = "http://127.0.0.1:" + listener.getLocalPort() + CALLBACK_PATH;
      if (clientId == null) clientId = register(endpoints.get("registration_endpoint"));

      Map<String, String> query = new LinkedHashMap<>();
      query.put("response_type", "code");
      query.put("client_id", clientId);
      query.put("redirect_uri", redirectUri);
      query.put("code_challenge", challenge(verifier));
      query.put("code_challenge_method", "S256");
      query.put("scope", SCOPE);
      query.put("resource", resource);
      query.put("state", state);
      browser.open(endpoints.get("authorization_endpoint") + "?" + encode(query));

      Map<String, String> callback = awaitCallback(listener);
      if (!state.equals(callback.get("state"))) throw signInFailed("the sign-in response didn't match this request");
      if (callback.containsKey("error")) throw signInFailed(callback.get("error"));

      Map<String, String> form = new LinkedHashMap<>();
      form.put("grant_type", "authorization_code");
      form.put("code", callback.get("code"));
      form.put("redirect_uri", redirectUri);
      form.put("client_id", clientId);
      form.put("code_verifier", verifier);
      store(postForm(endpoints.get("token_endpoint"), form, false));
    } catch (IOException e) {
      throw signInFailed(e.getMessage());
    }
  }

  private void store(Map<?, ?> response) {
    accessToken = (String) response.get("access_token");
    if (response.get("refresh_token") instanceof String rotated) refreshToken = rotated;
    long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 3600;
    expiresAt = Instant.now().plusSeconds(expiresIn);
  }

  private Map<String, String> endpoints() throws SQLException {
    Map<?, ?> metadata = getJson("/.well-known/oauth-authorization-server");
    Map<String, String> endpoints = new HashMap<>();
    for (String key : List.of("authorization_endpoint", "token_endpoint", "registration_endpoint")) {
      endpoints.put(key, (String) metadata.get(key));
    }
    return endpoints;
  }

  private String resource() throws SQLException {
    // RFC 9728: the metadata of a resource with a path sits under that path
    return (String) getJson("/.well-known/oauth-protected-resource/api/v1/querydesk").get("resource");
  }

  private String register(String registrationEndpoint) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("client_name", "QueryDesk JDBC driver");
    // QueryDesk accepts any port on a registered loopback redirect (RFC 8252)
    body.put("redirect_uris", List.of("http://127.0.0.1" + CALLBACK_PATH));
    body.put("grant_types", List.of("authorization_code", "refresh_token"));
    body.put("response_types", List.of("code"));
    body.put("token_endpoint_auth_method", "none");
    body.put("scope", SCOPE);

    HttpRequest request = HttpRequest.newBuilder(URI.create(registrationEndpoint))
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
        .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 201) throw signInFailed("QueryDesk refused to register the driver (" + response.statusCode() + ")");
    return (String) ((Map<?, ?>) Json.parse(response.body())).get("client_id");
  }

  /** Waits for the browser to come back with the code, answering it with a page saying it can close. */
  private static Map<String, String> awaitCallback(ServerSocket listener) throws IOException, SQLException {
    Instant deadline = Instant.now().plus(SIGN_IN_TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      listener.setSoTimeout((int) Math.max(1, Duration.between(Instant.now(), deadline).toMillis()));
      try (Socket socket = listener.accept()) {
        socket.setSoTimeout(5000);
        String requestLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        String target = requestLine == null ? "" : requestLine.split(" ")[1];
        boolean callback = target.startsWith(CALLBACK_PATH + "?");
        respond(socket.getOutputStream(), callback);
        if (callback) return decode(target.substring(CALLBACK_PATH.length() + 1));
      } catch (SocketTimeoutException e) {
        break;
      }
    }
    throw signInFailed("it didn't finish within " + SIGN_IN_TIMEOUT.toMinutes() + " minutes");
  }

  private static void respond(OutputStream out, boolean callback) throws IOException {
    String body = callback
        ? "<!doctype html><title>QueryDesk</title><body style=\"font-family:system-ui;padding:3rem\">"
            + "<h1>Signed in to QueryDesk</h1><p>You can close this tab and go back to your database tool.</p>"
        : "";
    String status = callback ? "200 OK" : "404 Not Found";
    out.write(("HTTP/1.1 " + status + "\r\ncontent-type: text/html; charset=utf-8\r\ncontent-length: "
        + body.getBytes(StandardCharsets.UTF_8).length + "\r\nconnection: close\r\n\r\n" + body)
        .getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /** Opens the system browser with each OS's own command. AWT's Desktop would put a Java icon in the macOS Dock. */
  private static void openBrowser(String url) throws IOException {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String[] command;
    if (os.contains("win")) command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
    else if (os.contains("mac")) command = new String[] {"open", url};
    else command = new String[] {"xdg-open", url};
    new ProcessBuilder(command).inheritIO().start();
  }

  private Map<?, ?> getJson(String path) throws SQLException {
    HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    if (response.statusCode() != 200) throw signInFailed("QueryDesk's sign-in metadata answered " + response.statusCode());
    return (Map<?, ?>) Json.parse(response.body());
  }

  /** Returns null for a refused grant when {@code soft}, so an expired refresh token falls back to signing in. */
  private Map<?, ?> postForm(String url, Map<String, String> form, boolean soft) throws SQLException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("content-type", "application/x-www-form-urlencoded")
        .header("accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
        .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() == 200) return (Map<?, ?>) Json.parse(response.body());
    if (soft && response.statusCode() < 500) return null;
    throw signInFailed("QueryDesk's token endpoint answered " + response.statusCode());
  }

  private HttpResponse<String> send(HttpRequest request) throws SQLException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw signInFailed("couldn't reach " + request.uri().getHost() + ": " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw signInFailed("it was interrupted");
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

  private static String randomUrlSafe(int bytes) {
    byte[] random = new byte[bytes];
    new SecureRandom().nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  private static String encode(Map<String, String> params) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> entry : params.entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  private static Map<String, String> decode(String query) {
    Map<String, String> params = new HashMap<>();
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
    }
    return params;
  }

  private static SQLInvalidAuthorizationSpecException signInFailed(String why) {
    return new SQLInvalidAuthorizationSpecException("Couldn't sign in to QueryDesk: " + why, "28000");
  }
}
