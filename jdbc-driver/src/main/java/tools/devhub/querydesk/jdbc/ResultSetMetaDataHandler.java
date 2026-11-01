package tools.devhub.querydesk.jdbc;

import java.lang.reflect.Method;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Column names and types of a Result. QueryDesk sends no table, precision or nullability. */
final class ResultSetMetaDataHandler extends Handler {
  private final Result result;

  ResultSetMetaDataHandler(Result result) {
    this.result = result;
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    if (method.getName().equals("getColumnCount")) return result.names().size();

    int index = (Integer) a[0];
    if (index < 1 || index > result.names().size()) throw new SQLException("No column " + index, "07009");
    String type = result.types().get(index - 1);

    switch (method.getName()) {
      case "getColumnName":
      case "getColumnLabel":
        return result.names().get(index - 1);
      case "getColumnType":
        return ResultSetHandler.sqlType(type);
      case "getColumnTypeName":
        return type;
      case "getColumnClassName":
        return ResultSetHandler.className(type);
      case "getColumnDisplaySize":
        return 40;
      case "getPrecision":
      case "getScale":
        return 0;
      case "isNullable":
        return ResultSetMetaData.columnNullableUnknown;
      case "isSigned":
        return type.equals("integer") || type.equals("float") || type.equals("decimal");
      case "isCaseSensitive":
      case "isSearchable":
      case "isReadOnly":
        return true;
      case "isAutoIncrement":
      case "isCurrency":
      case "isWritable":
      case "isDefinitelyWritable":
        return false;
      case "getSchemaName":
      case "getTableName":
      case "getCatalogName":
        return "";
      default:
        throw unsupported(method);
    }
  }
}
