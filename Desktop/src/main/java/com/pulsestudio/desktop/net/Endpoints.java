package com.pulsestudio.desktop.net;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * URLs base de los servicios externos. Solo son sobrescribibles con propiedades de sistema al arrancar la JVM
 * (-Dpulse.endpoint.groq=…), algo pensado para pruebas automáticas; la interfaz no puede cambiarlas.
 */
public final class Endpoints {
    public final String groq = prop("groq", "https://api.groq.com");
    public final String pexels = prop("pexels", "https://api.pexels.com");
    public final String pexelsImages = prop("pexelsImages", "https://images.pexels.com");
    public final String jina = prop("jina", "https://r.jina.ai");
    public final String hn = prop("hn", "https://hn.algolia.com");
    public final String rss = prop("rss", "https://pulse-studio-rss.elteclas1312.workers.dev");
    public final String hfRouter = prop("hfRouter", "https://router.huggingface.co");
    public final String hfHub = prop("hfHub", "https://huggingface.co");

    private static String prop(String name, String def) {
        String v = System.getProperty("pulse.endpoint." + name);
        return (v == null || v.isBlank() ? def : v).replaceAll("/+$", "");
    }

    /** Hosts a los que el backend puede conectarse (lista cerrada). */
    public Set<String> allowedHosts() {
        Set<String> s = new LinkedHashSet<>();
        for (String u : new String[]{groq, pexels, pexelsImages, jina, hn, rss, hfRouter, hfHub}) s.add(URI.create(u).getAuthority());
        return s;
    }
}
