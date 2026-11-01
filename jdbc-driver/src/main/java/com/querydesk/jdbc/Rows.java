package com.querydesk.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A result's rows. Streamed ones are read from QueryDesk only as far as the caller asks, so a
 * tool showing the first page never pulls the rest; closing them closes the stream, which stops
 * the query in QueryDesk.
 */
final class Rows implements AutoCloseable {
  private final List<List<Object>> loaded;
  // null once the stream ended or was closed; closed from another thread to cancel a read
  private volatile BufferedReader stream;

  private Rows(List<List<Object>> loaded, BufferedReader stream) {
    this.loaded = loaded;
    this.stream = stream;
  }

  static Rows of(List<List<Object>> rows) {
    return new Rows(rows, null);
  }

  static Rows streamed(BufferedReader stream) {
    return new Rows(new ArrayList<>(), stream);
  }

  /** Whether there is a row at {@code index}, reading up to it. */
  synchronized boolean has(int index) throws SQLException {
    while (index >= loaded.size() && stream != null) readLine();
    return index >= 0 && index < loaded.size();
  }

  synchronized List<Object> get(int index) throws SQLException {
    if (!has(index)) throw new SQLException("No current row", "24000");
    return loaded.get(index);
  }

  /** Every row's count, which reads the rest of the stream. */
  synchronized int size() throws SQLException {
    while (stream != null) readLine();
    return loaded.size();
  }

  @Override
  public void close() {
    BufferedReader open = stream;
    stream = null;
    if (open == null) return;
    try {
      open.close();
    } catch (IOException e) {
      // the stream is abandoned either way
    }
  }

  private void readLine() throws SQLException {
    BufferedReader open = stream;
    String line;
    try {
      line = open.readLine();
    } catch (IOException e) {
      close();
      throw new SQLException("Lost QueryDesk's result partway: " + e.getMessage(), "08006", e);
    }
    // a stream ends with done or error, so anything else means the result was cut short
    if (line == null) {
      close();
      throw new SQLException("QueryDesk's result ended before its last row", "08006");
    }
    if (line.isBlank()) return;

    Map<?, ?> json = (Map<?, ?>) Json.parse(line);
    if (json.get("rows") instanceof List<?> rows) {
      for (Object row : rows) loaded.add(new ArrayList<>((List<?>) row));
    } else if (json.containsKey("done")) {
      close();
    } else if (json.get("error") != null) {
      close();
      throw new SQLSyntaxErrorException(String.valueOf(json.get("error")), "42000");
    }
  }
}
