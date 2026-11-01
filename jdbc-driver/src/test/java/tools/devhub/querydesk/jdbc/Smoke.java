package tools.devhub.querydesk.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs each SQL argument through the driver against a real QueryDesk and prints what comes
 * back. QUERYDESK_TOKEN holds the token so it stays out of the shell history.
 *
 * <pre>java -cp build/querydesk-jdbc.jar:build/test-classes tools.devhub.querydesk.jdbc.Smoke \
 *   'jdbc:querydesk://localhost:4000/crd_...?ssl=false' 'SELECT 1'</pre>
 */
public final class Smoke {
  public static void main(String[] args) throws SQLException {
    try (Connection connection = DriverManager.getConnection(args[0], "", System.getenv("QUERYDESK_TOKEN"));
        Statement statement = connection.createStatement()) {
      statement.setMaxRows(5);
      for (int i = 1; i < args.length; i++) {
        System.out.println("> " + args[i]);
        try {
          if (statement.execute(args[i])) {
            print(statement.getResultSet());
          } else {
            System.out.println("  update count " + statement.getUpdateCount());
          }
        } catch (SQLException e) {
          System.out.println("  " + e.getClass().getSimpleName() + " [" + e.getSQLState() + "] " + e.getMessage());
        }
      }
    }
  }

  private static void print(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    StringBuilder header = new StringBuilder("  ");
    for (int c = 1; c <= meta.getColumnCount(); c++) {
      header.append(meta.getColumnLabel(c)).append(':').append(meta.getColumnTypeName(c)).append("  ");
    }
    System.out.println(header);
    while (rs.next()) {
      StringBuilder row = new StringBuilder("  ");
      for (int c = 1; c <= meta.getColumnCount(); c++) {
        Object value = rs.getObject(c);
        row.append(value == null ? "NULL" : value + " (" + value.getClass().getSimpleName() + ")").append("  ");
      }
      System.out.println(row);
    }
  }
}
