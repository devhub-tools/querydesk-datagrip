package tools.devhub.querydesk.jdbc;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** A Statement, or a PreparedStatement when it was made with SQL. */
final class StatementHandler extends Handler {
  private final ConnectionHandler connection;
  private final String preparedSql;
  private final Map<Integer, String> parameters = new HashMap<>();
  private Statement proxy;
  private long maxRows;
  private int fetchSize;
  private int queryTimeout;
  private ResultSet resultSet;
  private long updateCount = -1;
  private boolean closed;

  StatementHandler(ConnectionHandler connection, String preparedSql) {
    this.connection = connection;
    this.preparedSql = preparedSql;
  }

  <T extends Statement> T proxy(Class<T> type) {
    T statement = proxy(type, this);
    proxy = statement;
    return statement;
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    String name = method.getName();
    if (closed && !name.equals("close") && !name.equals("isClosed")) throw new SQLException("Statement is closed", "HY010");

    switch (name) {
      case "executeQuery":
        run(sql(a));
        return resultSet == null ? ResultSetHandler.proxy(Result.empty(), proxy) : resultSet;
      case "executeUpdate":
        run(sql(a));
        return (int) Math.max(updateCount, 0);
      case "executeLargeUpdate":
        run(sql(a));
        return Math.max(updateCount, 0);
      case "execute":
        run(sql(a));
        return resultSet != null;
      case "getResultSet":
        return resultSet;
      case "getUpdateCount":
        return (int) updateCount;
      case "getLargeUpdateCount":
        return updateCount;
      case "getMoreResults":
        resultSet = null;
        updateCount = -1;
        return false;
      case "getGeneratedKeys":
        return ResultSetHandler.proxy(Result.empty(), proxy);
      case "getMetaData":
      case "getWarnings":
        return null;
      case "setMaxRows":
        maxRows = (Integer) a[0];
        return null;
      case "setLargeMaxRows":
        maxRows = (Long) a[0];
        return null;
      case "getMaxRows":
        return (int) maxRows;
      case "getLargeMaxRows":
        return maxRows;
      case "setFetchSize":
        fetchSize = (Integer) a[0];
        return null;
      case "getFetchSize":
        return fetchSize;
      // QueryDesk applies the Database's own timeout
      case "setQueryTimeout":
        queryTimeout = (Integer) a[0];
        return null;
      case "getQueryTimeout":
        return queryTimeout;
      case "getFetchDirection":
        return ResultSet.FETCH_FORWARD;
      case "getResultSetType":
        return ResultSet.TYPE_SCROLL_INSENSITIVE;
      case "getResultSetConcurrency":
        return ResultSet.CONCUR_READ_ONLY;
      case "getResultSetHoldability":
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getConnection":
        return connection.proxy();
      case "isPoolable":
      case "isCloseOnCompletion":
        return false;
      case "close":
        closed = true;
        resultSet = null;
        return null;
      case "isClosed":
        return closed;
      case "clearParameters":
        parameters.clear();
        return null;
      // a statement already sent runs to its end in QueryDesk
      case "cancel":
      case "clearWarnings":
      case "setFetchDirection":
      case "setEscapeProcessing":
      case "setCursorName":
      case "setPoolable":
      case "closeOnCompletion":
        return null;
      default:
        if (preparedSql != null && name.startsWith("set") && a.length >= 2 && a[0] instanceof Integer index) {
          parameters.put(index, name.equals("setNull") ? "NULL" : literal(a[1]));
          return null;
        }
        throw unsupported(method);
    }
  }

  private String sql(Object[] a) throws SQLException {
    if (a.length > 0 && a[0] instanceof String sql) return sql;
    if (preparedSql == null) throw new SQLException("No SQL to execute", "07003");
    return bind(preparedSql);
  }

  private void run(String sql) throws SQLException {
    Result result = connection.client.execute(sql, maxRows);
    if (result.hasRows()) {
      resultSet = ResultSetHandler.proxy(result, proxy);
      updateCount = -1;
    } else {
      resultSet = null;
      updateCount = result.updateCount();
    }
  }

  /**
   * QueryDesk takes SQL text and no parameters, so each {@code ?} outside a string or quoted
   * identifier is replaced by its value written as a literal. QueryDesk parses and checks the
   * result like any other statement.
   */
  private String bind(String sql) throws SQLException {
    StringBuilder out = new StringBuilder();
    int index = 0;
    char quote = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
        out.append(c);
      } else if (c == '\'' || c == '"') {
        quote = c;
        out.append(c);
      } else if (c == '?') {
        String value = parameters.get(++index);
        if (value == null) throw new SQLException("No value specified for parameter " + index, "07001");
        out.append(value);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String literal(Object value) throws SQLException {
    if (value == null) return "NULL";
    if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
    if (value instanceof BigDecimal d) return d.toPlainString();
    if (value instanceof Number) return value.toString();
    if (value instanceof byte[]) throw new SQLException("Binary parameters aren't supported yet", "0A000");
    return "'" + value.toString().replace("'", "''") + "'";
  }
}
