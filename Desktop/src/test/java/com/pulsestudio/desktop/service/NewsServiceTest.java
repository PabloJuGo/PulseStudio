package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.server.ApiException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.Assert.*;

public class NewsServiceTest {
    private static final long NOW = System.currentTimeMillis();
    private static String rfc(long ms) { return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneOffset.UTC)); }

    @Test public void parsesRssKeepsAllowedDomainsAndRecentItems() {
        String xml = "<?xml version=\"1.0\"?><rss xmlns:content=\"http://purl.org/rss/1.0/modules/content/\"><channel>"
            + "<item><title>Nuevo &lt;b&gt;chip&lt;/b&gt;</title><link>https://www.xataka.com/a?utm_source=x</link><pubDate>" + rfc(NOW - 3600_000) + "</pubDate>"
            + "<description>&lt;p&gt;Resumen de la noticia&lt;/p&gt;</description><content:encoded><![CDATA[<p>" + "Cuerpo largo. ".repeat(30) + "</p>]]></content:encoded><category>Hardware</category></item>"
            + "<item><title>Dominio ajeno</title><link>https://evil.example/a</link><pubDate>" + rfc(NOW) + "</pubDate></item>"
            + "<item><title>Muy antigua</title><link>https://xataka.com/b</link><pubDate>" + rfc(NOW - 200L * 86400_000) + "</pubDate></item>"
            + "</channel></rss>";
        List<NewsService.News> items = NewsService.parseRss(xml.getBytes(StandardCharsets.UTF_8), "xataka", NOW);
        assertEquals(1, items.size());
        NewsService.News n = items.get(0);
        assertEquals("Nuevo chip", n.title());
        assertEquals("Resumen de la noticia", n.summary());
        assertTrue(n.storyText().startsWith("Cuerpo largo."));
        assertEquals("xataka:https://xataka.com/a", n.id());
    }

    @Test public void rejectsDoctypeAndEntitiesXxe() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><channel><item><title>&x;</title></item></channel></rss>";
        try { NewsService.parseRss(xxe.getBytes(StandardCharsets.UTF_8), "xataka", NOW); fail(); }
        catch (ApiException e) { assertTrue(e.getMessage().contains("mal formado")); }
    }

    @Test public void filtersAndDedupePreferOriginalSource() {
        NewsService.News hn = new NewsService.News("hn:1", "hn", "xataka.com · vía Hacker News", "Ransomware ataca", "https://xataka.com/a/", "", "", "", "", NOW);
        NewsService.News rss = new NewsService.News("xataka:1", "xataka", "Xataka", "Ransomware ataca", "https://www.xataka.com/a?utm_medium=x", "", "", "", "", NOW - 1000);
        List<NewsService.News> out = NewsService.dedupeAndSort(List.of(hn, rss), false);
        assertEquals(1, out.size());
        assertEquals("xataka", out.get(0).sourceKey());
        assertTrue(NewsService.matches(rss, "", "cyber"));
        assertFalse(NewsService.matches(rss, "", "hardware"));
        assertTrue(NewsService.matches(rss, "ransomware", "all"));
        assertFalse(NewsService.matches(rss, "nvidia", "all"));
    }

    @Test public void canonicalUrlAndHtmlStrip() {
        assertEquals("https://xataka.com/a?b=2&c=1", Text.canonicalUrl("https://WWW.Xataka.com/a/?utm_source=x&c=1&b=2#frag"));
        assertNull(Text.canonicalUrl("javascript:alert(1)"));
        assertEquals("A & B <x> é", Text.htmlStrip("<p>A &amp; B &lt;x&gt; &#233;</p>"));
    }
}
