package com.querydesk.jdbc;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** An array column's value, which QueryDesk sends as a JSON list. Its elements keep their JSON types. */
final class ArrayHandler extends Handler {
  private final List<?> elements;
  // an empty array's element type, from the column's other values; JSON has none for it
  private final Class<?> fallback;

  ArrayHandler(List<?> elements, Class<?> fallback) {
    this.elements = elements;
    this.fallback = fallback;
  }

  /** The Java class an array's elements are given as, or null for one with no element to tell by. */
  static Class<?> elementClass(List<?> elements) {
    Class<?> type = elements.stream().filter(e -> e != null).findFirst().<Class<?>>map(Object::getClass).orElse(null);
    return type == null || type == Long.class || type == Double.class || type == Boolean.class ? type : String.class;
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
    Class<?> type = elementClass(elements);
    if (type == null) type = fallback != null ? fallback : String.class;
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
