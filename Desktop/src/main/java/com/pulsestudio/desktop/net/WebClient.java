package com.pulsestudio.desktop.net;

import com.pulsestudio.desktop.server.ApiException;
import com.pulsestudio.desktop.server.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Cliente HTTP saliente: solo hosts permitidos, sin redirecciones, con límites de tamaño y tiempo. */
public final class WebClient {
    public record Response(int status, String contentType, byte[] body) {
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public boolean ok() { return status >= 200 && status < 300; }
        public Map<String, Object> json() {
            try { return Json.parseObject(text()); }
            catch (IllegalArgumentException e) { throw ApiException.upstream("El servicio no ha devuelto JSON válido."); }
        }
        /** Mensaje de error del proveedor (error.message, message, error o texto). */
        public String errorMessage() {
            try {
                Map<String, Object> m = Json.parseObject(text());
                Object err = m.get("error");
                if (err instanceof Map<?, ?> em && em.get("message") != null) return String.valueOf(em.get("message"));
                if (m.get("message") != null) return String.valueOf(m.get("message"));
                if (err != null) return String.valueOf(err);
            } catch (Exception ignored) { }
            String t = text();
            return t.length() > 260 ? t.substring(0, 260) : t;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER)
        .proxy(java.net.ProxySelector.getDefault()).build(); // respeta el proxy de Windows (java.net.useSystemProxies)
    private final Set<String> allowedHosts;

    public WebClient(Endpoints endpoints) { this.allowedHosts = endpoints.allowedHosts(); }

    public Response get(String url, Map<String, String> headers, int timeoutSeconds, long maxBytes) {
        return send(builder(url, headers, timeoutSeconds).GET().build(), maxBytes);
    }

    public Response postJson(String url, Map<String, String> headers, Object body, int timeoutSeconds, long maxBytes) {
        HttpRequest req = builder(url, headers, timeoutSeconds).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8)).build();
        return send(req, maxBytes);
    }

    public Response post(String url, Map<String, String> headers, String contentType, byte[] body, int timeoutSeconds, long maxBytes) {
        HttpRequest req = builder(url, headers, timeoutSeconds).header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return send(req, maxBytes);
    }

    private HttpRequest.Builder builder(String url, Map<String, String> headers, int timeoutSeconds) {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw ApiException.badRequest("URL no válida."); }
        if (!allowedHosts.contains(uri.getAuthority()) || uri.getRawUserInfo() != null) throw new ApiException(403, "Destino de red no permitido: " + uri.getHost());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds)).header("User-Agent", "PulseStudioDesktop/1.0");
        if (headers != null) headers.forEach(b::header);
        return b;
    }

    private Response send(HttpRequest req, long maxBytes) {
        try {
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw ApiException.upstream("La respuesta del servicio es demasiado grande.");
                    out.write(buf, 0, n);
                }
                return new Response(res.statusCode(), res.headers().firstValue("Content-Type").orElse("").toLowerCase(), out.toByteArray());
            }
        } catch (HttpConnectTimeoutException e) {
            throw ApiException.upstream("No se ha podido conectar con " + req.uri().getHost() + " (tiempo de conexión agotado).");
        } catch (HttpTimeoutException e) {
            throw ApiException.upstream(req.uri().getHost() + " ha tardado demasiado en responder.");
        } catch (IOException e) {
            throw ApiException.upstream("No se ha podido conectar con " + req.uri().getHost() + ". Comprueba la conexión a Internet.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("Operación interrumpida.");
        }
    }
}
