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
  // QueryDesk's name for the engine (doris), which the DataGrip plugin passes; null from other tools
  final String engine;
  // the catalog as QueryDesk last synced it, read once per connection
  final StoredSchema storedSchema;
  // mongosh rather than SQL, whose own database's db.getSiblingDB and use are rewritten
  final boolean mongo;
  private final Connection proxy;
  private boolean closed;

  ConnectionHandler(Client client, String url, String product, String version, String engine) {
    this.client = client;
    this.url = url;
    this.product = product;
    this.version = version;
    this.engine = engine;
    this.mongo = "MongoDB".equals(product);
    this.storedSchema = new StoredSchema(client, product);
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
        if (!closed) client.close();
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
      // the database and schema a statement runs in, which a tool selects to introspect by default
      case "getCatalog":
        return storedSchema.catalog();
      case "getSchema":
        return storedSchema.currentSchema();
      // each statement is sent alone, so there is no session for these to change
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
