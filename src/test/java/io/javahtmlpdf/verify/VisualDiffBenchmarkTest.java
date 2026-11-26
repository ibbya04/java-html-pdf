package io.javahtmlpdf.verify;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualDiffBenchmarkTest {

    @Test
    void helloBoxMatchesHeadlessChromeEndToEnd() throws Exception {
        VisualDiffBenchmark benchmark = new VisualDiffBenchmark();
        Assumptions.assumeTrue(
                benchmark.findChromeBinary().isPresent(),
                "Skipping Tier 3 visual diff: Chrome/Chromium not installed"
        );

        URL fixtureUrl = getClass().getResource("/fixtures/visual/hello-box.html");
        Path htmlFile = Path.of(fixtureUrl.toURI());
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);

        byte[] chromePdf = benchmark.renderWithChrome(htmlFile);
        RenderResult actual = HtmlToPdf.builder()
                .html(html)
                .pageSize(PageSize.A4)
                .render();

        VisualDiffBenchmark.BenchmarkReport report = benchmark.comparePdfs("hello-box", chromePdf, actual.pdfBytes());

        assertEquals(1, report.referencePages());
        assertEquals(1, report.actualPages());
        assertTrue(Files.exists(report.pages().getFirst().diffImage()));
        assertTrue(
                report.averageSimilarityPercent() >= 99.5,
                () -> String.format("Expected >= 99.5%% visual similarity with Chrome, got %.2f%% (diff: %s)",
                        report.averageSimilarityPercent(), report.pages().getFirst().diffImage())
        );
    }

    @Test
    void phase1StyledArticleMatchesHeadlessChrome() throws Exception {
        VisualDiffBenchmark benchmark = new VisualDiffBenchmark();
        Assumptions.assumeTrue(
                benchmark.findChromeBinary().isPresent(),
                "Skipping Tier 3 visual diff: Chrome/Chromium not installed"
        );

        // Load LiberationSans-Regular.ttf bundled inside PDFBox so Chrome & Java use identical Font Metrics
        byte[] ttfBytes;
        try (InputStream in = getClass().getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Assumptions.assumeTrue(in != null, "Bundled LiberationSans-Regular.ttf not found");
            ttfBytes = in.readAllBytes();
        }
        String fontBase64 = Base64.getEncoder().encodeToString(ttfBytes);
        String photoBase64 = createBannerPhotoBase64(560, 140);

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <style>
                    @page { size: A4; margin: 48px; }
                    @font-face {
                      font-family: 'LibSans';
                      src: url('data:font/ttf;base64,%s') format('truetype');
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: 'LibSans', sans-serif;
                      font-size: 14px;
                      line-height: 1.5;
                      color: #1f2937;
                      background: #ffffff;
                    }
                    .card {
                      padding: 20px;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      background: #f9fafb;
                      margin-bottom: 18px;
                    }
                    h1 {
                      margin: 0 0 8px 0;
                      font-size: 24px;
                      font-weight: normal;
                      color: #111827;
                    }
                    h2 {
                      margin: 0 0 10px 0;
                      font-size: 18px;
                      font-weight: normal;
                      color: #1d4ed8;
                    }
                    p {
                      margin: 0 0 12px 0;
                    }
                    blockquote {
                      margin: 12px 0 0 0;
                      padding: 12px 16px;
                      border-left: 4px solid #0d9488;
                      background: #eef2f6;
                      color: #374151;
                    }
                    .photo {
                      display: block;
                      width: 100%%;
                      height: 140px;
                      border-radius: 6px;
                      margin-bottom: 18px;
                    }
                    ul {
                      margin: 0;
                      padding-left: 24px;
                    }
                    li {
                      margin-bottom: 6px;
                    }
                    a {
                      color: #1d4ed8;
                      text-decoration: underline;
                    }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Alpine Field Report</h1>
                    <p>This Phase 1 document exercises the pure-Java CSS parser, cascade, font-metric line wrapping,
                       rounded borders, blockquotes, lists, and <a href="https://example.com">hyperlinks</a>.</p>
                    <blockquote>Every HTML page is a tree of semantic boxes arranged by CSS formatting contexts.</blockquote>
                  </div>
                  <img class="photo" src="data:image/png;base64,%s" alt="Alpine banner" />
                  <div class="card">
                    <h2>Packing Checklist</h2>
                    <ul>
                      <li>Shell jacket for ridge wind</li>
                      <li>Topographic map and compass</li>
                      <li>Headlamp with spare batteries</li>
                    </ul>
                  </div>
                </body>
                </html>
                """.formatted(fontBase64, photoBase64);

        Path tempHtml = Files.createTempFile("article-phase1-", ".html");
        try {
            Files.writeString(tempHtml, html, StandardCharsets.UTF_8);
            byte[] chromePdf = benchmark.renderWithChrome(tempHtml);

            RenderResult actual = HtmlToPdf.builder()
                    .html(html)
                    .pageSize(PageSize.A4)
                    .font("LibSans", false, false, ttfBytes)
                    .render();

            VisualDiffBenchmark.BenchmarkReport report =
                    benchmark.comparePdfs("article-phase1", chromePdf, actual.pdfBytes());

            System.out.printf("Phase 1 Visual Diff Benchmark (article-phase1): %.2f%% pixel similarity (diff: %s)%n",
                    report.averageSimilarityPercent(), report.pages().getFirst().diffImage());

            assertEquals(1, report.referencePages());
            assertEquals(1, report.actualPages());
            assertTrue(
                    report.averageSimilarityPercent() >= 95.0,
                    () -> String.format("Expected >= 95.0%% visual similarity with Chrome, got %.2f%% (diff: %s)",
                            report.averageSimilarityPercent(), report.pages().getFirst().diffImage())
            );
        } finally {
            Files.deleteIfExists(tempHtml);
        }
    }

    private static String createBannerPhotoBase64(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(30, 58, 95), 0, h, new Color(200, 107, 82)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(26, 37, 56));
        g.fillPolygon(new int[]{0, w / 3, (w * 2) / 3}, new int[]{h, 35, h}, 3);
        g.fillPolygon(new int[]{w / 2, (w * 3) / 4, w}, new int[]{h, 25, h}, 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
