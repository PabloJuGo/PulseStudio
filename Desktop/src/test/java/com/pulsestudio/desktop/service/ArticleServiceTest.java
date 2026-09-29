package com.pulsestudio.desktop.service;

import com.pulsestudio.desktop.server.ApiException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Port de tests/article.test.cjs de la app móvil: misma lógica de extracción, ahora en el backend Java. */
public class ArticleServiceTest {
    private static final String ARTICLE = "La empresa presenta nuevos procesadores para centros de datos. Según el fabricante, las pruebas comenzaron en abril y la distribución está prevista para octubre. ".repeat(8);

    @Test public void longJinaPageKeepsOnlyTheArticle() {
        List<String> lines = new ArrayList<>(List.of("Title: Noticias de prueba", "URL Source: https://hackread.com/test", "Markdown Content:"));
        for (int i = 0; i < 120; i++) lines.add("- [Navigation " + i + "](https://example.com/menu/" + i + ")");
        lines.addAll(List.of("# Noticias de prueba", "Por Redacción · 23 de septiembre", "", ARTICLE, "", "## Publicidad", "Publicidad", "", ARTICLE, "",
            "## Noticias relacionadas", "ARTÍCULO AJENO Y PUBLICIDAD ".repeat(5000)));
        String result = ArticleService.fromReader(String.join("\n", lines), "Noticias de prueba");
        assertTrue(result.contains("La empresa presenta nuevos procesadores"));
        assertFalse(result.contains("Navigation 42"));
        assertFalse(result.contains("ARTÍCULO AJENO"));
        assertFalse(result.contains("URL Source"));
        assertTrue(result.length() <= 8200);
    }

    @Test public void tokenBudgetIsNeverExceeded() {
        assertTrue(ArticleService.fromReader("# Noticias de prueba\n\n" + ARTICLE.repeat(50), "Noticias de prueba").length() <= 8200);
        assertTrue(ArticleService.trimArticle("Noticias de prueba. ".repeat(900)).length() <= 8200);
    }

    @Test public void cjkTextUsesTighterBudget() {
        String title = "日本の技術ニュース", content = "新しい技術の研究が公表されました。発表によると、研究者は新型プロセッサーを開発しています。";
        String result = ArticleService.fromReader("# " + title + "\n\n" + content.repeat(200), title);
        assertTrue(result.length() <= 2900);
        assertTrue(result.length() >= 240);
        assertEquals(2900, ArticleService.articleLimit(result));
    }

    @Test public void largePageWithoutHeadingFails() {
        try {
            ArticleService.fromReader("Markdown Content:\n[Home](https://example.com)\n" + ARTICLE.repeat(100), "Noticias de prueba");
            fail("debería fallar");
        } catch (ApiException e) { assertTrue(e.getMessage().contains("No se ha podido localizar el titular")); }
    }

    @Test public void plainReaderTextWithoutMarkdownWrapper() {
        String result = ArticleService.fromReader("A technology company announced a new chip for data centres. ".repeat(40), "A company announces a new chip");
        assertTrue(result.contains("announced a new chip"));
        assertTrue(result.length() < 8201);
    }

    @Test public void titleComparisonToleratesAccents() {
        assertTrue(ArticleService.titleMatch("# Análisis: novedades en Inteligencia Artificial", "Novedades en inteligencia artificial"));
        assertFalse(ArticleService.titleMatch("# Otro contenido sin relación", "Novedades en inteligencia artificial"));
    }

    @Test public void manualTextHasPriorityAndInvalidUrlIsRejected() {
        ArticleService svc = new ArticleService(null, null); // no toca la red cuando hay texto manual
        String manual = "Texto revisado por la persona usuaria. ".repeat(400);
        ArticleService.Evidence ev = svc.extract("https://hackread.com/test", "Noticias", manual, "", "", "");
        assertFalse(ev.automatic());
        assertTrue(ev.text().length() <= 8200);
        try { svc.extract("javascript:alert(1)", "x", manual, "", "", ""); fail(); } catch (ApiException e) { assertEquals(400, e.status()); }
    }
}
