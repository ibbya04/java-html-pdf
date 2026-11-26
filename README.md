# java-html-pdf

A pure-Java HTML5 + CSS3 (Flexbox & CSS Grid) to PDF rendering library tailored for business and editorial documents—invoices, reports, dashboards, tables, photos, and multi-page pagination.

Reuses **Jsoup** for HTML5 parsing and **Apache PDFBox** for PDF serialization, while owning the CSS parser/cascade, Normal Flow, Flexbox, CSS Grid, Table layout, and multi-page pagination engine in pure Java 21.

## Quickstart

```java
RenderResult result = HtmlToPdf.builder()
    .html("""
        <!DOCTYPE html>
        <html>
        <head>
          <style>
            @page {
              size: A4;
              margin: 48px;
              @bottom-right { content: "Page " counter(page) " of " counter(pages); }
            }
            .header { display: flex; justify-content: space-between; align-items: center; }
            .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
          </style>
        </head>
        <body>
          <header class="header">
            <h1>Alpine Field Report</h1>
            <span>Report #04</span>
          </header>
        </body>
        </html>
        """)
    .pageSize(PageSize.A4)
    .render();

Files.write(Path.of("report.pdf"), result.pdfBytes());
```

Custom TrueType/OpenType fonts can be registered via `.font(family, bold, italic, pathOrBytes)`. Without custom fonts, the library falls back to PDF Standard 14 fonts (Helvetica, Times, Courier).

## Architecture

1. **HTML5 Parsing (`Jsoup`)** → DOM Tree
2. **CSS3 Parser & Cascade (`io.javahtmlpdf.css`)** → Styled Tree + `@page` & Margin Boxes + non-fatal `RenderDiagnostic` warnings
3. **Layout Engine (`io.javahtmlpdf.layout`)** → Normal Flow (Block + Inline word wrapping with Font Metrics), Flexbox (1D), CSS Grid (2D), and HTML Tables
4. **Paginator (`io.javahtmlpdf.layout.Paginator`)** → Multi-page fragmentation (`break-before/after/inside`, orphans/widows, repeating `<thead>`, `@page` `"Page X of Y"` counters)
5. **PDF Painter (`io.javahtmlpdf.pdf.PdfPainter` + `Apache PDFBox`)** → Vector rectangles, rounded corners, per-side borders, clipped images, embedded fonts, underlines, and clickable PDF link annotations

See [GLOSSARY.md](./GLOSSARY.md) and [docs/adr/](./docs/adr/) for domain terminology and architectural decisions.

## 3-Tier Deterministic Verification

Run the full test suite (requires Java 21, Maven, and Chrome/Chromium for Tier 3 visual diffs):

```bash
mvn test
```

* **Tier 1 — Geometry Oracle:** Compares computed `(x, y, width, height)` boxes directly against Chromium-verified fixtures (including bundled `DioxusLabs/taffy` XML conformance fixtures).
* **Tier 2 — PDF Structural Assertions:** Reloads generated PDFs with PDFBox to verify page counts, physical `MediaBox` sizes, text reading order, repeating table headers, running `"Page X of Y"` counters, and link annotations.
* **Tier 3 — Visual Diff Benchmark vs. Headless Chrome:** Prints HTML fixtures with headless Chrome (`--print-to-pdf`), rasterizes both PDFs at 150 DPI, computes pixel similarity %, and outputs 3-panel `[Chrome | java-html-pdf | Diff]` PNGs under `target/visual-diffs/`.

### Manual Scratchpad Runner

Render any local `.html` file to PDF and compare it against headless Chrome:

```bash
mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=io.javahtmlpdf.verify.RenderScratchpad \
  -Dexec.args="src/test/resources/fixtures/visual/hello-box.html"
```
