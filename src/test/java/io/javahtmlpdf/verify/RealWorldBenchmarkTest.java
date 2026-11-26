package io.javahtmlpdf.verify;

import io.javahtmlpdf.HtmlToPdf;
import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.RenderResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 Real-World Benchmark Suite: verifies full end-to-end documents combining
 * Flexbox, CSS Grid, Normal Flow, images, lists, tables, and multi-page pagination.
 */
class RealWorldBenchmarkTest {

    @Test
    void alpineFieldReportMatchesHeadlessChrome() throws Exception {
        VisualDiffBenchmark benchmark = new VisualDiffBenchmark();
        Assumptions.assumeTrue(
                benchmark.findChromeBinary().isPresent(),
                "Skipping Tier 3 visual diff: Chrome/Chromium not installed"
        );

        byte[] ttfBytes = loadBundledLiberationSans();
        String fontBase64 = Base64.getEncoder().encodeToString(ttfBytes);
        String sunrisePng = createMountainPhotoBase64(340, 120, new Color(30, 58, 95), new Color(200, 107, 82));
        String lakePng = createMountainPhotoBase64(340, 120, new Color(21, 59, 80), new Color(66, 158, 166));

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <style>
                    @page { size: A4; margin: 40px; }
                    @font-face {
                      font-family: 'LibSans';
                      src: url('data:font/ttf;base64,%s') format('truetype');
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: 'LibSans', sans-serif;
                      font-size: 13px;
                      line-height: 1.45;
                      color: #1f2937;
                      background: #ffffff;
                    }
                    .doc-header {
                      display: flex;
                      justify-content: space-between;
                      align-items: center;
                      padding: 16px 18px;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      background: #f9fafb;
                      margin-bottom: 16px;
                    }
                    .doc-header h1 { margin: 0; font-size: 20px; font-weight: normal; color: #111827; }
                    .doc-header .sub { margin: 3px 0 0 0; font-size: 12px; color: #6b7280; }
                    .badge {
                      padding: 5px 12px;
                      border-radius: 12px;
                      background: #e0f2fe;
                      color: #0369a1;
                      font-size: 11px;
                    }
                    .intro {
                      padding: 16px 18px;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      margin-bottom: 16px;
                    }
                    .intro h2 { margin: 0 0 8px 0; font-size: 15px; font-weight: normal; color: #111827; }
                    .intro p { margin: 0 0 10px 0; }
                    .intro a { color: #1d4ed8; text-decoration: underline; }
                    blockquote {
                      margin: 0;
                      padding: 10px 14px;
                      border-left: 3px solid #0d9488;
                      background: #f3f4f6;
                      color: #4b5563;
                      font-size: 12px;
                    }
                    .photo-grid, .data-grid {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: 16px;
                      margin-bottom: 16px;
                    }
                    .photo-card {
                      margin: 0;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      overflow: hidden;
                      background: #ffffff;
                    }
                    .photo-card img {
                      display: block;
                      width: 100%%;
                      height: 120px;
                    }
                    .photo-card figcaption {
                      padding: 10px 12px;
                      font-size: 12px;
                      color: #4b5563;
                    }
                    .cap-title {
                      margin: 0 0 2px 0;
                      color: #111827;
                      font-size: 13px;
                    }
                    .box-panel {
                      padding: 14px 16px;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      background: #ffffff;
                    }
                    .box-panel h3 { margin: 0 0 10px 0; font-size: 14px; font-weight: normal; color: #111827; }
                    ul { margin: 0; padding-left: 20px; color: #374151; }
                    li { margin-bottom: 6px; }
                    table { width: 100%%; border-collapse: collapse; font-size: 12px; }
                    th {
                      text-align: left;
                      padding: 6px 8px;
                      border-bottom: 2px solid #d1d5db;
                      color: #4b5563;
                      font-weight: normal;
                    }
                    td {
                      padding: 6px 8px;
                      border-bottom: 1px solid #e5e7eb;
                    }
                    .doc-footer {
                      display: flex;
                      justify-content: space-between;
                      border-top: 1px solid #d1d5db;
                      padding-top: 10px;
                      font-size: 11px;
                      color: #6b7280;
                    }
                  </style>
                </head>
                <body>
                  <header class="doc-header">
                    <div>
                      <h1>Alpine Field Report</h1>
                      <p class="sub">Autumn Expedition - Swiss Alps</p>
                    </div>
                    <div class="badge">Report #04</div>
                  </header>

                  <section class="intro">
                    <h2>Overview</h2>
                    <p>This benchmark exercises Flexbox, CSS Grid, Normal Flow, embedded photos, lists, tables,
                       and <a href="https://example.com/alpine">clickable PDF links</a> in one document.</p>
                    <blockquote>Every HTML page is a tree of semantic boxes arranged by CSS formatting contexts.</blockquote>
                  </section>

                  <section class="photo-grid">
                    <figure class="photo-card">
                      <img src="data:image/png;base64,%s" alt="Ridge Camp" />
                      <figcaption>
                        <p class="cap-title">Ridge Camp at Dawn</p>
                        Elevation 2,410 m - Clear skies
                      </figcaption>
                    </figure>
                    <figure class="photo-card">
                      <img src="data:image/png;base64,%s" alt="Glacier Lake" />
                      <figcaption>
                        <p class="cap-title">Glacier Valley Lake</p>
                        Water temperature 6 C - Light wind
                      </figcaption>
                    </figure>
                  </section>

                  <section class="data-grid">
                    <div class="box-panel">
                      <h3>Packing Checklist</h3>
                      <ul>
                        <li>Shell jacket for wind protection</li>
                        <li>Topographic map and compass</li>
                        <li>Headlamp with spare batteries</li>
                      </ul>
                    </div>
                    <div class="box-panel">
                      <h3>Trail Log</h3>
                      <table>
                        <thead>
                          <tr><th>Stage</th><th>Dist.</th><th>Gain</th></tr>
                        </thead>
                        <tbody>
                          <tr><td>Base to Ridge</td><td>6.4 km</td><td>+820 m</td></tr>
                          <tr><td>Ridge to Lake</td><td>4.1 km</td><td>-310 m</td></tr>
                          <tr><td>Lake to Village</td><td>7.8 km</td><td>-510 m</td></tr>
                        </tbody>
                      </table>
                    </div>
                  </section>

                  <footer class="doc-footer">
                    <div>Generated with java-html-pdf</div>
                    <div>Page 1 of 1</div>
                  </footer>
                </body>
                </html>
                """.formatted(fontBase64, sunrisePng, lakePng);

        Path tempHtml = Files.createTempFile("alpine-field-report-", ".html");
        try {
            Files.writeString(tempHtml, html, StandardCharsets.UTF_8);
            byte[] chromePdf = benchmark.renderWithChrome(tempHtml);

            RenderResult actual = HtmlToPdf.builder()
                    .html(html)
                    .pageSize(PageSize.A4)
                    .font("LibSans", false, false, ttfBytes)
                    .render();

            assertFalse(actual.hasWarnings(), () -> "Unexpected warnings: " + actual.diagnostics());

            try (PdfAssertions pdf = PdfAssertions.assertThat(actual.pdfBytes())) {
                pdf.hasPageCount(1)
                        .containsTextInOrder("Alpine Field Report", "Overview")
                        .containsTextInOrder("Ridge Camp at Dawn", "Packing Checklist")
                        .containsTextInOrder("Trail Log", "Generated with java-html-pdf")
                        .hasAnnotationCount(0, 1);
            }

            VisualDiffBenchmark.BenchmarkReport report =
                    benchmark.comparePdfs("alpine-field-report", chromePdf, actual.pdfBytes());

            System.out.printf("Phase 4 Benchmark (alpine-field-report): %.2f%% pixel similarity (diff: %s)%n",
                    report.averageSimilarityPercent(), report.pages().getFirst().diffImage());

            assertEquals(1, report.referencePages());
            assertEquals(1, report.actualPages());
            assertTrue(
                    report.averageSimilarityPercent() >= 95.0,
                    () -> String.format("Expected >= 95.0%% similarity on Alpine Field Report, got %.2f%% (diff: %s)",
                            report.averageSimilarityPercent(), report.pages().getFirst().diffImage())
            );
        } finally {
            Files.deleteIfExists(tempHtml);
        }
    }

    @Test
    void multipageBusinessInvoiceRendersCleanlyWithRepeatingHeadersAndTotals() throws Exception {
        byte[] ttfBytes = loadBundledLiberationSans();

        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 32; i++) {
            rows.append("<tr>")
                    .append("<td>Consulting &amp; Engineering Services — Task #").append(i).append("</td>")
                    .append("<td class=\"num\">4 hrs</td>")
                    .append("<td class=\"num\">$150.00</td>")
                    .append("<td class=\"num\">$600.00</td>")
                    .append("</tr>\n");
        }

        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8" />
                  <style>
                    @page {
                      size: A4;
                      margin: 64px 48px 64px 48px;
                      @top-left { content: "ACME STUDIO LLC"; font-size: 10px; color: #6b7280; }
                      @top-right { content: "INV-2026-0042"; font-size: 10px; color: #6b7280; }
                      @bottom-center { content: "Page " counter(page) " of " counter(pages); font-size: 10px; color: #6b7280; }
                    }
                    * { box-sizing: border-box; }
                    body { margin: 0; font-family: 'LibSans', sans-serif; font-size: 12px; color: #1f2937; }
                    .inv-header {
                      display: flex;
                      justify-content: space-between;
                      align-items: center;
                      padding-bottom: 14px;
                      border-bottom: 2px solid #1d4ed8;
                      margin-bottom: 16px;
                    }
                    .inv-header h1 { margin: 0; font-size: 24px; font-weight: normal; color: #111827; }
                    .inv-status {
                      padding: 6px 14px;
                      border-radius: 12px;
                      background: #dcfce7;
                      color: #166534;
                      font-size: 12px;
                    }
                    .meta-grid {
                      display: grid;
                      grid-template-columns: repeat(3, 1fr);
                      gap: 12px;
                      margin-bottom: 18px;
                    }
                    .meta-card {
                      padding: 12px;
                      border: 1px solid #d1d5db;
                      border-radius: 6px;
                      background: #f9fafb;
                    }
                    .meta-label { margin: 0 0 4px 0; font-size: 10px; color: #6b7280; }
                    .meta-value { margin: 0; font-size: 13px; color: #111827; }
                    table { width: 100%%; border-collapse: collapse; margin-bottom: 18px; }
                    th {
                      padding: 9px 10px;
                      background: #eff6ff;
                      border-bottom: 2px solid #93c5fd;
                      text-align: left;
                      color: #1e3a8a;
                    }
                    td {
                      padding: 7px 10px;
                      border-bottom: 1px solid #e5e7eb;
                    }
                    .num { text-align: right; }
                    .totals {
                      break-inside: avoid;
                      margin-left: auto;
                      width: 260px;
                      padding: 14px 16px;
                      border: 1px solid #d1d5db;
                      border-radius: 8px;
                      background: #f9fafb;
                    }
                    .total-row {
                      display: flex;
                      justify-content: space-between;
                      margin-bottom: 6px;
                    }
                    .grand-total {
                      display: flex;
                      justify-content: space-between;
                      padding-top: 8px;
                      border-top: 2px solid #111827;
                      font-size: 15px;
                      color: #111827;
                    }
                  </style>
                </head>
                <body>
                  <header class="inv-header">
                    <div>
                      <h1>INVOICE</h1>
                    </div>
                    <div class="inv-status">DUE NET 30</div>
                  </header>

                  <section class="meta-grid">
                    <div class="meta-card">
                      <p class="meta-label">BILL TO</p>
                      <p class="meta-value">Northwind Traders Inc.</p>
                    </div>
                    <div class="meta-card">
                      <p class="meta-label">INVOICE DATE</p>
                      <p class="meta-value">November 26, 2026</p>
                    </div>
                    <div class="meta-card">
                      <p class="meta-label">AMOUNT DUE</p>
                      <p class="meta-value">$19,200.00</p>
                    </div>
                  </section>

                  <table>
                    <thead>
                      <tr>
                        <th style="width: 52%%;">Description</th>
                        <th class="num" style="width: 14%%;">Qty</th>
                        <th class="num" style="width: 16%%;">Rate</th>
                        <th class="num" style="width: 18%%;">Total</th>
                      </tr>
                    </thead>
                    <tbody>
                      %s
                    </tbody>
                  </table>

                  <section class="totals">
                    <div class="total-row"><div>Subtotal</div><div>$19,200.00</div></div>
                    <div class="total-row"><div>Tax (0%%)</div><div>$0.00</div></div>
                    <div class="grand-total"><div>Total Due</div><div>$19,200.00</div></div>
                  </section>
                </body>
                </html>
                """.formatted(rows);

        RenderResult result = HtmlToPdf.builder()
                .html(html)
                .pageSize(PageSize.A4)
                .font("LibSans", false, false, ttfBytes)
                .render();

        assertFalse(result.hasWarnings(), () -> "Unexpected warnings: " + result.diagnostics());

        // Save rendered pages to target/preview/ for inspection
        Path outPdf = Path.of("target", "preview", "invoice-multipage.pdf");
        Files.createDirectories(outPdf.getParent());
        Files.write(outPdf, result.pdfBytes());

        try (PdfAssertions pdf = PdfAssertions.assertThat(result.pdfBytes())) {
            pdf.hasPageCount(2)
                    .pageContainsText(0, "ACME STUDIO LLC")
                    .pageContainsText(0, "INV-2026-0042")
                    .pageContainsText(0, "Northwind Traders Inc.")
                    .pageContainsText(0, "Description")
                    .pageContainsText(0, "Task #1")
                    .pageContainsText(0, "Page 1 of 2")
                    .pageContainsText(1, "ACME STUDIO LLC")
                    .pageContainsText(1, "Description") // repeating <thead> on Page 2
                    .pageContainsText(1, "Task #32")
                    .pageContainsText(1, "Total Due")
                    .pageContainsText(1, "Page 2 of 2");
        }

        // Rasterize both pages so they can be visually audited
        Path diffDir = Path.of("target", "visual-diffs");
        Files.createDirectories(diffDir);
        try (PDDocument doc = Loader.loadPDF(result.pdfBytes())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                BufferedImage img = renderer.renderImageWithDPI(p, 150f);
                ImageIO.write(img, "png", diffDir.resolve("invoice-multipage-page-" + (p + 1) + ".png").toFile());
            }
        }
    }

    private byte[] loadBundledLiberationSans() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            Assumptions.assumeTrue(in != null, "Bundled LiberationSans-Regular.ttf not found");
            return in.readAllBytes();
        }
    }

    private static String createMountainPhotoBase64(int w, int h, Color skyTop, Color skyBottom) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, skyTop, 0, h, skyBottom));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(26, 37, 56));
        g.fillPolygon(new int[]{0, w / 3, (w * 2) / 3}, new int[]{h, 30, h}, 3);
        g.fillPolygon(new int[]{w / 2, (w * 3) / 4, w}, new int[]{h, 22, h}, 3);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
