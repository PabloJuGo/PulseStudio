package com.pulsestudio.app;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Pure URL decisions; deliberately no substring/suffix matching for the trust boundary. */
public final class SecurityPolicy {
    public static final String ORIGIN="https://appassets.androidplatform.net";
    public static final String PAGE=ORIGIN+"/assets/www/index.html";
    public static final String DEFAULT_RSS="https://pulse-studio-rss.elteclas1312.workers.dev/";
    private static final URI RSS_URI=URI.create(DEFAULT_RSS);
    private static final Set<String> PROVIDERS=new HashSet<>(Arrays.asList(
        "api.groq.com","api.pexels.com","images.pexels.com","r.jina.ai","hn.algolia.com",
        // Hugging Face Inference Providers router: text-to-image for the thumbnail background.
        "router.huggingface.co"));
    /** huggingface.co is only reachable for the provider-mapping lookup and the token test. */
    private static final String HF_HUB_HOST="huggingface.co";
    private static final String VENDOR="/assets/www/vendor/mediapipe/";
    /** Exact local files of the on-device segmentation engine (MediaPipe Tasks Vision). */
    private static final Map<String,String> INTERNAL_ASSETS;
    static {
        Map<String,String> assets=new HashMap<>();
        assets.put(VENDOR+"vision_bundle.js","text/javascript");
        assets.put(VENDOR+"wasm/vision_wasm_internal.js","text/javascript");
        assets.put(VENDOR+"wasm/vision_wasm_internal.wasm","application/wasm");
        assets.put(VENDOR+"wasm/vision_wasm_nosimd_internal.js","text/javascript");
        assets.put(VENDOR+"wasm/vision_wasm_nosimd_internal.wasm","application/wasm");
        assets.put(VENDOR+"selfie_segmenter.tflite","application/octet-stream");
        // Mediabunny: lectura/escritura de MP4 con WebCodecs para la exportación fotograma a fotograma.
        assets.put("/assets/www/vendor/mediabunny/mediabunny.js","text/javascript");
        INTERNAL_ASSETS=Collections.unmodifiableMap(assets);
    }
    private SecurityPolicy() {}
    private static URI https(String raw) {
        try {
            URI u=new URI(raw);
            return "https".equals(u.getScheme()) && u.getHost()!=null && u.getRawUserInfo()==null
                && (u.getPort()==-1 || u.getPort()==443) ? u : null;
        } catch(Exception ignored) { return null; }
    }
    public static boolean trustedOrigin(String raw) {
        URI u=https(raw);
        return u!=null && "appassets.androidplatform.net".equals(u.getHost())
            && (u.getRawPath()==null || u.getRawPath().isEmpty() || "/".equals(u.getRawPath()))
            && u.getRawQuery()==null && u.getRawFragment()==null;
    }
    public static boolean internalDocument(String raw) {
        URI u=https(raw);
        return u!=null && "appassets.androidplatform.net".equals(u.getHost())
            && "/assets/www/index.html".equals(u.getRawPath()) && u.getRawQuery()==null;
    }
    /** Local sub-resources the page may load: an exact allowlist, never a directory prefix. */
    public static boolean internalAsset(String raw) {
        return internalAssetMime(raw)!=null;
    }
    /** MIME type to serve for an allowed internal asset, or null when the URL is not allowed. */
    public static String internalAssetMime(String raw) {
        URI u=https(raw);
        if(u==null || !"appassets.androidplatform.net".equals(u.getHost()) || u.getRawQuery()!=null || u.getRawFragment()!=null) return null;
        return INTERNAL_ASSETS.get(u.getRawPath());
    }
    public static boolean networkAllowed(String raw) {
        URI u=https(raw);
        if(u==null || "appassets.androidplatform.net".equals(u.getHost())) return false;
        if(PROVIDERS.contains(u.getHost())) return true;
        if(HF_HUB_HOST.equals(u.getHost())) {
            String path=u.getRawPath();
            return path!=null && (path.startsWith("/api/models/") || "/api/whoami-v2".equals(path));
        }
        return RSS_URI.getHost().equals(u.getHost()) && "/feed".equals(u.getRawPath());
    }
    public static boolean externalAllowed(String raw) {
        try {
            URI u=new URI(raw);
            return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
                && u.getHost()!=null && u.getRawUserInfo()==null
                && !"appassets.androidplatform.net".equals(u.getHost());
        } catch(Exception ignored) { return false; }
    }
}
