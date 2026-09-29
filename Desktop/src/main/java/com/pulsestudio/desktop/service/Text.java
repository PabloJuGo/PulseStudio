package com.pulsestudio.desktop.service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilidades de texto compartidas por los servicios (portadas del JavaScript de la app móvil). */
public final class Text {
    private Text() {}
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Locale ES = Locale.forLanguageTag("es-ES");

    /** Texto plano de un fragmento HTML (equivale a DOMParser().textContent + normalización de espacios). */
    public static String htmlStrip(String html) {
        if (html == null) return "";
        String s = TAGS.matcher(html).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        Matcher m = NUM_ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (NumberFormatException e) { cp = ' '; }
            m.appendReplacement(b, Matcher.quoteReplacement(Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : " "));
        }
        m.appendTail(b);
        return b.toString().replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    /** Minúsculas, sin tildes y con espacios normalizados. */
    public static String normalizeWords(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static String cap(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** URL http(s) segura o null. */
    public static String safeHttp(String raw) {
        try {
            URI u = new URI(raw == null ? "" : raw.trim());
            if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme())) return null;
            if (u.getHost() == null || u.getRawUserInfo() != null) return null;
            return u.toString();
        } catch (Exception e) { return null; }
    }

    /** URL canónica para deduplicar (sin www, sin utm_*, parámetros ordenados, sin barra final). */
    public static String canonicalUrl(String raw) {
        String safe = safeHttp(raw);
        if (safe == null) return null;
        try {
            URI u = new URI(safe);
            String host = u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            TreeMap<String, List<String>> params = new TreeMap<>();
            if (u.getRawQuery() != null) for (String part : u.getRawQuery().split("&")) {
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                if (k.matches("(?i)^(utm_.+|fbclid|gclid|mc_cid|mc_eid|ref|source)$")) continue;
                params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
            StringBuilder q = new StringBuilder();
            params.forEach((k, vs) -> vs.forEach(v -> q.append(q.length() == 0 ? "?" : "&")
                .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8))));
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            if (path.isEmpty()) path = "/";
            String port = u.getPort() == -1 ? "" : ":" + u.getPort();
            return u.getScheme() + "://" + host + port + path + q;
        } catch (Exception e) { return null; }
    }

    public static String prettyDate(String iso) {
        if (iso == null || iso.isBlank()) return "No indicada";
        try {
            Instant t = iso.length() == 10 ? Instant.parse(iso + "T12:00:00Z") : Instant.parse(iso);
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(ES).format(t.atZone(ZoneId.systemDefault()));
        } catch (Exception e) { return "No indicada"; }
    }

    public static String pct(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
