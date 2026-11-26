package io.javahtmlpdf.pdf;

import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.css.CssColor;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.InlineRun;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.LineBox;
import io.javahtmlpdf.model.MarginBoxSpec;
import io.javahtmlpdf.model.PageSheet;
import io.javahtmlpdf.model.Rect;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Paints a laid-out {@link LayoutBox} tree onto a PDF document using Apache PDFBox.
 * Handles CSS-px to PDF-pt scaling, Y-axis flipping, rounded rectangles, per-side borders,
 * embedded images, mixed inline text runs, list markers, underlines, and link annotations.
 */
public class PdfPainter {

    // Magic constant for approximating a 90-degree circular arc with a cubic Bézier curve
    private static final float BEZIER_CIRCLE = 0.55228475f;

    public byte[] paintSinglePage(LayoutBox root, PageSize pageSize) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            FontRegistry.LoadedFonts fonts = new FontRegistry().bindTo(doc);
            return paintSinglePage(doc, root, pageSize, fonts);
        }
    }

    public byte[] paintSinglePage(
            PDDocument doc,
            LayoutBox root,
            PageSize pageSize,
            FontRegistry.LoadedFonts fonts
    ) throws IOException {
        return paintDocument(doc, List.of(new PageSheet(1, 1, pageSize, Edges.ZERO, List.of(root), List.of())), fonts);
    }

    public byte[] paintDocument(
            PDDocument doc,
            List<PageSheet> sheets,
            FontRegistry.LoadedFonts fonts
    ) throws IOException {
        for (PageSheet sheet : sheets) {
            PageSize ps = sheet.pageSize();
            PDPage page = new PDPage(new PDRectangle(ps.widthPt(), ps.heightPt()));
            doc.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                for (LayoutBox box : sheet.contentBoxes()) {
                    paintBox(doc, page, stream, box, ps.heightPt(), fonts);
                }
                for (MarginBoxSpec mb : sheet.marginBoxes()) {
                    paintMarginBox(stream, sheet, mb, fonts);
                }
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void paintMarginBox(
            PDPageContentStream stream,
            PageSheet sheet,
            MarginBoxSpec mb,
            FontRegistry.LoadedFonts fonts
    ) throws IOException {
        String text = mb.evaluateText(sheet.pageNumber(), sheet.totalPages());
        if (text.isEmpty()) return;

        PDFont font = fonts.resolve(mb.fontFamily(), false, false);
        String safe = fonts.sanitizeForFont(font, text);
        float fontSizePt = mb.fontSizePx() * PageSize.PX_TO_PT;
        float textWidthPt = fonts.measureWidthPx(font, mb.fontSizePx(), safe) * PageSize.PX_TO_PT;

        PageSize ps = sheet.pageSize();
        Edges m = sheet.marginsPx();
        float leftPt = Math.max(18f, m.left() * PageSize.PX_TO_PT);
        float rightPt = ps.widthPt() - Math.max(18f, m.right() * PageSize.PX_TO_PT);

        float xPt = switch (mb.position()) {
            case TOP_LEFT, BOTTOM_LEFT -> leftPt;
            case TOP_CENTER, BOTTOM_CENTER -> (ps.widthPt() - textWidthPt) / 2f;
            case TOP_RIGHT, BOTTOM_RIGHT -> rightPt - textWidthPt;
        };

        float topMarginPt = Math.max(24f, m.top() * PageSize.PX_TO_PT);
        float bottomMarginPt = Math.max(24f, m.bottom() * PageSize.PX_TO_PT);
        float yPt = switch (mb.position()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> ps.heightPt() - (topMarginPt / 2f) - (fontSizePt / 3f);
            case BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT -> (bottomMarginPt / 2f) - (fontSizePt / 3f);
        };

        stream.setNonStrokingColor(mb.color().toAwtColor());
        stream.beginText();
        stream.setFont(font, fontSizePt);
        stream.newLineAtOffset(xPt, yPt);
        stream.showText(safe);
        stream.endText();
    }

    private void paintBox(
            PDDocument doc,
            PDPage page,
            PDPageContentStream stream,
            LayoutBox box,
            float pageHeightPt,
            FontRegistry.LoadedFonts fonts
    ) throws IOException {
        Rect rect = box.borderBox();
        LayoutStyle style = box.style();

        float xPt = rect.x() * PageSize.PX_TO_PT;
        float wPt = rect.width() * PageSize.PX_TO_PT;
        float hPt = rect.height() * PageSize.PX_TO_PT;
        float yPt = pageHeightPt - (rect.y() * PageSize.PX_TO_PT) - hPt;
        float radiusPt = Math.min(style.borderRadiusPx() * PageSize.PX_TO_PT, Math.min(wPt, hPt) / 2f);

        // 1. Background fill
        if (style.backgroundColor() != null && !style.backgroundColor().isTransparent() && wPt > 0 && hPt > 0) {
            stream.setNonStrokingColor(style.backgroundColor().toAwtColor());
            if (radiusPt > 0.1f) {
                addRoundedRectPath(stream, xPt, yPt, wPt, hPt, radiusPt);
            } else {
                stream.addRect(xPt, yPt, wPt, hPt);
            }
            stream.fill();
        }

        // 2. Borders
        paintBorders(stream, xPt, yPt, wPt, hPt, radiusPt, style);

        // Optional overflow: hidden clipping for rounded cards containing images/children
        boolean clipped = style.overflowHidden() && wPt > 0 && hPt > 0;
        if (clipped) {
            stream.saveGraphicsState();
            if (radiusPt > 0.1f) {
                addRoundedRectPath(stream, xPt, yPt, wPt, hPt, radiusPt);
            } else {
                stream.addRect(xPt, yPt, wPt, hPt);
            }
            stream.clip();
        }

        // 3. Embedded image (<img>)
        if (box.imageBytes() != null && wPt > 0 && hPt > 0) {
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, box.imageBytes(), box.id());
            if (radiusPt > 0.1f && !clipped) {
                stream.saveGraphicsState();
                addRoundedRectPath(stream, xPt, yPt, wPt, hPt, radiusPt);
                stream.clip();
                stream.drawImage(pdImage, xPt, yPt, wPt, hPt);
                stream.restoreGraphicsState();
            } else {
                stream.drawImage(pdImage, xPt, yPt, wPt, hPt);
            }
        }

        // 4. List bullet marker
        if (box.listMarker() != null) {
            float markerSizePt = style.fontSizePx() * PageSize.PX_TO_PT;
            float baselineYPt;
            if (!box.lines().isEmpty()) {
                baselineYPt = pageHeightPt - (box.lines().getFirst().baselineY() * PageSize.PX_TO_PT);
            } else {
                baselineYPt = yPt + hPt - markerSizePt;
            }
            float bulletRadiusPt = Math.max(1.5f, markerSizePt * 0.16f);
            float bulletCenterXPt = xPt - (12f * PageSize.PX_TO_PT);
            float bulletCenterYPt = baselineYPt + (markerSizePt * 0.28f);
            stream.setNonStrokingColor(style.color().toAwtColor());
            addRoundedRectPath(
                    stream,
                    bulletCenterXPt - bulletRadiusPt,
                    bulletCenterYPt - bulletRadiusPt,
                    bulletRadiusPt * 2f,
                    bulletRadiusPt * 2f,
                    bulletRadiusPt
            );
            stream.fill();
        }

        // 5. Wrapped text lines & inline runs
        for (LineBox line : box.lines()) {
            for (InlineRun run : line.runs()) {
                paintInlineRun(page, stream, run, pageHeightPt, fonts);
            }
        }

        // 6. Child boxes
        for (LayoutBox child : box.children()) {
            paintBox(doc, page, stream, child, pageHeightPt, fonts);
        }

        if (clipped) {
            stream.restoreGraphicsState();
        }
    }

    private void paintInlineRun(
            PDPage page,
            PDPageContentStream stream,
            InlineRun run,
            float pageHeightPt,
            FontRegistry.LoadedFonts fonts
    ) throws IOException {
        LayoutStyle s = run.style();
        PDFont font = fonts.resolve(s.fontFamily(), s.bold(), s.italic());
        String safeText = fonts.sanitizeForFont(font, run.text());
        if (safeText.isEmpty()) {
            return;
        }

        float fontSizePt = s.fontSizePx() * PageSize.PX_TO_PT;
        float xPt = run.x() * PageSize.PX_TO_PT;
        float wPt = run.width() * PageSize.PX_TO_PT;
        float baselineYPt = pageHeightPt - (run.baselineY() * PageSize.PX_TO_PT);

        // Inline background highlight (e.g. inline <code>)
        if (s.backgroundColor() != null && !s.backgroundColor().isTransparent() && wPt > 0) {
            float bgH = fontSizePt * 1.25f;
            float bgY = baselineYPt - (fontSizePt * 0.25f);
            stream.setNonStrokingColor(s.backgroundColor().toAwtColor());
            stream.addRect(xPt, bgY, wPt, bgH);
            stream.fill();
        }

        stream.setNonStrokingColor(s.color().toAwtColor());
        stream.beginText();
        stream.setFont(font, fontSizePt);
        stream.newLineAtOffset(xPt, baselineYPt);
        stream.showText(safeText);
        stream.endText();

        if (s.underline() && wPt > 0) {
            float underlineY = baselineYPt - Math.max(1f, fontSizePt * 0.12f);
            stream.setStrokingColor(s.color().toAwtColor());
            stream.setLineWidth(Math.max(0.6f, fontSizePt * 0.06f));
            stream.moveTo(xPt, underlineY);
            stream.lineTo(xPt + wPt, underlineY);
            stream.stroke();
        }

        if (run.linkHref() != null && !run.linkHref().isBlank() && wPt > 0) {
            addLinkAnnotation(page, xPt, baselineYPt - fontSizePt * 0.25f, wPt, fontSizePt * 1.25f, run.linkHref());
        }
    }

    private void addLinkAnnotation(PDPage page, float x, float y, float w, float h, String href) throws IOException {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(x, y, w, h));
        PDBorderStyleDictionary borderStyle = new PDBorderStyleDictionary();
        borderStyle.setWidth(0);
        link.setBorderStyle(borderStyle);

        PDActionURI action = new PDActionURI();
        action.setURI(href);
        link.setAction(action);
        page.getAnnotations().add(link);
    }

    private void paintBorders(
            PDPageContentStream stream,
            float xPt, float yPt, float wPt, float hPt,
            float radiusPt,
            LayoutStyle style
    ) throws IOException {
        Edges b = style.border();
        if (wPt <= 0 || hPt <= 0) return;

        boolean allEqualWidth = b.top() > 0 && b.top() == b.right() && b.top() == b.bottom() && b.top() == b.left();
        boolean allEqualColor = style.borderTopColor().equals(style.borderRightColor())
                && style.borderTopColor().equals(style.borderBottomColor())
                && style.borderTopColor().equals(style.borderLeftColor());

        if (allEqualWidth && allEqualColor && !style.borderTopColor().isTransparent()) {
            float strokePt = b.top() * PageSize.PX_TO_PT;
            stream.setStrokingColor(style.borderTopColor().toAwtColor());
            stream.setLineWidth(strokePt);
            float inset = strokePt / 2f;
            float rw = Math.max(0f, wPt - strokePt);
            float rh = Math.max(0f, hPt - strokePt);
            if (radiusPt > 0.1f) {
                addRoundedRectPath(stream, xPt + inset, yPt + inset, rw, rh, Math.max(0f, radiusPt - inset));
            } else {
                stream.addRect(xPt + inset, yPt + inset, rw, rh);
            }
            stream.stroke();
            return;
        }

        // Per-side borders (e.g. blockquote left accent bar, table row bottom divider)
        if (b.top() > 0 && !style.borderTopColor().isTransparent()) {
            fillBorderRect(stream, style.borderTopColor(), xPt, yPt + hPt - b.top() * PageSize.PX_TO_PT, wPt, b.top() * PageSize.PX_TO_PT);
        }
        if (b.bottom() > 0 && !style.borderBottomColor().isTransparent()) {
            fillBorderRect(stream, style.borderBottomColor(), xPt, yPt, wPt, b.bottom() * PageSize.PX_TO_PT);
        }
        if (b.left() > 0 && !style.borderLeftColor().isTransparent()) {
            fillBorderRect(stream, style.borderLeftColor(), xPt, yPt, b.left() * PageSize.PX_TO_PT, hPt);
        }
        if (b.right() > 0 && !style.borderRightColor().isTransparent()) {
            fillBorderRect(stream, style.borderRightColor(), xPt + wPt - b.right() * PageSize.PX_TO_PT, yPt, b.right() * PageSize.PX_TO_PT, hPt);
        }
    }

    private void fillBorderRect(PDPageContentStream stream, CssColor color, float x, float y, float w, float h)
            throws IOException {
        stream.setNonStrokingColor(color.toAwtColor());
        stream.addRect(x, y, w, h);
        stream.fill();
    }

    private void addRoundedRectPath(PDPageContentStream stream, float x, float y, float w, float h, float r)
            throws IOException {
        float c = r * BEZIER_CIRCLE;
        stream.moveTo(x + r, y);
        stream.lineTo(x + w - r, y);
        stream.curveTo(x + w - r + c, y, x + w, y + r - c, x + w, y + r);
        stream.lineTo(x + w, y + h - r);
        stream.curveTo(x + w, y + h - r + c, x + w - r + c, y + h, x + w - r, y + h);
        stream.lineTo(x + r, y + h);
        stream.curveTo(x + r - c, y + h, x, y + h - r + c, x, y + h - r);
        stream.lineTo(x, y + r);
        stream.curveTo(x, y + r - c, x + r - c, y, x + r, y);
        stream.closePath();
    }
}
