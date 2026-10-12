package com.convoy.offline;

import java.io.IOException;
import java.util.*;

/** Bounded JSON reader shared by map manifests and PMTiles metadata; duplicate keys are rejected. */
final class StrictJson {
    private final String text; private int cursor, values;
    private StrictJson(String text) { this.text = text; }
    static Object parse(String text) throws IOException {
        if (text == null || text.length() > 2 * 1024 * 1024) throw new IOException("JSON document is too large");
        StrictJson parser = new StrictJson(text); Object value = parser.value(0); parser.whitespace();
        if (parser.cursor != text.length()) throw new IOException("Unexpected JSON trailing data"); return value;
    }
    private Object value(int depth) throws IOException {
        if (depth > 32 || ++values > 100000) throw new IOException("JSON structure is too complex");
        whitespace(); if (cursor >= text.length()) throw bad(); char c = text.charAt(cursor);
        if (c == '{') {
            cursor++; LinkedHashMap<String,Object> object = new LinkedHashMap<>(); whitespace();
            if (take('}')) return object;
            do { whitespace(); if (cursor >= text.length() || text.charAt(cursor) != '"') throw bad();
                String key = string(); whitespace(); require(':'); if (object.containsKey(key)) throw new IOException("Duplicate JSON field");
                object.put(key, value(depth + 1)); whitespace(); if (take('}')) return object; require(',');
            } while (true);
        }
        if (c == '[') { cursor++; ArrayList<Object> array = new ArrayList<>(); whitespace(); if (take(']')) return array;
            do { array.add(value(depth + 1)); whitespace(); if (take(']')) return array; require(','); } while (true);
        }
        if (c == '"') return string();
        if (text.startsWith("true", cursor)) { cursor += 4; return Boolean.TRUE; }
        if (text.startsWith("false", cursor)) { cursor += 5; return Boolean.FALSE; }
        if (text.startsWith("null", cursor)) { cursor += 4; return null; }
        int start = cursor; if (take('-') && cursor >= text.length()) throw bad();
        if (take('0')) { if (cursor < text.length() && Character.isDigit(text.charAt(cursor))) throw bad(); }
        else { if (cursor >= text.length() || text.charAt(cursor) < '1' || text.charAt(cursor) > '9') throw bad(); while (cursor < text.length() && digit(text.charAt(cursor))) cursor++; }
        boolean integer = true;
        if (take('.')) { integer = false; int after = cursor; while (cursor < text.length() && digit(text.charAt(cursor))) cursor++; if (after == cursor) throw bad(); }
        if (take('e') || take('E')) { integer = false; if (!take('+')) take('-'); int after = cursor; while (cursor < text.length() && digit(text.charAt(cursor))) cursor++; if (after == cursor) throw bad(); }
        try { String number = text.substring(start,cursor); if (integer) return Long.valueOf(number); double d = Double.parseDouble(number); if (!Double.isFinite(d)) throw bad(); return d; }
        catch (NumberFormatException invalid) { throw bad(); }
    }
    private String string() throws IOException {
        require('"'); StringBuilder value = new StringBuilder();
        while (cursor < text.length()) { char c = text.charAt(cursor++); if (c == '"') return value.toString(); if (c < 32) throw bad();
            if (c == '\\') { if (cursor == text.length()) throw bad(); char escaped = text.charAt(cursor++);
                switch (escaped) { case '"': case '\\': case '/': c = escaped; break; case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break; case 'u':
                        if (cursor + 4 > text.length()) throw bad(); try { c = (char)Integer.parseInt(text.substring(cursor,cursor+4),16); } catch (NumberFormatException invalid) { throw bad(); } cursor += 4; break;
                    default: throw bad(); }
            }
            value.append(c); if (value.length() > 1024 * 1024) throw new IOException("JSON string is too large");
        }
        throw bad();
    }
    private void whitespace() { while (cursor < text.length() && " \t\r\n".indexOf(text.charAt(cursor)) >= 0) cursor++; }
    private boolean take(char c) { if (cursor < text.length() && text.charAt(cursor) == c) { cursor++; return true; } return false; }
    private void require(char c) throws IOException { if (!take(c)) throw bad(); }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private IOException bad() { return new IOException("Malformed JSON document"); }
}
