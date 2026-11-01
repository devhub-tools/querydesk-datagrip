package com.querydesk.jdbc;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Properties;

/** A connection to QueryDesk. It holds no session on the database, so every statement commits on its own. */
final class ConnectionHandler extends Handler {
  final Client client;
  final String url;
  final String product;
  final String version;
  // MongoDB's catalog, read from QueryDesk once per connection; null for SQL engines
  final StoredSchema storedSchema;
  private final Connection proxy;
  private boolean closed;

  ConnectionHandler(Client client, String url, String product, String version) {
    this.client = client;
    this.url = url;
    this.product = product;
    this.version = version;
    this.storedSchema = "MongoDB".equals(product) ? new StoredSchema(client) : null;
    this.proxy = proxy(Connection.class, this);
  }

  Connection proxy() {
    return proxy;
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    String name = method.getName();
    if (closed && !name.equals("close") && !name.equals("isClosed") && !name.equals("isValid")) {
      throw new SQLNonTransientConnectionException("Connection is closed", "08003");
    }

    switch (name) {
      case "createStatement":
        return new StatementHandler(this, null).proxy(Statement.class);
      case "prepareStatement":
        return new StatementHandler(this, (String) a[0]).proxy(PreparedStatement.class);
      case "getMetaData":
        return proxy(DatabaseMetaData.class, new MetaDataHandler(this));
      case "getAutoCommit":
        return true;
      case "setAutoCommit":
        if (!(Boolean) a[0]) {
          throw new SQLFeatureNotSupportedException(
              "QueryDesk's driver runs each statement on its own, so it can't hold a transaction open yet");
        }
        return null;
      case "commit":
      case "rollback":
        if (a.length == 0) return null;
        throw unsupported(method);
      case "close":
      case "abort":
        closed = true;
        return null;
      case "isClosed":
        return closed;
      case "isValid":
        return !closed;
      case "isReadOnly":
        return false;
      case "getTransactionIsolation":
        return Connection.TRANSACTION_READ_COMMITTED;
      case "getHoldability":
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getClientInfo":
        return a.length == 0 ? new Properties() : null;
      case "getTypeMap":
        return new HashMap<String, Class<?>>();
      case "getNetworkTimeout":
        return 0;
      case "nativeSQL":
        return a[0];
      // each statement is sent alone, so there is no session for these to change
      case "getCatalog":
      case "getSchema":
      case "getWarnings":
      case "setReadOnly":
      case "setCatalog":
      case "setSchema":
      case "setTransactionIsolation":
      case "setHoldability":
      case "setClientInfo":
      case "setTypeMap":
      case "setNetworkTimeout":
      case "clearWarnings":
        return null;
      default:
        throw unsupported(method);
    }
  }
}
