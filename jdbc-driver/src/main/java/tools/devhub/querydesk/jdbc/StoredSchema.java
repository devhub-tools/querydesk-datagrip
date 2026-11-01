package tools.devhub.querydesk.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC's catalog methods answered from the schema QueryDesk last synced, for MongoDB, which
 * has no SQL catalog a tool can query. The database is both the catalog and the schema, as
 * DataGrip asks for it, each collection a table and each field path (address.city) a column.
 * Read once per connection.
 */
final class StoredSchema {
  private static final List<String> TABLE_COLUMNS = List.of(
      "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
      "SELF_REFERENCING_COL_NAME", "REF_GENERATION");
  private static final List<String> COLUMN_COLUMNS = List.of(
      "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE",
      "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE",
      "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA",
      "SCOPE_TABLE", "SOURCE_DATA_TYPE", "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN");
  private static final List<String> KEY_COLUMNS = List.of("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");

  private static final Pattern SIBLING = Pattern.compile("\\s*db\\.getSiblingDB\\(\\s*([\"'])(.*?)\\1\\s*\\)");

  private static final Pattern USE = Pattern.compile("\\s*use\\s+([^\\s;]+)\\s*;?\\s*");

  private final Client client;
  private Map<?, ?> schema;

  StoredSchema(Client client) {
    this.client = client;
  }

  Result catalogs() throws SQLException {
    return rows(List.of("TABLE_CAT"), List.of(List.of(database())));
  }

  Result schemas() throws SQLException {
    return rows(List.of("TABLE_SCHEM", "TABLE_CATALOG"), List.of(List.of(database(), database())));
  }

  Result tables(String catalog, String schemaPattern, String tablePattern) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    if (inDatabase(catalog, schemaPattern)) {
      for (Map<?, ?> table : tables()) {
        String name = (String) table.get("name");
        if (matches(tablePattern, name)) rows.add(Arrays.asList(database(), database(), name, "TABLE", null, null, null, null, null, null));
      }
    }
    return rows(TABLE_COLUMNS, rows);
  }

  Result columns(String catalog, String schemaPattern, String tablePattern, String columnPattern) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    if (inDatabase(catalog, schemaPattern)) {
      for (Map<?, ?> table : tables()) {
        String tableName = (String) table.get("name");
        if (!matches(tablePattern, tableName)) continue;
        long position = 0;
        for (Object entry : (List<?>) table.get("columns")) {
          Map<?, ?> column = (Map<?, ?>) entry;
          String name = (String) column.get("name");
          position++;
          if (!matches(columnPattern, name)) continue;
          String type = String.valueOf(column.get("type"));
          rows.add(Arrays.asList(database(), database(), tableName, name, (long) sqlType(type), type, null, null, null, null,
              (long) DatabaseMetaData.columnNullable, null, null, null, null, null, position, "YES", null, null, null,
              null, "NO", "NO"));
        }
      }
    }
    return rows(COLUMN_COLUMNS, rows);
  }

  Result primaryKeys(String catalog, String schema, String table) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    if (inDatabase(catalog, schema)) {
      for (Map<?, ?> stored : tables()) {
        if (!stored.get("name").equals(table)) continue;
        long sequence = 0;
        for (Object entry : (List<?>) stored.get("columns")) {
          Map<?, ?> column = (Map<?, ?>) entry;
          if (Boolean.TRUE.equals(column.get("primary_key"))) {
            rows.add(Arrays.asList(database(), database(), table, column.get("name"), ++sequence, "_id_"));
          }
        }
      }
    }
    return rows(KEY_COLUMNS, rows);
  }

  /**
   * {@code db.getSiblingDB("<this database>")} written as {@code db}, which it is: a QueryDesk
   * credential reaches one database, and DataGrip names it when it opens a collection. Another
   * database's name is left for QueryDesk to refuse.
   */
  String ownDatabase(String statement) throws SQLException {
    Matcher sibling = SIBLING.matcher(statement);
    return sibling.lookingAt() && sibling.group(2).equals(database()) ? "db" + statement.substring(sibling.end()) : statement;
  }

  /**
   * Whether {@code statement} is mongosh's {@code use <database>}, which DataGrip sends to point
   * a console at the database. Its own database is where every statement already runs; another
   * is out of the credential's reach.
   */
  boolean isUse(String statement) throws SQLException {
    Matcher use = USE.matcher(statement);
    if (!use.matches()) return false;
    if (!use.group(1).equals(database())) {
      throw new SQLException("This QueryDesk credential reaches only the " + database() + " database", "3D000");
    }
    return true;
  }

  private String database() throws SQLException {
    return (String) schema().get("database");
  }

  private List<Map<?, ?>> tables() throws SQLException {
    List<Map<?, ?>> tables = new ArrayList<>();
    for (Object table : (List<?>) schema().get("tables")) tables.add((Map<?, ?>) table);
    return tables;
  }

  private synchronized Map<?, ?> schema() throws SQLException {
    if (schema == null) schema = client.schema();
    return schema;
  }

  private boolean inDatabase(String catalog, String schemaPattern) throws SQLException {
    return (catalog == null || catalog.equals(database())) && matches(schemaPattern, database());
  }

  // a JDBC pattern: % is any run of characters, _ any one, and null matches everything
  private static boolean matches(String pattern, String name) {
    if (pattern == null || pattern.equals("%")) return true;
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\' && i + 1 < pattern.length()) regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
      else if (c == '%') regex.append(".*");
      else if (c == '_') regex.append('.');
      else regex.append(Pattern.quote(String.valueOf(c)));
    }
    return name.matches(regex.toString());
  }

  // QueryDesk's names for BSON types; a field seen with several ("null, string") takes its first non-null one
  private static int sqlType(String types) {
    String type = Arrays.stream(types.split(",\\s*")).filter(t -> !t.equals("null")).findFirst().orElse("null");
    switch (type) {
      case "string":
        return Types.VARCHAR;
      case "int":
      case "long":
        return Types.BIGINT;
      case "double":
        return Types.DOUBLE;
      case "decimal":
        return Types.DECIMAL;
      case "bool":
        return Types.BOOLEAN;
      case "date":
        return Types.TIMESTAMP;
      case "array":
        return Types.ARRAY;
      case "binData":
        return Types.BINARY;
      default:
        return Types.OTHER;
    }
  }

  // a column holding numbers reads as integer, the rest as text
  private static Result rows(List<String> names, List<List<Object>> rows) {
    List<String> types = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      int column = i;
      boolean number = rows.stream().anyMatch(row -> row.get(column) instanceof Long);
      types.add(number ? "integer" : "text");
    }
    return Result.rows(names, types, rows);
  }
}
