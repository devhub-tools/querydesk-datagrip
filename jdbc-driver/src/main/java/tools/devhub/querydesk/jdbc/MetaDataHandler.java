package tools.devhub.querydesk.jdbc;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.List;

/**
 * Reports the database as the product and version the URL names, so a tool picks that SQL
 * dialect and reads the catalog with its own queries. The JDBC catalog methods (getTables and
 * the like) return no rows, except for MongoDB ({@link StoredSchema}); anything else answers
 * false, 0 or "".
 */
final class MetaDataHandler extends Handler {
  private final ConnectionHandler connection;
  // MongoDB has no SQL catalog for a tool's own queries, so its catalog methods read QueryDesk's
  private final StoredSchema storedSchema;

  MetaDataHandler(ConnectionHandler connection) {
    this.connection = connection;
    this.storedSchema = connection.storedSchema;
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    String[] version = connection.version.split("\\.");

    if (storedSchema != null) {
      switch (method.getName()) {
        case "getCatalogs":
          return ResultSetHandler.proxy(storedSchema.catalogs(), null);
        case "getSchemas":
          return ResultSetHandler.proxy(storedSchema.schemas(), null);
        case "getTables":
          return ResultSetHandler.proxy(storedSchema.tables((String) a[0], (String) a[1], (String) a[2]), null);
        case "getColumns":
          return ResultSetHandler.proxy(storedSchema.columns((String) a[0], (String) a[1], (String) a[2], (String) a[3]), null);
        case "getPrimaryKeys":
          return ResultSetHandler.proxy(storedSchema.primaryKeys((String) a[0], (String) a[1], (String) a[2]), null);
        case "getTableTypes":
          return ResultSetHandler.proxy(Result.rows(List.of("TABLE_TYPE"), List.of("text"), List.of(List.of("TABLE"))), null);
        case "getCatalogTerm":
          return "database";
        default:
          break;
      }
    }

    switch (method.getName()) {
      case "getConnection":
        return connection.proxy();
      case "getURL":
        return connection.url;
      case "getDatabaseProductName":
        return connection.product;
      case "getDatabaseProductVersion":
        return connection.version;
      case "getDatabaseMajorVersion":
        return Integer.parseInt(version[0]);
      case "getDatabaseMinorVersion":
        return version.length > 1 ? Integer.parseInt(version[1]) : 0;
      case "getDriverName":
        return "QueryDesk JDBC";
      case "getDriverVersion":
        return QueryDeskDriver.MAJOR_VERSION + "." + QueryDeskDriver.MINOR_VERSION;
      case "getDriverMajorVersion":
        return QueryDeskDriver.MAJOR_VERSION;
      case "getDriverMinorVersion":
        return QueryDeskDriver.MINOR_VERSION;
      case "getJDBCMajorVersion":
        return 4;
      case "getJDBCMinorVersion":
        return 2;
      case "getIdentifierQuoteString":
        return "\"";
      case "getSearchStringEscape":
        return "\\";
      case "getCatalogSeparator":
        return ".";
      case "getCatalogTerm":
        return "database";
      case "getSchemaTerm":
        return "schema";
      case "getProcedureTerm":
        return "function";
      case "getDefaultTransactionIsolation":
        return Connection.TRANSACTION_READ_COMMITTED;
      case "getResultSetHoldability":
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getSQLStateType":
        return DatabaseMetaData.sqlStateSQL;
      case "getRowIdLifetime":
        return RowIdLifetime.ROWID_UNSUPPORTED;
      case "supportsResultSetType":
        return (Integer) a[0] != ResultSet.TYPE_SCROLL_SENSITIVE;
      case "supportsResultSetConcurrency":
        return (Integer) a[1] == ResultSet.CONCUR_READ_ONLY;
      case "storesLowerCaseIdentifiers":
      case "supportsMixedCaseQuotedIdentifiers":
      case "nullsAreSortedHigh":
      case "supportsSchemasInDataManipulation":
      case "supportsSchemasInTableDefinitions":
      case "supportsCatalogsInDataManipulation":
        return true;
      default:
        Class<?> returns = method.getReturnType();
        if (returns == boolean.class) return false;
        if (returns == int.class) return 0;
        if (returns == long.class) return 0L;
        if (returns == String.class) return "";
        if (returns == ResultSet.class) return ResultSetHandler.proxy(Result.empty(), null);
        return null;
    }
  }
}
