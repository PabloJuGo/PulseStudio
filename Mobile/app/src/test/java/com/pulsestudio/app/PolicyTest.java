package com.pulsestudio.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PolicyTest {
    @Test public void bridgeOnlyAcceptsExactOriginAndDocument() {
        assertTrue(SecurityPolicy.trustedOrigin("https://appassets.androidplatform.net"));
        assertTrue(SecurityPolicy.internalDocument(SecurityPolicy.PAGE));
        for (String url : new String[]{"http://appassets.androidplatform.net", "https://appassets.androidplatform.net.evil.test", "https://evil@appassets.androidplatform.net", "https://appassets.androidplatform.net:8443", "file:///android_asset/index.html"}) {
            assertFalse(url, SecurityPolicy.trustedOrigin(url));
        }
        assertFalse(SecurityPolicy.internalDocument(SecurityPolicy.ORIGIN+"/assets/www/evil.html"));
        assertFalse(SecurityPolicy.internalDocument(SecurityPolicy.PAGE+"?redirect=evil"));
    }
    @Test public void networkAllowsProvidersAndFixedBridgeOnly() {
        assertTrue(SecurityPolicy.networkAllowed("https://api.groq.com/openai/v1/chat/completions"));
        assertTrue(SecurityPolicy.networkAllowed(SecurityPolicy.DEFAULT_RSS+"feed?source=xataka"));
        for (String url : new String[]{"https://api.groq.com.evil.test/", "http://api.groq.com/", "file:///etc/passwd", "content://secret", "https://api.groq.com:8443/", "https://evil.test/", "https://u:p@api.groq.com/", "https://another.workers.dev/feed?source=xataka", "https://pulse-studio-rss.elteclas1312.workers.dev/other"}) {
            assertFalse(url, SecurityPolicy.networkAllowed(url));
        }
    }
    @Test public void huggingFaceRouterAndHubLookupOnly() {
        assertTrue(SecurityPolicy.networkAllowed("https://router.huggingface.co/hf-inference/models/stabilityai/stable-diffusion-3-medium-diffusers"));
        assertTrue(SecurityPolicy.networkAllowed("https://router.huggingface.co/nscale/v1/images/generations"));
        assertTrue(SecurityPolicy.networkAllowed("https://huggingface.co/api/models/black-forest-labs/FLUX.1-schnell?expand%5B%5D=inferenceProviderMapping"));
        assertTrue(SecurityPolicy.networkAllowed("https://huggingface.co/api/whoami-v2"));
        for (String url : new String[]{"https://huggingface.co/", "https://huggingface.co/settings/tokens", "https://huggingface.co/api/whoami-v2/extra", "https://huggingface.co.evil.test/api/models/x", "http://router.huggingface.co/x", "https://router.huggingface.co:8443/x", "https://evil.huggingface.co/api/models/x", "https://api.replicate.com/v1/predictions"}) {
            assertFalse(url, SecurityPolicy.networkAllowed(url));
        }
    }
    @Test public void onlyExactMediaPipeAssetsAreServed() {
        String base=SecurityPolicy.ORIGIN+"/assets/www/vendor/mediapipe/";
        assertEquals("text/javascript", SecurityPolicy.internalAssetMime(base+"vision_bundle.js"));
        assertEquals("application/wasm", SecurityPolicy.internalAssetMime(base+"wasm/vision_wasm_internal.wasm"));
        assertEquals("application/wasm", SecurityPolicy.internalAssetMime(base+"wasm/vision_wasm_nosimd_internal.wasm"));
        assertTrue(SecurityPolicy.internalAsset(base+"selfie_segmenter.tflite"));
        for (String url : new String[]{base+"VERSION.json", base+"vision_bundle.js?x=1", base+"vision_bundle.js#a", base+"../index.html", base+"wasm/../vision_bundle.js", base, SecurityPolicy.PAGE, "http://appassets.androidplatform.net/assets/www/vendor/mediapipe/vision_bundle.js", "https://evil.test/assets/www/vendor/mediapipe/vision_bundle.js", SecurityPolicy.ORIGIN+"/assets/www/VENDOR/mediapipe/vision_bundle.js"}) {
            assertFalse(url, SecurityPolicy.internalAsset(url));
        }
        assertFalse(SecurityPolicy.networkAllowed(base+"vision_bundle.js"));
    }
    @Test public void externalIntentRejectsActiveSchemesAndUserInfo() {
        assertTrue(SecurityPolicy.externalAllowed("https://www.pexels.com/photo/123/"));
        for (String url : new String[]{"intent://evil", "javascript:alert(1)", "data:text/html,test", "file:///secret", "https://user:pass@example.com/"}) assertFalse(SecurityPolicy.externalAllowed(url));
    }
    @Test public void fixedRssRejectsAlternateHostsAndSchemes() {
        for (String url : new String[]{"http://pulse-studio-rss.elteclas1312.workers.dev/feed", "https://127.0.0.1/feed", "https://localhost/feed", "https://192.168.1.1/feed", "https://[::1]/feed", "https://example.workers.dev/feed", "https://pulse-studio-rss.elteclas1312.workers.dev:8443/feed", "https://appassets.androidplatform.net/feed"}) {
            assertFalse(url, SecurityPolicy.networkAllowed(url));
        }
    }
}
