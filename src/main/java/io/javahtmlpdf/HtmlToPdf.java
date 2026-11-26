package io.javahtmlpdf;

import io.javahtmlpdf.css.StyleResolver;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.layout.LayoutEngine;
import io.javahtmlpdf.layout.Paginator;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.PageSheet;
import io.javahtmlpdf.pdf.PdfPainter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent public entry point for rendering HTML5 + CSS3 Source Documents into PDFs.
 *
 * <pre>{@code
 * RenderResult result = HtmlToPdf.builder()
 *     .html("<h1>Hello</h1>")
 *     .pageSize(PageSize.A4)
 *     .render();
 * }</pre>
 */
public final class HtmlToPdf {

    private HtmlToPdf() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String html = "";
        private PageSize pageSize = PageSize.A4;
        private Path baseDir;
        private ResourceLoader resourceLoader;
        private final FontRegistry fontRegistry = new FontRegistry();

        public Builder html(String html) {
            this.html = Objects.requireNonNull(html, "html must not be null");
            return this;
        }

        public Builder pageSize(PageSize pageSize) {
            this.pageSize = Objects.requireNonNull(pageSize, "pageSize must not be null");
            return this;
        }

        public Builder baseDirectory(Path baseDir) {
            this.baseDir = baseDir;
            return this;
        }

        public Builder resourceLoader(ResourceLoader resourceLoader) {
            this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader must not be null");
            return this;
        }

        /**
         * Registers a custom TrueType/OpenType font file for a CSS font-family name.
         */
        public Builder font(String family, boolean bold, boolean italic, Path ttfPath) throws IOException {
            Objects.requireNonNull(ttfPath, "ttfPath must not be null");
            return font(family, bold, italic, Files.readAllBytes(ttfPath));
        }

        /**
         * Registers a custom TrueType/OpenType font from raw bytes for a CSS font-family name.
         */
        public Builder font(String family, boolean bold, boolean italic, byte[] ttfBytes) {
            Objects.requireNonNull(family, "family must not be null");
            Objects.requireNonNull(ttfBytes, "ttfBytes must not be null");
            fontRegistry.register(family, bold, italic, ttfBytes);
            return this;
        }

        public RenderResult render() throws IOException {
            List<RenderDiagnostic> diagnostics = new ArrayList<>();
            ResourceLoader loader = this.resourceLoader != null
                    ? this.resourceLoader
                    : ResourceLoader.safeLocal(this.baseDir);

            Document document = Jsoup.parse(html);
            StyleResolver.ResolvedDocument resolved = new StyleResolver()
                    .resolve(document, pageSize, loader, diagnostics);

            PageSize effectivePage = resolved.pageSize();
            Edges pageMargins = resolved.pageMarginsPx();
            float contentWidthPx = Math.max(0f, effectivePage.widthPx() - pageMargins.horizontal());

            try (PDDocument pdDoc = new PDDocument()) {
                FontRegistry.LoadedFonts loadedFonts = fontRegistry.copy().bindTo(pdDoc);
                LayoutBox laidOut = new LayoutEngine().layout(
                        resolved.root(),
                        contentWidthPx,
                        pageMargins.left(),
                        pageMargins.top(),
                        loadedFonts
                );
                List<PageSheet> sheets = new Paginator().paginate(
                        laidOut, effectivePage, pageMargins, resolved.marginBoxes()
                );
                byte[] pdfBytes = new PdfPainter().paintDocument(pdDoc, sheets, loadedFonts);
                return new RenderResult(pdfBytes, diagnostics);
            }
        }

        public RenderResult renderTo(OutputStream out) throws IOException {
            RenderResult result = render();
            out.write(result.pdfBytes());
            return result;
        }
    }
}
