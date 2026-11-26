package io.javahtmlpdf.css;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderDiagnostic;
import io.javahtmlpdf.RenderResult;
import io.javahtmlpdf.ResourceLoader;
import io.javahtmlpdf.model.LayoutNode;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CssParserAndCascadeTest {

    @Test
    void parsesColorsAndLengthsAcrossAllSupportedUnits() {
        assertEquals(new CssColor(255, 0, 170, 1f), CssColor.parse("#f0a").orElseThrow());
        assertEquals(new CssColor(29, 78, 216, 1f), CssColor.parse("#1d4ed8").orElseThrow());
        assertEquals(new CssColor(10, 20, 30, 0.5f), CssColor.parse("rgba(10, 20, 30, 0.5)").orElseThrow());
        assertEquals(new CssColor(0, 128, 128, 1f), CssColor.parse("teal").orElseThrow());

        assertEquals(16f, CssLength.parse("12pt").orElseThrow().toPx(16f, 16f, 100f), 0.05f);
        assertEquals(96f, CssLength.parse("1in").orElseThrow().toPx(16f, 16f, 100f), 0.05f);
        assertEquals(37.795f, CssLength.parse("10mm").orElseThrow().toPx(16f, 16f, 100f), 0.1f);
        assertEquals(32f, CssLength.parse("2em").orElseThrow().toPx(16f, 16f, 100f), 0.05f);
        assertEquals(24f, CssLength.parse("1.5rem").orElseThrow().toPx(20f, 16f, 100f), 0.05f);
        assertEquals(250f, CssLength.parse("50%").orElseThrow().toPx(16f, 16f, 500f), 0.05f);
    }

    @Test
    void resolvesSpecificitySourceOrderInheritanceAndInlineStyles() {
        String html = """
                <html>
                <head>
                  <style>
                    body { color: #111111; font-size: 14px; }
                    p { color: #ff0000; }
                    .lead { color: #00aa00; }
                    #hero { color: #0000ff; }
                    .lead { font-size: 2em; }
                  </style>
                </head>
                <body>
                  <p id="hero" class="lead">Specificity winner</p>
                  <p class="lead" style="color: #222222;">Inline winner</p>
                </body>
                </html>
                """;
        List<RenderDiagnostic> diagnostics = new ArrayList<>();
        StyleResolver.ResolvedDocument resolved = new StyleResolver()
                .resolve(Jsoup.parse(html), PageSize.A4, ResourceLoader.safeLocal(null), diagnostics);

        assertTrue(diagnostics.isEmpty());
        LayoutNode hero = resolved.root().children().get(0);
        LayoutNode inlineWinner = resolved.root().children().get(1);

        assertEquals(new CssColor(0, 0, 255, 1f), hero.style().color());
        assertEquals(28f, hero.style().fontSizePx(), 0.01f); // 2em of body's 14px
        assertEquals(new CssColor(34, 34, 34, 1f), inlineWinner.style().color());
    }

    @Test
    void degradesGracefullyAndEmitsDiagnosticsForUnsupportedCss() throws Exception {
        String html = """
                <html>
                <head><style>p { filter: blur(4px); color: #333; }</style></head>
                <body><p>Still renders cleanly</p></body>
                </html>
                """;
        RenderResult result = HtmlToPdf.builder().html(html).render();
        assertNotNull(result.pdfBytes());
        assertTrue(result.hasWarnings());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> "UNSUPPORTED_CSS".equals(d.code()) && d.message().contains("filter")));
        assertFalse(result.pdfBytes().length == 0);
    }
}
