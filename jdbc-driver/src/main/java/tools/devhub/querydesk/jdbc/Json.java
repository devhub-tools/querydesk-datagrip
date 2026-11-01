package tools.devhub.querydesk.jdbc;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the statements API, so the jar has no dependencies to clash with its host's. */
final class Json {
  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text;
  }

  static Object parse(String text) {
    Json json = new Json(text);
    json.skipWhitespace();
    Object value = json.readValue();
    json.skipWhitespace();
    if (json.pos != text.length()) throw json.error("trailing characters");
    return value;
  }

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      out.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
          }
        }
      }
      out.append('"');
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) out.append(',');
        first = false;
        write(String.valueOf(entry.getKey()), out);
        out.append(':');
        write(entry.getValue(), out);
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) out.append(',');
        write(list.get(i), out);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException("can't write " + value.getClass());
    }
  }

  private Object readValue() {
    if (pos >= text.length()) throw error("unexpected end");
    char c = text.charAt(pos);
    switch (c) {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return readString();
      case 't':
        expect("true");
        return Boolean.TRUE;
      case 'f':
        expect("false");
        return Boolean.FALSE;
      case 'n':
        expect("null");
        return null;
      default:
        return readNumber();
    }
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      if (text.charAt(pos++) != ':') throw error("expected ':'");
      skipWhitespace();
      map.put(key, readValue());
      skipWhitespace();
      char c = text.charAt(pos++);
      if (c == '}') return map;
      if (c != ',') throw error("expected ',' or '}'");
    }
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<>();
    pos++;
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(readValue());
      skipWhitespace();
      char c = text.charAt(pos++);
      if (c == ']') return list;
      if (c != ',') throw error("expected ',' or ']'");
    }
  }

  private String readString() {
    if (text.charAt(pos++) != '"') throw error("expected string");
    StringBuilder out = new StringBuilder();
    while (true) {
      char c = text.charAt(pos++);
      if (c == '"') return out.toString();
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char escaped = text.charAt(pos++);
      switch (escaped) {
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> out.append(escaped);
      }
    }
  }

  private Object readNumber() {
    int start = pos;
    while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
    String number = text.substring(start, pos);
    if (number.isEmpty()) throw error("unexpected character");
    if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
    BigInteger integer = new BigInteger(number);
    return integer.bitLength() < 64 ? (Object) integer.longValue() : integer;
  }

  private void expect(String word) {
    if (!text.startsWith(word, pos)) throw error("expected " + word);
    pos += word.length();
  }

  private char peek() {
    return pos < text.length() ? text.charAt(pos) : 0;
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException("Invalid JSON from QueryDesk at " + pos + ": " + message);
  }
}
