package io.javahtmlpdf.verify;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;

class PdfAssertionsTest {

    @Test
    void rendersStyledArticleWithLinksListsAndImages() throws Exception {
        String pngBase64 = createSolidPngBase64(120, 60, new Color(30, 110, 180));
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    @page { size: letter; margin: 24px; }
                    body { font-family: sans-serif; color: #1f2937; }
                    blockquote { border-left: 3px solid #0d9488; padding: 8px 12px; background: #f3f4f6; }
                  </style>
                </head>
                <body>
                  <h1>Alpine Field Report</h1>
                  <p>Includes <strong>bold</strong>, <em>italic</em>, <code>code</code>, and a
                     <a href="https://example.com">reference link</a>.</p>
                  <blockquote>Every HTML page is a tree of semantic tags.</blockquote>
                  <img src="data:image/png;base64,%s" width="120" height="60" />
                  <ul>
                    <li>Shell jacket</li>
                    <li>Topographic map</li>
                  </ul>
                </body>
                </html>
                """.formatted(pngBase64);

        RenderResult result = HtmlToPdf.builder()
                .html(html)
                .pageSize(PageSize.A4) // @page { size: letter; } should override this
                .render();

        assertFalse(result.hasWarnings(), () -> "Unexpected warnings: " + result.diagnostics());

        try (PdfAssertions pdf = PdfAssertions.assertThat(result.pdfBytes())) {
            pdf.hasPageCount(1)
                    .hasPageSize(0, PageSize.LETTER, 1.0f)
                    .containsText("Alpine Field Report")
                    .containsTextInOrder("Alpine Field Report", "reference link")
                    .containsTextInOrder("reference link", "Shell jacket")
                    .containsTextInOrder("Shell jacket", "Topographic map")
                    .hasAnnotationCount(0, 1);
        }
    }

    private static String createSolidPngBase64(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
