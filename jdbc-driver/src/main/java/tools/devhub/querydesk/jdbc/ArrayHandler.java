package tools.devhub.querydesk.jdbc;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** An array column's value, which QueryDesk sends as a JSON list. Its elements keep their JSON types. */
final class ArrayHandler extends Handler {
  private final List<?> elements;

  ArrayHandler(List<?> elements) {
    this.elements = elements;
  }

  @Override
  Object handle(Object self, Method method, Object[] a) throws SQLException {
    switch (method.getName()) {
      case "getArray":
        // getArray(index, count) takes a 1-based slice; getArray() and getArray(map) take all of it
        if (a.length >= 2) {
          int from = (int) ((Long) a[0] - 1);
          return typed(elements.subList(from, Math.min(elements.size(), from + (Integer) a[1])));
        }
        return typed(elements);
      case "getBaseType":
        return elements.stream().filter(e -> e != null).findFirst().map(ArrayHandler::sqlType).orElse(Types.VARCHAR);
      case "getBaseTypeName":
        return elements.stream().filter(e -> e != null).findFirst().map(ArrayHandler::typeName).orElse("text");
      case "free":
        return null;
      default:
        throw unsupported(method);
    }
  }

  /** A Long[], Double[], Boolean[] or String[] like PgJDBC's: clients convert by the array's class, not its elements'. */
  private Object[] typed(List<?> slice) {
    Class<?> type = elements.stream().filter(e -> e != null).findFirst().<Class<?>>map(Object::getClass).orElse(String.class);
    if (type != Long.class && type != Double.class && type != Boolean.class) type = String.class;
    Object[] array = (Object[]) java.lang.reflect.Array.newInstance(type, slice.size());
    for (int i = 0; i < array.length; i++) {
      Object element = slice.get(i);
      array[i] = element == null || type.isInstance(element) ? element : String.valueOf(element);
    }
    return array;
  }

  private static int sqlType(Object element) {
    if (element instanceof Long) return Types.BIGINT;
    if (element instanceof Double) return Types.DOUBLE;
    if (element instanceof Boolean) return Types.BOOLEAN;
    return Types.VARCHAR;
  }

  private static String typeName(Object element) {
    if (element instanceof Long) return "integer";
    if (element instanceof Double) return "float";
    if (element instanceof Boolean) return "boolean";
    return "text";
  }
}
