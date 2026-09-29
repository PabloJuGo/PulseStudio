package com.pulsestudio.desktop.server;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.Assert.*;

/** El servidor local solo responde a la ventana propia: token, Host y Origin exactos. */
public class ServerSecurityTest {
    private static LocalServer server;
    private static final String TOKEN = "test-token-0123456789abcdef";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeClass public static void start() throws Exception {
        Router r = new Router();
        r.get("/api/echo", req -> req.json(200, Map.of("ok", true)));
        r.post("/api/boom", req -> { throw ApiException.badRequest("mensaje claro"); });
        server = new LocalServer(0, TOKEN, r, new StaticFiles(null));
        server.start();
    }

    @AfterClass public static void stop() { server.stop(); }

    private static HttpResponse<String> call(String method, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url() + path.substring(1))).method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test public void apiRequiresToken() throws Exception {
        assertEquals(401, call("GET", "/api/echo", Map.of()).statusCode());
        assertEquals(401, call("GET", "/api/echo", Map.of("X-Pulse-Token", "otro")).statusCode());
        HttpResponse<String> ok = call("GET", "/api/echo", Map.of("X-Pulse-Token", TOKEN));
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Security-Policy").orElse("").contains("connect-src 'self'"));
    }

    @Test public void rejectsForeignOriginAndCrossSite() throws Exception {
        assertEquals(403, call("GET", "/api/echo", Map.of("X-Pulse-Token", TOKEN, "Origin", "https://evil.example")).statusCode());
        assertEquals(403, call("GET", "/api/echo", Map.of("X-Pulse-Token", TOKEN, "Sec-Fetch-Site", "cross-site")).statusCode());
        assertEquals(200, call("GET", "/api/echo", Map.of("X-Pulse-Token", TOKEN, "Origin", server.url().replaceAll("/$", ""))).statusCode());
    }

    @Test public void errorsAreJsonWithStatusAndUnknownRoutes() throws Exception {
        HttpResponse<String> r = call("POST", "/api/boom", Map.of("X-Pulse-Token", TOKEN));
        assertEquals(400, r.statusCode());
        assertEquals("mensaje claro", Json.str(Json.parseObject(r.body()), "error"));
        assertEquals(404, call("GET", "/api/nada", Map.of("X-Pulse-Token", TOKEN)).statusCode());
        assertEquals(405, call("DELETE", "/api/echo", Map.of("X-Pulse-Token", TOKEN)).statusCode());
    }

    @Test public void staticFilesRejectTraversalAndUnknownTypes() throws Exception {
        assertEquals(404, call("GET", "/../secret.txt", Map.of()).statusCode());
        assertEquals(404, call("GET", "/vendor/x.exe", Map.of()).statusCode());
    }

    @Test public void jsonRoundTripAndEscaping() {
        Map<String, Object> m = Json.parseObject("{\"a\":[1,2.5,true,null,\"x\\u00e9\\n\"],\"b\":{\"c\":\"</script>\"}}");
        String out = Json.stringify(m);
        assertTrue(out.contains("\\u003c/script>"));
        assertEquals(m, Json.parseObject(out));
        try { Json.parse("{\"a\":1} x"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
