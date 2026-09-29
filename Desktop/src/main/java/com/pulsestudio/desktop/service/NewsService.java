package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Búsqueda multifuente de noticias: Hacker News (Algolia) + RSS fijos (HackRead, KrebsOnSecurity, Xataka). */
public final class NewsService {
    public static final Map<String, String> RSS_SOURCES = Map.of("hackread", "HackRead", "krebs", "KrebsOnSecurity", "xataka", "Xataka");
    private static final Map<String, List<String>> RSS_DOMAINS = Map.of("hackread", List.of("hackread.com"), "krebs", List.of("krebsonsecurity.com"), "xataka", List.of("xataka.com"));
    public static final Map<String, List<String>> CATEGORY_TERMS = Map.of(
        "all", List.of("technology", "cybersecurity", "artificial intelligence", "hardware"),
        "tech", List.of("technology", "software"), "cyber", List.of("cybersecurity", "ransomware"),
        "ai", List.of("artificial intelligence", "machine learning"), "hardware", List.of("hardware", "semiconductor"));
    public static final Map<String, String> CATEGORY_LABELS = Map.of("all", "Todas las temáticas", "tech", "Tech general",
        "cyber", "Ciberseguridad", "ai", "Inteligencia artificial", "hardware", "Hardware");
    private static final Map<String, Pattern> TOPIC = Map.of(
        "cyber", Pattern.compile("\\b(cybersecurity|ciberseguridad|cyber|ciber|security|seguridad|ransomware|malware|phishing|breach|filtracion|filtraciones|hack|hacker|vulnerab|exploit|zero.day|zero day|botnet|password|contrasen|ataque|datos robados|data leak|fraud|fraude|credential|credencial|ddos|patch tuesday|actualizacion de seguridad)\\b", Pattern.CASE_INSENSITIVE),
        "ai", Pattern.compile("\\b(artificial intelligence|inteligencia artificial|machine learning|deep learning|generative ai|generative artificial|ai model|modelos? de ia|\\bia\\b|\\bai\\b|\\bllm\\b|openai|chatgpt|gemini|claude|copilot|mistral|deepseek|neural|neuronales|inferencia|transformer|gpt|stable diffusion)\\b", Pattern.CASE_INSENSITIVE),
        "hardware", Pattern.compile("\\b(hardware|gpu|cpu|procesador|semiconductor|chip|chipset|nvidia|amd|intel|qualcomm|arm|memoria ram|ssd|motherboard|placa base|smartphone|movil|moviles|iphone|pixel|dispositivo|laptop|portatil|bateria|refrigeracion|tarjeta grafica|graphics card|computer hardware|server hardware|centro de datos|data center)\\b", Pattern.CASE_INSENSITIVE),
        "tech", Pattern.compile("\\b(technology|technologies|tecnologia|tecnologias|technolog|tecnolog|software|apps?|aplicacion|programacion|programming|internet|browser|navegador|plataforma|digital|cloud|nube|smartphone|hardware|cyber|ciber|security|seguridad|inteligencia artificial|artificial intelligence|\\bia\\b|\\bai\\b|\\bgpu\\b|open source|codigo abierto|windows|linux|android|apple|microsoft|google)\\b", Pattern.CASE_INSENSITIVE));
    private static final long DAY = 86_400_000L;

    private final WebClient web;
    private final Endpoints ep;

    public NewsService(WebClient web, Endpoints ep) { this.web = web; this.ep = ep; }

    public record News(String id, String sourceKey, String source, String title, String url, String date, String summary,
                       String storyText, String tags, long publishedAt) {
        Map<String, Object> toMap() {
            return Json.map("id", id, "sourceKey", sourceKey, "source", source, "title", title, "url", url, "date", date,
                "summary", summary, "storyText", storyText, "publishedAt", publishedAt);
        }
    }

    /** Resultado con noticias, recuento por fuente y fuentes que han fallado (para el mensaje de estado). */
    public Map<String, Object> search(String query, String category, String source, String sort) {
        String cat = CATEGORY_TERMS.containsKey(category) ? category : "all";
        List<String> keys = "all".equals(source) || source.isBlank() ? List.of("hn", "hackread", "krebs", "xataka") : List.of(source);
        for (String k : keys) if (!k.equals("hn") && !RSS_SOURCES.containsKey(k)) throw ApiException.badRequest("Fuente no admitida.");
        String q = Text.cap(query, 100);
        Map<String, CompletableFuture<Object>> tasks = new LinkedHashMap<>();
        for (String k : keys) tasks.put(k, CompletableFuture.supplyAsync(() -> k.equals("hn") ? hn(q, cat) : rss(k)));
        List<News> all = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean partial = false;
        for (var e : tasks.entrySet()) {
            try {
                Object r = e.getValue().join();
                if (r instanceof HnResult hr) { all.addAll(hr.items); partial |= hr.partial; }
                else if (r instanceof List<?> l) for (Object o : l) all.add((News) o);
            } catch (Exception ex) {
                Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                failures.add(label(e.getKey()) + ": " + c.getMessage());
            }
        }
        List<News> filtered = new ArrayList<>();
        for (News n : all) if (matches(n, q, cat)) filtered.add(n);
        List<News> items = dedupeAndSort(filtered, "oldest".equals(sort));
        Map<String, Object> bySource = new LinkedHashMap<>();
        for (String k : keys) {
            boolean any = all.stream().anyMatch(n -> n.sourceKey.equals(k));
            if (any) bySource.put(label(k), items.stream().filter(n -> n.sourceKey.equals(k)).count());
        }
        List<Object> list = new ArrayList<>();
        for (News n : items) list.add(n.toMap());
        return Json.map("items", list, "bySource", bySource, "failures", failures, "partial", partial,
            "categoryLabel", CATEGORY_LABELS.get(cat));
    }

    private static String label(String key) { return key.equals("hn") ? "Hacker News" : RSS_SOURCES.getOrDefault(key, key); }

    private record HnResult(List<News> items, boolean partial) {}

    private HnResult hn(String query, String cat) {
        List<String> terms = query.isBlank() ? CATEGORY_TERMS.get(cat) : List.of(query);
        long min = Instant.now().getEpochSecond() - 90 * 86400L;
        List<CompletableFuture<Map<String, Object>>> calls = new ArrayList<>();
        for (String term : terms) calls.add(CompletableFuture.supplyAsync(() -> {
            String url = ep.hn + "/api/v1/search_by_date?query=" + Text.pct(term) + "&tags=story&hitsPerPage=25&numericFilters=" + Text.pct("created_at_i>" + min);
            WebClient.Response r = web.get(url, Map.of("Accept", "application/json"), 26, 4_000_000);
            if (!r.ok()) throw ApiException.upstream("HTTP " + r.status());
            return r.json();
        }));
        List<Object> hits = new ArrayList<>();
        int failed = 0;
        RuntimeException first = null;
        for (var c : calls) {
            try { hits.addAll(Json.list(c.join(), "hits")); }
            catch (RuntimeException e) { failed++; if (first == null) first = e.getCause() instanceof RuntimeException re ? re : e; }
        }
        if (hits.isEmpty() && failed == calls.size() && first != null) throw first;
        Set<String> seen = new LinkedHashSet<>();
        List<News> items = new ArrayList<>();
        for (Object o : hits) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> h = (Map<String, Object>) raw;
            String title = Json.str(h, "title"), id = Json.str(h, "objectID");
            if (title.isBlank() || id.isBlank() || !seen.add(id)) continue;
            String link = Text.safeHttp(Json.str(h, "url"));
            if (link == null) link = "https://news.ycombinator.com/item?id=" + Text.pct(id);
            String story = Text.htmlStrip(Json.str(h, "story_text"));
            long stamp = parseTime(Json.str(h, "created_at"));
            String host = java.net.URI.create(link).getHost().replaceFirst("^www\\.", "");
            items.add(new News("hn:" + id, "hn", host + " · vía Hacker News", title, link, stamp > 0 ? Instant.ofEpochMilli(stamp).toString() : "",
                story.isEmpty() ? "Publicación en Hacker News: titular y enlace al medio original." : story.substring(0, Math.min(420, story.length())) + (story.length() > 420 ? "…" : ""),
                story, "", Math.max(0, stamp)));
            if (items.size() >= 90) break;
        }
        return new HnResult(items, failed > 0);
    }

    List<News> rss(String key) {
        WebClient.Response r = web.get(ep.rss + "/feed?source=" + key, Map.of("Accept", "application/rss+xml, application/xml, text/xml"), 26, 8_000_000);
        if (!r.ok()) throw ApiException.upstream("HTTP " + r.status());
        return parseRss(r.body(), key, System.currentTimeMillis());
    }

    /** Analiza RSS/Atom con el parser XML endurecido (sin DOCTYPE ni entidades externas). */
    static List<News> parseRss(byte[] xml, String key, long now) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw ApiException.upstream("RSS XML mal formado.");
        }
        String root = doc.getDocumentElement().getLocalName();
        if (root == null || !List.of("rss", "rdf", "feed").contains(root.toLowerCase())) throw ApiException.upstream("La respuesta no es un feed RSS/Atom.");
        NodeList nodes = doc.getElementsByTagNameNS("*", "item");
        if (nodes.getLength() == 0) throw ApiException.upstream("El feed no ha devuelto noticias RSS.");
        long min = now - 90 * DAY, max = now + DAY;
        List<News> out = new ArrayList<>();
        for (int i = 0; i < Math.min(nodes.getLength(), 65); i++) {
            Element item = (Element) nodes.item(i);
            String url = link(item, key), title = Text.htmlStrip(child(item, "title"));
            String dateRaw = !child(item, "pubDate").isEmpty() ? child(item, "pubDate") : !child(item, "date").isEmpty() ? child(item, "date") : child(item, "published");
            long ts = parseTime(dateRaw);
            if (url == null || title.isEmpty() || ts <= 0 || ts < min || ts > max) continue;
            String body = Text.htmlStrip(child(item, "encoded"));
            String desc = Text.htmlStrip(!child(item, "description").isEmpty() ? child(item, "description") : body);
            String summary = desc.substring(0, Math.min(425, desc.length()));
            StringBuilder tags = new StringBuilder();
            for (Node c = item.getFirstChild(); c != null; c = c.getNextSibling())
                if (c instanceof Element e && "category".equalsIgnoreCase(e.getLocalName())) tags.append(e.getTextContent().trim()).append(' ');
            String canon = Text.canonicalUrl(url);
            out.add(new News(key + ":" + (canon != null ? canon : String.valueOf(i)), key, RSS_SOURCES.get(key), title, url,
                Instant.ofEpochMilli(ts).toString(), summary.isEmpty() ? "RSS: titular y enlace al artículo original; contenido ampliado al generar." : summary,
                body.length() >= 180 ? body.substring(0, Math.min(16000, body.length())) : "", tags.toString().trim(), ts));
        }
        return out;
    }

    private static String child(Element item, String local) {
        for (Node c = item.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e && local.equalsIgnoreCase(e.getLocalName())) return e.getTextContent().trim();
        return "";
    }

    private static String link(Element item, String key) {
        for (Node c = item.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element e) || !"link".equals(e.getLocalName())) continue;
            String v = e.getTextContent().trim();
            if (v.isEmpty()) v = e.getAttribute("href");
            String safe = Text.safeHttp(v);
            if (safe == null) return null;
            String host = java.net.URI.create(safe).getHost().toLowerCase().replaceFirst("^www\\.", "");
            for (String d : RSS_DOMAINS.getOrDefault(key, List.of())) if (host.equals(d) || host.endsWith("." + d)) return safe;
            return null;
        }
        return null;
    }

    static long parseTime(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        String s = raw.trim();
        try { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        try { return Instant.parse(s).toEpochMilli(); } catch (Exception ignored) { }
        try { return ZonedDateTime.parse(s.replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH)).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        return 0;
    }

    static boolean matches(News n, String query, String cat) {
        String text = Text.normalizeWords(n.title + " " + n.summary + " " + n.tags);
        for (String w : Text.normalizeWords(query).split(" ")) if (!w.isEmpty() && !text.contains(w)) return false;
        if ("all".equals(cat)) return true;
        if ("hn".equals(n.sourceKey) && query.isBlank()) return true;
        return TOPIC.get(cat).matcher(text).find();
    }

    static List<News> dedupeAndSort(List<News> items, boolean oldest) {
        Map<String, News> seen = new LinkedHashMap<>();
        for (News n : items) {
            String c = Text.canonicalUrl(n.url);
            String key = c != null ? c : Text.normalizeWords(n.title) + "|" + n.sourceKey;
            News old = seen.get(key);
            if (old == null || ("hn".equals(old.sourceKey) && !"hn".equals(n.sourceKey)) || (old.sourceKey.equals(n.sourceKey) && n.publishedAt > old.publishedAt)) seen.put(key, n);
        }
        List<News> out = new ArrayList<>(seen.values());
        Comparator<News> byDate = Comparator.comparingLong(News::publishedAt);
        out.sort(oldest ? byDate : byDate.reversed());
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }
}
