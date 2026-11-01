package com.querydesk.jdbc;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;

/**
 * Base for the driver's JDBC objects. JDBC's interfaces run to hundreds of methods, most of
 * which a read-mostly HTTP driver refuses, so each object is a proxy that handles what it
 * supports and refuses the rest by name.
 */
abstract class Handler implements InvocationHandler {
  static <T> T proxy(Class<T> type, Handler handler) {
    return type.cast(Proxy.newProxyInstance(Handler.class.getClassLoader(), new Class<?>[] {type}, handler));
  }

  @Override
  public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Object[] a = args == null ? new Object[0] : args;
    switch (method.getName()) {
      case "toString":
        if (a.length == 0) return getClass().getSimpleName().replace("Handler", "") + "@QueryDesk";
        break;
      case "hashCode":
        if (a.length == 0) return System.identityHashCode(proxy);
        break;
      case "equals":
        if (a.length == 1) return proxy == a[0];
        break;
      case "unwrap":
        if (((Class<?>) a[0]).isInstance(proxy)) return proxy;
        throw new SQLException("Not a wrapper for " + a[0]);
      case "isWrapperFor":
        return ((Class<?>) a[0]).isInstance(proxy);
      default:
        break;
    }
    if (trace == null) return handle(proxy, method, a);

    String call = getClass().getSimpleName() + "." + method.getName() + Arrays.deepToString(a);
    try {
      Object result = handle(proxy, method, a);
      trace(call + " -> " + result);
      return result;
    } catch (SQLException e) {
      trace(call + " !! " + e.getMessage());
      throw e;
    }
  }

  /** The file every JDBC call is appended to, from the URL's {@code trace} parameter, to see what a tool calls. */
  static volatile Path trace;

  private static synchronized void trace(String line) {
    try {
      Files.writeString(trace, line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      // tracing is a debugging aid, so a write that fails never fails the call
    }
  }

  abstract Object handle(Object proxy, Method method, Object[] args) throws SQLException;

  static SQLFeatureNotSupportedException unsupported(Method method) {
    return new SQLFeatureNotSupportedException("The QueryDesk driver doesn't support " + method.getName() + " yet");
  }
}
