package tools.devhub.querydesk.jdbc;

import java.util.List;

/** One statement's outcome: rows with each column's QueryDesk type, or an update count. */
record Result(List<String> names, List<String> types, List<List<Object>> rows, long updateCount) {
  static Result rows(List<String> names, List<String> types, List<List<Object>> rows) {
    return new Result(names, types, rows, -1);
  }

  static Result count(long updateCount) {
    return new Result(List.of(), List.of(), null, updateCount);
  }

  static Result empty() {
    return rows(List.of(), List.of(), List.of());
  }

  boolean hasRows() {
    return rows != null;
  }
}
