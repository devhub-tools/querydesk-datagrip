package tools.devhub.querydesk.jdbc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;

/** Where a connection's bearer token comes from: one the user gave, the IDE, or a browser sign-in. */
interface Tokens {
  String current() throws SQLException;

  /** Gets a new token after QueryDesk rejected {@code rejected}. False when there is no way to. */
  boolean renew(String rejected) throws SQLException;

  static Tokens fixed(String token) {
    return new Tokens() {
      @Override
      public String current() {
        return token;
      }

      @Override
      public boolean renew(String rejected) {
        return false;
      }
    };
  }

  /**
   * A token the IDE handed over, renewed by asking the IDE again: it keeps the refresh token,
   * which rotates on every use, so only one place may spend it. {@code renewUrl} must be on this
   * machine, since the secret goes to it.
   */
  static Tokens renewedBy(String token, String renewUrl, String secret) throws SQLException {
    URI uri = URI.create(renewUrl);
    try {
      if (!"http".equals(uri.getScheme()) || !InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
        throw new SQLException("renewUrl must be a loopback http address, got " + renewUrl, "08001");
      }
    } catch (IOException e) {
      throw new SQLException("renewUrl must be a loopback http address, got " + renewUrl, "08001", e);
    }
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    return new Tokens() {
      private volatile String current = token;

      @Override
      public String current() {
        return current;
      }

      @Override
      public synchronized boolean renew(String rejected) throws SQLException {
        // another statement already renewed it
        if (!current.equals(rejected)) return true;
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("authorization", "Bearer " + secret)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
          HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
          // the IDE is signed out of this QueryDesk, or has nothing newer
          if (response.statusCode() != 200 || response.body().isBlank() || response.body().equals(rejected)) return false;
          current = response.body().strip();
          return true;
        } catch (IOException e) {
          return false;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLException("Interrupted renewing the QueryDesk token", "57014", e);
        }
      }
    };
  }
}
