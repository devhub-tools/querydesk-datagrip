package tools.devhub.querydesk.jdbc;

import java.sql.SQLException;

/** Where a connection's bearer token comes from: one the user gave, or a browser sign-in. */
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
}
