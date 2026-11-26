package io.javahtmlpdf.layout;

import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.InlineRun;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.LineBox;
import io.javahtmlpdf.model.Rect;
import org.apache.pdfbox.pdmodel.font.PDFont;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes exact {@link LayoutBox} geometry and wrapped {@link LineBox}es for a {@link LayoutNode} tree.
 *
 * <p>Phase 1 implements Normal Flow (Block Formatting Context with sibling margin collapsing,
 * Inline Formatting Context with font-metric word wrapping &amp; mixed styles, lists, and {@code <img>} sizing).
 */
public class LayoutEngine {

    private static final float ROOT_FONT_SIZE_PX = 16f;

    public LayoutBox layout(LayoutNode root, float viewportWidthPx) {
        return layout(root, viewportWidthPx, 0f, 0f, null);
    }

    public LayoutBox layout(
            LayoutNode root,
            float viewportWidthPx,
            float startX,
            float startY,
            FontRegistry.LoadedFonts fonts
    ) {
        return layoutBlock(root, startX, startY, viewportWidthPx, fonts);
    }

    private LayoutBox layoutBlock(
            LayoutNode node,
            float originX,
            float originY,
            float containingWidth,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle style = node.style();
        if (style.display() == Display.NONE) {
            return new LayoutBox(node.id(), style, Rect.ZERO, List.of(), null, null, List.of());
        }

        if (node.isImageNode()) {
            return layoutImage(node, originX, originY, containingWidth);
        }

        Edges margin = style.margin();
        Edges padding = style.padding();
        Edges border = style.border();

        float borderBoxWidth = resolveBorderBoxWidth(style, containingWidth, margin, padding, border);

        // Horizontal margin: auto centering when an explicit/clamped width is smaller than containingWidth
        float marginLeft = margin.left();
        if (style.marginLeftAuto() && style.marginRightAuto()) {
            float remaining = Math.max(0f, containingWidth - borderBoxWidth);
            marginLeft = remaining / 2f;
        } else if (style.marginLeftAuto()) {
            marginLeft = Math.max(0f, containingWidth - borderBoxWidth - margin.right());
        }

        float boxX = originX + marginLeft;
        float boxY = originY + margin.top();

        float contentX = boxX + border.left() + padding.left();
        float contentStartY = boxY + border.top() + padding.top();
        float contentWidth = Math.max(0f, borderBoxWidth - border.horizontal() - padding.horizontal());

        float cursorY = contentStartY;
        List<LayoutBox> laidOutChildren = new ArrayList<>();
        List<LineBox> ownLines = new ArrayList<>();

        // Direct text leaf on node (e.g. Phase 0 tests or simple LayoutNode)
        if (node.text() != null && !node.text().isBlank() && node.children().isEmpty()) {
            List<InlinePiece> pieces = List.of(new InlinePiece(node.text(), style, node.linkHref(), false));
            List<LineBox> wrapped = layoutInlinePieces(pieces, contentX, cursorY, contentWidth, style.textAlign(), fonts);
            ownLines.addAll(wrapped);
            if (!wrapped.isEmpty()) {
                LineBox last = wrapped.getLast();
                cursorY = last.y() + last.height();
            }
        } else {
            // Partition children into contiguous inline runs vs block children
            List<InlinePiece> pendingInline = new ArrayList<>();
            float prevMarginBottom = 0f;
            boolean hasPrevBlock = false;

            for (LayoutNode child : node.children()) {
                if (child.style().display() == Display.NONE) {
                    continue;
                }
                if (isInlineFlowNode(child)) {
                    collectInlinePieces(child, pendingInline);
                } else {
                    if (!pendingInline.isEmpty()) {
                        if (hasPrevBlock) {
                            cursorY += prevMarginBottom;
                            hasPrevBlock = false;
                            prevMarginBottom = 0f;
                        }
                        List<LineBox> wrapped = layoutInlinePieces(pendingInline, contentX, cursorY, contentWidth, style.textAlign(), fonts);
                        ownLines.addAll(wrapped);
                        if (!wrapped.isEmpty()) {
                            LineBox last = wrapped.getLast();
                            cursorY = last.y() + last.height();
                        }
                        pendingInline.clear();
                    }

                    // Sibling vertical margin collapsing: max(prevMarginBottom, child.margin.top)
                    float childMarginTop = child.style().margin().top();
                    float effectiveTopGap = hasPrevBlock
                            ? Math.max(prevMarginBottom, childMarginTop) - childMarginTop
                            : 0f;

                    LayoutBox childBox = layoutBlock(child, contentX, cursorY + effectiveTopGap, contentWidth, fonts);
                    laidOutChildren.add(childBox);
                    cursorY = childBox.borderBox().bottom();
                    prevMarginBottom = child.style().margin().bottom();
                    hasPrevBlock = true;
                }
            }

            if (!pendingInline.isEmpty()) {
                if (hasPrevBlock) {
                    cursorY += prevMarginBottom;
                    hasPrevBlock = false;
                    prevMarginBottom = 0f;
                }
                List<LineBox> wrapped = layoutInlinePieces(pendingInline, contentX, cursorY, contentWidth, style.textAlign(), fonts);
                ownLines.addAll(wrapped);
                if (!wrapped.isEmpty()) {
                    LineBox last = wrapped.getLast();
                    cursorY = last.y() + last.height();
                }
                pendingInline.clear();
            }

            if (hasPrevBlock) {
                cursorY += prevMarginBottom;
            }
        }

        float contentHeight = Math.max(0f, cursorY - contentStartY);
        float borderBoxHeight = resolveBorderBoxHeight(style, contentHeight, padding, border);

        Rect rect = new Rect(boxX, boxY, borderBoxWidth, borderBoxHeight);
        String listMarker = style.listItem() ? "\u2022" : null;
        return new LayoutBox(node.id(), style, rect, ownLines, null, listMarker, laidOutChildren);
    }

    private LayoutBox layoutImage(LayoutNode node, float originX, float originY, float containingWidth) {
        LayoutStyle style = node.style();
        Edges margin = style.margin();
        Edges padding = style.padding();
        Edges border = style.border();

        float intrinsicW = 100f;
        float intrinsicH = 100f;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(node.imageBytes()));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                intrinsicW = img.getWidth();
                intrinsicH = img.getHeight();
            }
        } catch (IOException ignored) {
            // Fallback to default intrinsic size
        }

        float targetW;
        float targetH;
        if (style.width() != null && style.height() != null) {
            targetW = style.width().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, containingWidth);
            targetH = style.height().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, intrinsicH);
        } else if (style.width() != null) {
            targetW = style.width().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, containingWidth);
            targetH = targetW * (intrinsicH / intrinsicW);
        } else if (style.height() != null) {
            targetH = style.height().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, intrinsicH);
            targetW = targetH * (intrinsicW / intrinsicH);
        } else {
            targetW = Math.min(intrinsicW, containingWidth);
            targetH = targetW * (intrinsicH / intrinsicW);
        }

        if (style.maxWidth() != null) {
            float maxW = style.maxWidth().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, containingWidth);
            if (targetW > maxW) {
                float scale = maxW / targetW;
                targetW = maxW;
                if (style.height() == null) {
                    targetH *= scale;
                }
            }
        }

        float boxW = style.borderBox() ? targetW : targetW + padding.horizontal() + border.horizontal();
        float boxH = style.borderBox() ? targetH : targetH + padding.vertical() + border.vertical();
        float boxX = originX + margin.left();
        float boxY = originY + margin.top();

        return new LayoutBox(
                node.id(),
                style,
                new Rect(boxX, boxY, boxW, boxH),
                List.of(),
                node.imageBytes(),
                null,
                List.of()
        );
    }

    private float resolveBorderBoxWidth(
            LayoutStyle style,
            float containingWidth,
            Edges margin,
            Edges padding,
            Edges border
    ) {
        float width;
        if (style.width() != null) {
            float raw = style.width().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, containingWidth);
            width = style.borderBox() ? raw : raw + padding.horizontal() + border.horizontal();
        } else {
            width = Math.max(0f, containingWidth - margin.horizontal());
        }
        if (style.maxWidth() != null) {
            float maxW = style.maxWidth().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, containingWidth);
            float maxBorderBox = style.borderBox() ? maxW : maxW + padding.horizontal() + border.horizontal();
            width = Math.min(width, maxBorderBox);
        }
        return width;
    }

    private float resolveBorderBoxHeight(LayoutStyle style, float contentHeight, Edges padding, Edges border) {
        if (style.height() != null) {
            float raw = style.height().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, contentHeight);
            return style.borderBox() ? raw : raw + padding.vertical() + border.vertical();
        }
        return border.vertical() + padding.vertical() + contentHeight;
    }

    private static boolean isInlineFlowNode(LayoutNode node) {
        if (node.isImageNode()) {
            return false; // Phase 1 lays out images as block-level boxes
        }
        if (node.isTextNode()) {
            return true;
        }
        return node.style().display() == Display.INLINE;
    }

    private record InlinePiece(String text, LayoutStyle style, String linkHref, boolean forcedBreak) {}

    private void collectInlinePieces(LayoutNode node, List<InlinePiece> out) {
        if (node.isTextNode()) {
            if ("\n".equals(node.text())) {
                out.add(new InlinePiece("", node.style(), node.linkHref(), true));
            } else {
                out.add(new InlinePiece(node.text(), node.style(), node.linkHref(), false));
            }
            return;
        }
        for (LayoutNode child : node.children()) {
            collectInlinePieces(child, out);
        }
    }

    private record WordToken(String text, boolean whitespace, LayoutStyle style, String linkHref, boolean forcedBreak) {}

    private List<LineBox> layoutInlinePieces(
            List<InlinePiece> pieces,
            float startX,
            float startY,
            float maxWidth,
            LayoutStyle.TextAlign textAlign,
            FontRegistry.LoadedFonts fonts
    ) {
        // 1. Tokenize pieces into words and single-space separators
        List<WordToken> tokens = new ArrayList<>();
        for (InlinePiece piece : pieces) {
            if (piece.forcedBreak()) {
                tokens.add(new WordToken("", false, piece.style(), piece.linkHref(), true));
                continue;
            }
            String text = piece.text();
            int i = 0;
            while (i < text.length()) {
                if (text.charAt(i) == ' ') {
                    while (i < text.length() && text.charAt(i) == ' ') i++;
                    tokens.add(new WordToken(" ", true, piece.style(), piece.linkHref(), false));
                } else {
                    int start = i;
                    while (i < text.length() && text.charAt(i) != ' ') i++;
                    tokens.add(new WordToken(text.substring(start, i), false, piece.style(), piece.linkHref(), false));
                }
            }
        }

        // Trim leading and trailing whitespace tokens for the entire inline block
        while (!tokens.isEmpty() && tokens.getFirst().whitespace()) {
            tokens.removeFirst();
        }
        while (!tokens.isEmpty() && tokens.getLast().whitespace()) {
            tokens.removeLast();
        }
        if (tokens.isEmpty()) {
            return List.of();
        }

        // 2. Pack tokens into lines using exact Font Metrics
        List<LineBox> lines = new ArrayList<>();
        List<WordToken> currentLine = new ArrayList<>();
        float currentLineWidth = 0f;
        float cursorY = startY;

        for (WordToken tok : tokens) {
            if (tok.forcedBreak()) {
                LineBox lb = finalizeLine(currentLine, startX, cursorY, maxWidth, textAlign, fonts);
                if (lb != null) {
                    lines.add(lb);
                    cursorY += lb.height();
                } else {
                    cursorY += tok.style().fontSizePx() * tok.style().lineHeightMultiplier();
                }
                currentLine.clear();
                currentLineWidth = 0f;
                continue;
            }

            if (tok.whitespace() && currentLine.isEmpty()) {
                continue; // Never start a wrapped line with a space
            }

            float tokWidth = measureWidth(tok.text(), tok.style(), fonts);
            if (!currentLine.isEmpty() && !tok.whitespace() && currentLineWidth + tokWidth > maxWidth + 0.01f) {
                // Trim trailing space on wrapped line
                while (!currentLine.isEmpty() && currentLine.getLast().whitespace()) {
                    currentLine.removeLast();
                }
                LineBox lb = finalizeLine(currentLine, startX, cursorY, maxWidth, textAlign, fonts);
                if (lb != null) {
                    lines.add(lb);
                    cursorY += lb.height();
                }
                currentLine.clear();
                currentLineWidth = 0f;
            }

            currentLine.add(tok);
            currentLineWidth += tokWidth;
        }

        while (!currentLine.isEmpty() && currentLine.getLast().whitespace()) {
            currentLine.removeLast();
        }
        if (!currentLine.isEmpty()) {
            LineBox lb = finalizeLine(currentLine, startX, cursorY, maxWidth, textAlign, fonts);
            if (lb != null) {
                lines.add(lb);
            }
        }

        return lines;
    }

    private LineBox finalizeLine(
            List<WordToken> lineTokens,
            float startX,
            float lineY,
            float maxWidth,
            LayoutStyle.TextAlign textAlign,
            FontRegistry.LoadedFonts fonts
    ) {
        if (lineTokens.isEmpty()) {
            return null;
        }

        // Merge adjacent tokens that share identical LayoutStyle and linkHref
        List<WordToken> merged = new ArrayList<>();
        for (WordToken tok : lineTokens) {
            if (!merged.isEmpty()) {
                WordToken prev = merged.getLast();
                if (prev.style().equals(tok.style()) && java.util.Objects.equals(prev.linkHref(), tok.linkHref())) {
                    merged.set(merged.size() - 1, new WordToken(
                            prev.text() + tok.text(), false, prev.style(), prev.linkHref(), false
                    ));
                    continue;
                }
            }
            merged.add(tok);
        }

        float totalWidth = 0f;
        float lineHeight = 0f;
        float maxAscent = 0f;
        float[] widths = new float[merged.size()];

        for (int i = 0; i < merged.size(); i++) {
            WordToken tok = merged.get(i);
            LayoutStyle s = tok.style();
            widths[i] = measureWidth(tok.text(), s, fonts);
            totalWidth += widths[i];
            float lh = s.fontSizePx() * s.lineHeightMultiplier();
            if (lh > lineHeight) lineHeight = lh;
            float asc = measureAscent(s, fonts);
            if (asc > maxAscent) maxAscent = asc;
        }

        // Half-leading puts the baseline comfortably inside the line box (matching CSS line-height)
        float leading = Math.max(0f, lineHeight - (maxAscent * 1.25f));
        float baselineY = lineY + (leading / 2f) + maxAscent;

        float offsetX = switch (textAlign) {
            case CENTER -> startX + Math.max(0f, (maxWidth - totalWidth) / 2f);
            case RIGHT -> startX + Math.max(0f, maxWidth - totalWidth);
            case LEFT -> startX;
        };

        List<InlineRun> runs = new ArrayList<>(merged.size());
        float runX = offsetX;
        for (int i = 0; i < merged.size(); i++) {
            WordToken tok = merged.get(i);
            runs.add(new InlineRun(
                    tok.text(),
                    tok.style(),
                    runX,
                    lineY,
                    widths[i],
                    lineHeight,
                    baselineY,
                    tok.linkHref()
            ));
            runX += widths[i];
        }

        return new LineBox(offsetX, lineY, totalWidth, lineHeight, baselineY, runs);
    }

    private float measureWidth(String text, LayoutStyle style, FontRegistry.LoadedFonts fonts) {
        if (fonts != null) {
            try {
                PDFont font = fonts.resolve(style.fontFamily(), style.bold(), style.italic());
                return fonts.measureWidthPx(font, style.fontSizePx(), text);
            } catch (IOException ignored) {
                // Fall back to approximate average glyph width
            }
        }
        return text.length() * style.fontSizePx() * 0.55f;
    }

    private float measureAscent(LayoutStyle style, FontRegistry.LoadedFonts fonts) {
        if (fonts != null) {
            try {
                PDFont font = fonts.resolve(style.fontFamily(), style.bold(), style.italic());
                return fonts.ascentPx(font, style.fontSizePx());
            } catch (IOException ignored) {
                // Fall back
            }
        }
        return style.fontSizePx() * 0.8f;
    }
}
