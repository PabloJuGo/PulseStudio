package com.pulsestudio.desktop.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/serializer (no external dependencies, so jpackage ships only the JDK).
 * Objects map to {@code LinkedHashMap<String,Object>}, arrays to {@code List<Object>},
 * numbers to {@code Double} (or {@code Long} when integral), plus String, Boolean and null.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text == null ? "" : text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("JSON: contenido inesperado en la posición " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON: se esperaba un objeto");
        return (Map<String, Object>) v;
    }

    private Object value(int depth) {
        if (depth > 64) throw new IllegalArgumentException("JSON demasiado anidado");
        if (i >= s.length()) throw new IllegalArgumentException("JSON incompleto");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw new IllegalArgumentException("JSON: se esperaba una clave");
            String k = string(); ws();
            if (!peek(':')) throw new IllegalArgumentException("JSON: se esperaba ':'");
            i++; ws();
            m.put(k, value(depth + 1)); ws();
            if (peek(',')) { i++; continue; }
            if (peek('}')) { i++; return m; }
            throw new IllegalArgumentException("JSON: se esperaba ',' o '}'");
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws(); l.add(value(depth + 1)); ws();
            if (peek(',')) { i++; continue; }
            if (peek(']')) { i++; return l; }
            throw new IllegalArgumentException("JSON: se esperaba ',' o ']'");
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("JSON: escape \\u incompleto");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: throw new IllegalArgumentException("JSON: escape no válido");
            }
        }
        throw new IllegalArgumentException("JSON: cadena sin cerrar");
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw new IllegalArgumentException("JSON: valor no válido en la posición " + start);
        if (n.matches("-?\\d{1,18}")) return Long.parseLong(n);
        return Double.parseDouble(n);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw new IllegalArgumentException("JSON: se esperaba " + word);
        i += word.length();
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    /* ───────── Serialización ───────── */

    public static String stringify(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { quote(b, (String) v); return; }
        if (v instanceof Boolean) { b.append(v); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
            return;
        }
        if (v instanceof Number) { b.append(v); return; }
        if (v instanceof Map) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                quote(b, String.valueOf(e.getKey())); b.append(':'); write(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
            return;
        }
        if (v instanceof Iterable) {
            b.append('[');
            Iterator<?> it = ((Iterable<?>) v).iterator();
            while (it.hasNext()) { write(b, it.next()); if (it.hasNext()) b.append(','); }
            b.append(']');
            return;
        }
        quote(b, String.valueOf(v));
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '<': b.append("\\u003c"); break; // nunca interpretable como HTML
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    /* ───────── Ayudas de lectura ───────── */

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static double num(Object v, double fallback) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? fallback : Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return fallback; }
    }

    /** Pequeño constructor de mapas ordenados: {@code Json.map("a", 1, "b", 2)}. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put(String.valueOf(kv[k]), kv[k + 1]);
        return m;
    }
}
