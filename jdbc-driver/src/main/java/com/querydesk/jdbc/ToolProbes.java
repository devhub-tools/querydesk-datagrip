package com.querydesk.jdbc;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DataGrip's introspection queries an engine on MySQL's protocol can't run, answered as the
 * engine would, so a tool shows no warning for what the engine doesn't have. Matched on the
 * exact text DataGrip sends (or its "-- Retrieve ..." header), so a member's own query still
 * reaches the database.
 */
final class ToolProbes {
  // the variables DataGrip reads from MySQL 8, with the value each engine logs in with
  private static final Map<String, Map<String, Result>> VARIABLES = Map.of(
      "doris", Map.of("select @@default_authentication_plugin as default_plugin", variable("default_plugin", "mysql_native_password")),
      "tidb", Map.of("select @@authentication_policy as policy", variable("policy", "*,,")));

  // StarRocks keeps its users and grants outside a mysql database, so DataGrip's read of mysql.* has none
  private static final Map<String, List<String>> NO_ROWS = Map.of(
      "starrocks", List.of("-- retrieve users", "-- retrieve role grants", "-- retrieve routine grants"));

  private ToolProbes() {}

  /** The engine's answer to a probe it can't run, or null for any other statement. */
  static Result answer(String engine, String sql) {
    if (engine == null) return null;
    String statement = sql.strip().toLowerCase(Locale.ROOT);
    Result variable = VARIABLES.getOrDefault(engine, Map.of()).get(statement);
    if (variable != null) return variable;
    for (String header : NO_ROWS.getOrDefault(engine, List.of())) {
      if (statement.startsWith(header)) return Result.empty();
    }
    return null;
  }

  private static Result variable(String name, String value) {
    return Result.rows(List.of(name), List.of("text"), List.of(List.of(value)));
  }
}
