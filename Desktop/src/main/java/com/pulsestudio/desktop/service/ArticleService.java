package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.net.Endpoints;
import com.pulsestudio.desktop.net.WebClient;
import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recupera y depura el texto de la noticia antes de enviarlo a Groq (Jina Reader + heurísticas de limpieza).
 * Port fiel de articleFromReader/trimArticle de la app: localiza el titular real, descarta menús, publicidad
 * y artículos relacionados, y limita el texto por párrafos.
 */
public final class ArticleService {
    public static final int ARTICLE_CHAR_LIMIT = 8200;
    private static final int UI = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern MARKER = Pattern.compile("^Markdown Content:\\s*$", Pattern.MULTILINE | UI);
    private static final Pattern STOP = Pattern.compile("^(?:#{1,6}\\s*)?(?:related(?: articles| posts| stories)?|recommended(?: for you)?|you (?:may|might) also like|also read|read (?:more|next)|more (?:stories|from .+)|latest (?:stories|news)|share this(?: article)?|comments?|leave a (?:reply|comment)|about the author|newsletter|subscribe|advertisement|publicidad|contenido relacionado|noticias relacionadas|art[ií]culos relacionados|te puede interesar|tambi[eé]n te puede interesar|sigue leyendo|lee tambi[eé]n|lo m[aá]s le[ií]do|sobre el autor|deja (?:un )?comentario|etiquetas|tags)\\s*[:：]?\\s*$", UI);
    private static final Pattern NOISE = Pattern.compile("^(?:title:|url source:|content type:|source:|markdown content:|\\[?skip to (?:main )?content\\]?|ir al contenido|saltar al contenido|iniciar sesi[oó]n|sign in|log in|registrarse|accept (?:all )?cookies|aceptar (?:todas las )?cookies|cookie preferences|configurar cookies|advertisement|publicidad|patrocinado|sponsored|share (?:on|this)|compartir (?:en|este art[ií]culo)|follow us|s[ií]guenos|all rights reserved|todos los derechos reservados|reading time:?\\s*\\d+|tiempo de lectura:?)", UI);
    private static final Pattern IMG_LINE = Pattern.compile("^!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern IMAGE_TAG = Pattern.compile("^\\[Image(?:\\s*\\d+)?(?::[^\\]]*)?\\]", UI);
    private static final Pattern FOOTNOTE = Pattern.compile("^\\[\\^?\\d+\\]:");
    private static final Pattern RULE = Pattern.compile("^(?:[-*_]\\s*){3,}$");
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]+\\]\\(https?://[^)]+\\)");
    private static final Pattern LIST_LINK = Pattern.compile("^[-*]\\s*\\[[^\\]]+\\]\\(https?://[^)]+\\)\\s*$");
    private static final Pattern FIRST_LINE_OK = Pattern.compile("^[A-ZÁÉÍÓÚÑ][^.!?]{20,}[:.!?]$");

    private final WebClient web;
    private final Endpoints ep;

    public ArticleService(WebClient web, Endpoints ep) { this.web = web; this.ep = ep; }

    public record Evidence(String text, String origin, boolean automatic, String readerError) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = Json.map("text", text, "origin", origin, "automatic", automatic, "characters", text.length());
            if (readerError != null) m.put("readerError", readerError);
            return m;
        }
    }

    /** Prioridad: texto pegado por el usuario → Jina Reader → cuerpo del RSS/HN → extracto. */
    public Evidence extract(String url, String title, String manualText, String lastExtracted, String storyText, String summary) {
        String manual = manualText == null ? "" : manualText.trim();
        String original = Text.safeHttp(url);
        if (original == null) throw ApiException.badRequest("La URL original no es válida.");
        if (manual.length() >= 70 && !manual.equals(lastExtracted == null ? "" : lastExtracted.trim()))
            return evidence(manual, "Texto del artículo introducido y revisado manualmente", false, null);
        try {
            WebClient.Response r = web.get(ep.jina + "/" + original, Map.of("Accept", "text/plain"), 65, 12_000_000);
            if (!r.ok()) throw ApiException.upstream("HTTP " + r.status() + ": " + Text.cap(r.text(), 260));
            return evidence(fromReader(r.text(), title), "Artículo extraído y depurado de la URL original mediante Jina Reader", true, null);
        } catch (RuntimeException err) {
            String backup = storyText == null ? "" : storyText.trim(), sum = summary == null ? "" : summary.trim();
            if (backup.length() >= 180) return evidence(backup, "Contenido de la fuente RSS/Hacker News (lectura de la web no disponible)", false, err.getMessage());
            if (sum.length() >= 180) return evidence(sum, "Extracto RSS de la noticia (artículo completo no disponible)", false, err.getMessage());
            throw ApiException.upstream("No se ha podido separar y recuperar el texto de la noticia (" + err.getMessage() + "). Pega el contenido del artículo en su campo y vuelve a generar.");
        }
    }

    static Evidence evidence(String text, String origin, boolean automatic, String readerError) {
        String article = trimArticle(text);
        if (article.length() < 70) throw ApiException.badRequest("La noticia no tiene suficiente contenido útil.");
        return new Evidence(article, origin, automatic, readerError);
    }

    static int articleLimit(String text) {
        long eastern = text.codePoints().filter(c -> (c >= 0x3040 && c <= 0x30ff) || (c >= 0x3400 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af)).count();
        return eastern > text.length() * .2 ? 2900 : ARTICLE_CHAR_LIMIT;
    }

    static String clean(String text) {
        return (text == null ? "" : text).replace("\r", "").replace("\u0000", "").replaceAll("\n{4,}", "\n\n").trim();
    }

    static String trimArticle(String text) {
        String s = text == null ? "" : text;
        int max = articleLimit(s);
        List<String> kept = new ArrayList<>();
        int count = 0;
        for (String para : clean(s).split("\\n\\s*\\n")) {
            String part = para.trim();
            if (part.isEmpty()) continue;
            int left = max - count - (kept.isEmpty() ? 0 : 2);
            if (left <= 0) break;
            if (part.length() <= left) { kept.add(part); count += part.length() + (kept.size() > 1 ? 2 : 0); continue; }
            if (left >= 150) {
                String cut = part.substring(0, left).trim();
                int sentence = Math.max(Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("! ")), Math.max(cut.lastIndexOf("? "), cut.lastIndexOf("。")));
                if (sentence > left * .53) cut = cut.substring(0, sentence + 1).trim();
                if (!cut.isEmpty()) kept.add(cut);
            }
            break;
        }
        String joined = String.join("\n\n", kept);
        return joined.length() > max ? joined.substring(0, max) : joined;
    }

    private static String stripHeading(String v) {
        String s = v.replaceAll("^[#\\s>*-]+", "").replaceAll("\\[[^\\]]*\\]\\([^)]*\\)", " ");
        return Text.normalizeWords(s).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    static boolean titleMatch(String line, String title) {
        String heading = stripHeading(line), expected = stripHeading(title == null ? "" : title);
        if (heading.isEmpty() || expected.isEmpty() || heading.length() > Math.max(220, expected.length() * 2.3)) return false;
        if (heading.contains(expected) || (expected.contains(heading) && heading.length() >= expected.length() * .72)) return true;
        List<String> words = Arrays.asList(heading.split(" "));
        String[] terms = Arrays.stream(expected.split(" ")).filter(t -> t.length() >= 4).toArray(String[]::new);
        if (terms.length < 3) return false;
        long hits = Arrays.stream(terms).filter(words::contains).count();
        return (double) hits / terms.length >= .75;
    }

    static String fromReader(String raw, String title) {
        String source = clean(raw);
        Matcher marker = MARKER.matcher(source);
        boolean hasMarker = marker.find();
        if (hasMarker) source = source.substring(marker.end()).trim();
        String[] lines = source.substring(0, Math.min(source.length(), 190000)).split("\n", -1);
        int start = -1;
        for (int i = 0; i < Math.min(lines.length, 520); i++) {
            String line = lines[i].trim();
            if (line.length() > 260 || !titleMatch(line, title)) continue;
            StringBuilder near = new StringBuilder();
            for (int k = i + 1; k < Math.min(lines.length, i + 16); k++) near.append(k > i + 1 ? " " : "").append(lines[k]);
            if (near.toString().replaceAll("\\[[^\\]]+\\]\\([^)]+\\)", " ").length() > 150) { start = i + 1; break; }
        }
        if (start < 0 && (source.length() > 14000 || (hasMarker && source.length() > 6000)))
            throw ApiException.upstream("No se ha podido localizar el titular dentro de la página extraída; se descarta el texto de navegación.");
        List<String> result = new ArrayList<>();
        int length = 0;
        for (int i = Math.max(0, start); i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) { if (!result.isEmpty() && !result.get(result.size() - 1).isEmpty()) result.add(""); continue; }
            if (STOP.matcher(line).find() && length >= 320) break;
            if (NOISE.matcher(line).find() || IMG_LINE.matcher(line).find() || IMAGE_TAG.matcher(line).find() || FOOTNOTE.matcher(line).find() || RULE.matcher(line).find()) continue;
            int links = 0;
            for (Matcher m = LINK.matcher(line); m.find(); ) links++;
            if ((links >= 2 && line.length() < 420) || LIST_LINK.matcher(line).find()) continue;
            String useful = line.replaceAll("^#{1,6}\\s+", "").replaceAll("^>\\s?", "").replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]+)\\]\\(https?://[^)]+\\)", "$1").replaceAll("\\[\\^?\\d+\\]", "")
                .replaceAll("(?iu)\\s+\\[(?:Image|Imagen):[^\\]]+\\]", "").replaceAll("\\s+", " ").trim();
            if (useful.isEmpty() || useful.matches("^https?://\\S+$") || NOISE.matcher(useful).find() || useful.matches("(?iu)^\\[?imagen(?::[^\\]]*)?\\]?$")) continue;
            if (length == 0 && useful.length() < 35 && !FIRST_LINE_OK.matcher(useful).find()) continue;
            result.add(useful);
            length += useful.length() + 1;
            if (length > ARTICLE_CHAR_LIMIT + 600) break;
        }
        String content = trimArticle(String.join("\n", result).replaceAll("\n{3,}", "\n\n"));
        if (content.length() < 240) throw ApiException.upstream("La página no ha proporcionado suficiente texto propio de la noticia.");
        return content;
    }
}
