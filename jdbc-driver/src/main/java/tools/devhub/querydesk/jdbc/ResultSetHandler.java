package tools.devhub.querydesk.jdbc;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

/** Rows QueryDesk returned, held in memory and read with each column's QueryDesk type. */
final class ResultSetHandler extends Handler {
  private final Result result;
  private final Statement statement;
  private int row = -1;
  private boolean wasNull;
  private boolean closed;

  private ResultSetHandler(Result result, Statement statement) {
    this.result = result;
    this.statement = statement;
  }

  static ResultSet proxy(Result result, Statement statement) {
    return proxy(ResultSet.class, new ResultSetHandler(result, statement));
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    String name = method.getName();
    int size = result.rows().size();

    switch (name) {
      case "next":
        row = Math.min(row + 1, size);
        return row < size;
      case "previous":
        row = Math.max(row - 1, -1);
        return row >= 0;
      case "first":
        row = 0;
        return size > 0;
      case "last":
        row = size - 1;
        return size > 0;
      case "beforeFirst":
        row = -1;
        return null;
      case "afterLast":
        row = size;
        return null;
      case "absolute": {
        int n = (Integer) a[0];
        // 0 is before the first row, and a negative n counts back from the last
        row = Math.max(-1, Math.min(size, n > 0 ? n - 1 : n == 0 ? -1 : size + n));
        return row >= 0 && row < size;
      }
      case "relative":
        row = Math.max(-1, Math.min(size, row + (Integer) a[0]));
        return row >= 0 && row < size;
      case "getRow":
        return row >= 0 && row < size ? row + 1 : 0;
      case "isBeforeFirst":
        return row == -1 && size > 0;
      case "isAfterLast":
        return row >= size && size > 0;
      case "isFirst":
        return row == 0 && size > 0;
      case "isLast":
        return row == size - 1 && size > 0;
      case "close":
        closed = true;
        return null;
      case "isClosed":
        return closed;
      case "getMetaData":
        return proxy(ResultSetMetaData.class, new ResultSetMetaDataHandler(result));
      case "findColumn":
        return column(a[0]) + 1;
      case "wasNull":
        return wasNull;
      case "getStatement":
        return statement;
      case "getType":
        return ResultSet.TYPE_SCROLL_INSENSITIVE;
      case "getConcurrency":
        return ResultSet.CONCUR_READ_ONLY;
      case "getHoldability":
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getFetchDirection":
        return ResultSet.FETCH_FORWARD;
      case "getFetchSize":
        return 0;
      case "getWarnings":
      case "clearWarnings":
      case "setFetchSize":
      case "setFetchDirection":
        return null;
      default:
        if (name.startsWith("get") && a.length >= 1 && (a[0] instanceof Integer || a[0] instanceof String)) {
          return get(method, a);
        }
        throw unsupported(method);
    }
  }

  private Object get(Method method, Object[] a) throws SQLException {
    if (row < 0 || row >= result.rows().size()) throw new SQLException("No current row", "24000");
    int column = column(a[0]);
    Object raw = result.rows().get(row).get(column);
    String type = result.types().get(column);
    wasNull = raw == null;

    Class<?> returns = method.getReturnType();
    if (raw == null) return returns.isPrimitive() ? zero(returns) : null;

    try {
      switch (method.getName()) {
        case "getObject":
          if (a.length > 1 && a[1] instanceof Class<?> target) return as(target, raw, type);
          return natural(raw, type);
        case "getString":
        case "getNString":
          return text(raw, type);
        case "getBoolean":
          if (raw instanceof Boolean b) return b;
          if (raw instanceof Number n) return n.doubleValue() != 0;
          return List.of("t", "true", "1", "y", "yes").contains(raw.toString().toLowerCase());
        case "getByte":
          return decimal(raw).byteValue();
        case "getShort":
          return decimal(raw).shortValue();
        case "getInt":
          return decimal(raw).intValue();
        case "getLong":
          return decimal(raw).longValue();
        case "getFloat":
          return decimal(raw).floatValue();
        case "getDouble":
          return decimal(raw).doubleValue();
        case "getBigDecimal":
          return decimal(raw);
        case "getBytes":
          return bytes(raw, type);
        case "getDate":
          return Date.valueOf(localDateTime(raw, type).toLocalDate());
        case "getTime":
          return Time.valueOf(type.equals("time") ? LocalTime.parse((String) raw) : localDateTime(raw, type).toLocalTime());
        case "getTimestamp":
          return timestamp(raw, type);
        case "getCharacterStream":
        case "getNCharacterStream":
          return new StringReader(text(raw, type));
        case "getAsciiStream":
        case "getBinaryStream":
          return new ByteArrayInputStream(bytes(raw, type));
        case "getArray":
          return proxy(Array.class, new ArrayHandler((List<?>) raw));
        default:
          throw unsupported(method);
      }
    } catch (RuntimeException e) {
      throw new SQLDataException("Can't read " + type + " column " + result.names().get(column) + " with " + method.getName(), "22000", e);
    }
  }

  private int column(Object key) throws SQLException {
    if (key instanceof Integer index) {
      if (index < 1 || index > result.names().size()) throw new SQLException("No column " + index, "07009");
      return index - 1;
    }
    List<String> names = result.names();
    for (int i = 0; i < names.size(); i++) {
      if (names.get(i).equalsIgnoreCase((String) key)) return i;
    }
    throw new SQLException("No column " + key, "42703");
  }

  static Object natural(Object raw, String type) {
    switch (type) {
      case "integer":
        return raw;
      case "float":
        return ((Number) raw).doubleValue();
      case "decimal":
        return new BigDecimal((String) raw);
      case "date":
        return Date.valueOf(LocalDate.parse((String) raw));
      case "time":
        return Time.valueOf(LocalTime.parse((String) raw));
      case "timestamp":
      case "timestamptz":
        return timestamp(raw, type);
      case "binary":
        return Base64.getDecoder().decode((String) raw);
      default:
        return text(raw, type);
    }
  }

  private static Object as(Class<?> target, Object raw, String type) throws SQLException {
    if (target == String.class) return text(raw, type);
    if (target == Long.class) return decimal(raw).longValue();
    if (target == Integer.class) return decimal(raw).intValue();
    if (target == Double.class) return decimal(raw).doubleValue();
    if (target == BigDecimal.class) return decimal(raw);
    if (target == Boolean.class && raw instanceof Boolean) return raw;
    if (target == LocalDate.class) return localDateTime(raw, type).toLocalDate();
    if (target == LocalTime.class) return type.equals("time") ? LocalTime.parse((String) raw) : localDateTime(raw, type).toLocalTime();
    if (target == LocalDateTime.class) return localDateTime(raw, type);
    if (target == OffsetDateTime.class && type.equals("timestamptz")) return OffsetDateTime.parse((String) raw);
    if (target == byte[].class) return bytes(raw, type);
    Object natural = natural(raw, type);
    if (target.isInstance(natural)) return natural;
    throw new SQLDataException("Can't read a " + type + " value as " + target.getName(), "22000");
  }

  static String text(Object raw, String type) {
    if (raw instanceof String s) return s;
    if (raw instanceof Boolean || raw instanceof Number) return raw.toString();
    return Json.write(raw);
  }

  private static BigDecimal decimal(Object raw) {
    if (raw instanceof Number n) return new BigDecimal(n.toString());
    if (raw instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
    return new BigDecimal(raw.toString().trim());
  }

  private static byte[] bytes(Object raw, String type) {
    if (type.equals("binary")) return Base64.getDecoder().decode((String) raw);
    return text(raw, type).getBytes(StandardCharsets.UTF_8);
  }

  private static LocalDateTime localDateTime(Object raw, String type) {
    String value = (String) raw;
    switch (type) {
      case "date":
        return LocalDate.parse(value).atStartOfDay();
      case "timestamptz":
        return OffsetDateTime.parse(value).toLocalDateTime();
      default:
        return LocalDateTime.parse(value);
    }
  }

  private static Timestamp timestamp(Object raw, String type) {
    if (type.equals("timestamptz")) return Timestamp.from(OffsetDateTime.parse((String) raw).toInstant());
    return Timestamp.valueOf(localDateTime(raw, type));
  }

  private static Object zero(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0f;
    return 0d;
  }

  /** The java.sql.Types code and Java class a QueryDesk column type reads as. */
  static int sqlType(String type) {
    switch (type) {
      case "integer":
        return Types.BIGINT;
      case "float":
        return Types.DOUBLE;
      case "decimal":
        return Types.NUMERIC;
      case "boolean":
        return Types.BOOLEAN;
      case "date":
        return Types.DATE;
      case "time":
        return Types.TIME;
      case "timestamp":
        return Types.TIMESTAMP;
      case "timestamptz":
        return Types.TIMESTAMP_WITH_TIMEZONE;
      case "binary":
        return Types.BINARY;
      default:
        return Types.VARCHAR;
    }
  }

  static String className(String type) {
    switch (type) {
      case "integer":
        return Long.class.getName();
      case "float":
        return Double.class.getName();
      case "decimal":
        return BigDecimal.class.getName();
      case "boolean":
        return Boolean.class.getName();
      case "date":
        return Date.class.getName();
      case "time":
        return Time.class.getName();
      case "timestamp":
      case "timestamptz":
        return Timestamp.class.getName();
      case "binary":
        return byte[].class.getName();
      default:
        return String.class.getName();
    }
  }
}
