package io.javahtmlpdf.layout;

import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.FlexProps;
import io.javahtmlpdf.model.GridProps;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java CSS Grid (2D) Formatting Context layout solver.
 * Supports {@code grid-template-columns}, {@code grid-template-rows} ({@code px}, {@code %},
 * {@code fr}, {@code auto}, {@code repeat()}, {@code minmax()}), implicit column/row track expansion,
 * {@code grid-column}/{@code grid-row} spans and explicit placement, {@code gap},
 * cell alignment, and both definite and indefinite ({@code max-content}) container sizing.
 */
final class GridLayout {

    private static final float ROOT_FONT_SIZE_PX = 16f;

    private final LayoutEngine engine;

    GridLayout(LayoutEngine engine) {
        this.engine = engine;
    }

    private static final class PlacedItem {
        final LayoutNode node;
        final int col;
        final int colSpan;
        final int row;
        final int rowSpan;
        LayoutBox box;

        PlacedItem(LayoutNode node, int col, int colSpan, int row, int rowSpan) {
            this.node = node;
            this.col = col;
            this.colSpan = colSpan;
            this.row = row;
            this.rowSpan = rowSpan;
        }
    }

    LayoutBox layout(
            LayoutNode container,
            float originX,
            float originY,
            Float containingWidthObj,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle style = container.style();
        GridProps grid = style.grid();
        Edges margin = style.margin();
        Edges padding = style.padding();
        Edges border = style.border();

        boolean definiteWidth = (style.width() != null && !style.width().isAuto()) || containingWidthObj != null;
        float containingWidth = containingWidthObj != null ? containingWidthObj : 800f;

        Float explicitContentWidth = null;
        float borderBoxWidth = 0f;
        if (definiteWidth) {
            borderBoxWidth = engine.resolveBorderBoxWidth(style, containingWidth, margin, padding, border);
            explicitContentWidth = Math.max(0f, borderBoxWidth - border.horizontal() - padding.horizontal());
        }

        float marginLeft = margin.left();
        if (definiteWidth) {
            if (style.marginLeftAuto() && style.marginRightAuto()) {
                marginLeft = Math.max(0f, containingWidth - borderBoxWidth) / 2f;
            } else if (style.marginLeftAuto()) {
                marginLeft = Math.max(0f, containingWidth - borderBoxWidth - margin.right());
            }
        }

        float boxX = originX + marginLeft;
        float boxY = originY + margin.top();
        float contentX = boxX + border.left() + padding.left();
        float contentY = boxY + border.top() + padding.top();

        Float explicitContentHeight = null;
        if (style.height() != null) {
            float rawH = style.height().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
            float bh = style.borderBox() ? rawH : rawH + padding.vertical() + border.vertical();
            explicitContentHeight = Math.max(0f, bh - border.vertical() - padding.vertical());
        }

        List<GridProps.GridTrack> colTracks = new ArrayList<>(grid.templateColumns());
        if (colTracks.isEmpty()) {
            colTracks.add(definiteWidth ? GridProps.GridTrack.fr(1f) : GridProps.GridTrack.auto());
        }
        float colGap = grid.columnGapPx();
        float rowGap = grid.rowGapPx();

        // 1. Collect visible children & place into 2D grid (growing implicit columns if explicitly placed beyond template)
        List<LayoutNode> visible = new ArrayList<>();
        for (LayoutNode child : container.children()) {
            if (child.style().display() != Display.NONE) {
                visible.add(child);
            }
        }

        for (LayoutNode child : visible) {
            GridProps cg = child.style().grid();
            if (cg.colStart() > 0) {
                int neededCols = (cg.colStart() - 1) + Math.max(1, cg.colSpan());
                while (colTracks.size() < neededCols) {
                    colTracks.add(GridProps.GridTrack.auto());
                }
            }
        }
        int numCols = colTracks.size();

        List<boolean[]> occupied = new ArrayList<>();
        List<PlacedItem> placed = new ArrayList<>();
        int autoRow = 0;
        int autoCol = 0;

        for (LayoutNode child : visible) {
            GridProps cg = child.style().grid();
            int cSpan = Math.min(numCols, Math.max(1, cg.colSpan()));
            int rSpan = Math.max(1, cg.rowSpan());

            int c;
            int r;
            if (cg.colStart() > 0 || cg.rowStart() > 0) {
                c = cg.colStart() > 0 ? Math.min(numCols - cSpan, cg.colStart() - 1) : 0;
                r = cg.rowStart() > 0 ? cg.rowStart() - 1 : findFirstFreeRow(occupied, c, cSpan, rSpan);
            } else {
                while (true) {
                    if (autoCol + cSpan > numCols) {
                        autoCol = 0;
                        autoRow++;
                    }
                    if (isAreaFree(occupied, autoRow, autoCol, rSpan, cSpan)) {
                        c = autoCol;
                        r = autoRow;
                        autoCol += cSpan;
                        break;
                    }
                    autoCol++;
                }
            }

            markOccupied(occupied, r, c, rSpan, cSpan, numCols);
            placed.add(new PlacedItem(child, c, cSpan, r, rSpan));
        }

        int numRows = Math.max(occupied.size(), grid.templateRows().size());

        // 2. Compute column track widths + justify-content track alignment
        float[] colWidths = resolveColumnWidths(colTracks, placed, explicitContentWidth, colGap, fonts);
        float totalColGaps = numCols > 1 ? colGap * (numCols - 1) : 0f;
        float usedColW = sumRange(colWidths, 0, numCols) + totalColGaps;
        float freeColW = explicitContentWidth != null ? (explicitContentWidth - usedColW) : 0f;

        float colStartOffset = 0f;
        float effectiveColGap = colGap;
        switch (style.flex().justifyContent()) {
            case FLEX_END -> colStartOffset = freeColW;
            case CENTER -> colStartOffset = freeColW / 2f;
            case SPACE_BETWEEN -> {
                if (numCols > 1 && freeColW > 0f) effectiveColGap = colGap + (freeColW / (numCols - 1));
            }
            case SPACE_AROUND -> {
                if (numCols > 0 && freeColW > 0f) {
                    float step = freeColW / numCols;
                    colStartOffset = step / 2f;
                    effectiveColGap = colGap + step;
                } else {
                    colStartOffset = freeColW / 2f;
                }
            }
            case SPACE_EVENLY -> {
                if (numCols > 0 && freeColW > 0f) {
                    float step = freeColW / (numCols + 1);
                    colStartOffset = step;
                    effectiveColGap = colGap + step;
                } else {
                    colStartOffset = freeColW / 2f;
                }
            }
            default -> colStartOffset = 0f;
        }

        float[] colOffsets = new float[numCols];
        float cx = colStartOffset;
        for (int c = 0; c < numCols; c++) {
            colOffsets[c] = cx;
            cx += colWidths[c] + effectiveColGap;
        }

        if (!definiteWidth) {
            float totalColW = numCols == 0 ? 0f : sumRange(colWidths, 0, numCols) + colGap * Math.max(0, numCols - 1);
            borderBoxWidth = border.horizontal() + padding.horizontal() + totalColW;
        }

        // 3. Lay out each placed item at its spanned column width (preserving explicit width if set)
        for (PlacedItem item : placed) {
            float cellW = sumRange(colWidths, item.col, item.colSpan) + colGap * (item.colSpan - 1);
            Edges im = item.node.style().margin();
            float itemBorderBoxW = Math.max(0f, cellW - im.horizontal());
            LayoutNode sized = item.node.style().width() == null
                    ? withFixedWidth(item.node, itemBorderBoxW)
                    : item.node;
            item.box = engine.layoutNodeAtOrigin(sized, cellW, fonts);
        }

        // 4. Compute row track heights (including FR row tracks when explicit height is given)
        float[] rowHeights = resolveRowHeights(grid.templateRows(), numRows, placed, explicitContentHeight, rowGap);
        float[] rowOffsets = new float[numRows];
        float ry = 0f;
        for (int r = 0; r < numRows; r++) {
            rowOffsets[r] = ry;
            ry += rowHeights[r] + rowGap;
        }

        // 5. Stretch auto-height items (when align-items is stretch) and apply cell alignment
        FlexProps.AlignItems containerAlign = style.flex().alignItems();
        List<LayoutBox> placedBoxes = new ArrayList<>(placed.size());
        for (PlacedItem item : placed) {
            float cellW = sumRange(colWidths, item.col, item.colSpan) + colGap * (item.colSpan - 1);
            float cellH = sumRange(rowHeights, item.row, item.rowSpan) + rowGap * (item.rowSpan - 1);
            LayoutStyle s = item.node.style();
            Edges im = s.margin();

            FlexProps.AlignItems itemAlign = switch (s.flex().alignSelf()) {
                case STRETCH -> FlexProps.AlignItems.STRETCH;
                case FLEX_START -> FlexProps.AlignItems.FLEX_START;
                case FLEX_END -> FlexProps.AlignItems.FLEX_END;
                case CENTER -> FlexProps.AlignItems.CENTER;
                case AUTO -> containerAlign;
            };

            LayoutBox finalBox = item.box;
            if (itemAlign == FlexProps.AlignItems.STRETCH && s.height() == null && !item.node.isImageNode()) {
                float stretchedH = Math.max(0f, cellH - im.vertical());
                if (Math.abs(stretchedH - finalBox.borderBox().height()) > 0.05f) {
                    float itemBorderBoxW = s.width() == null ? Math.max(0f, cellW - im.horizontal()) : finalBox.borderBox().width();
                    LayoutNode stretched = withFixedDimensions(item.node, itemBorderBoxW, stretchedH);
                    finalBox = engine.layoutNodeAtOrigin(stretched, cellW, fonts);
                }
            }

            float outerH = finalBox.borderBox().height() + im.vertical();
            float alignOffsetY = switch (itemAlign) {
                case CENTER -> (cellH - outerH) / 2f;
                case FLEX_END -> cellH - outerH;
                default -> 0f;
            };

            float targetX = contentX + colOffsets[item.col] + im.left();
            float targetY = contentY + rowOffsets[item.row] + im.top() + alignOffsetY;
            float dx = targetX - finalBox.borderBox().x();
            float dy = targetY - finalBox.borderBox().y();
            placedBoxes.add(LayoutEngine.translateBox(finalBox, dx, dy));
        }

        float totalRowsHeight = numRows == 0
                ? 0f
                : sumRange(rowHeights, 0, numRows) + rowGap * Math.max(0, numRows - 1);
        float borderBoxHeight = engine.resolveBorderBoxHeight(style, totalRowsHeight, padding, border);

        return new LayoutBox(
                container.id(),
                style,
                new Rect(boxX, boxY, borderBoxWidth, borderBoxHeight),
                List.of(),
                null,
                null,
                placedBoxes
        );
    }

    private float[] resolveColumnWidths(
            List<GridProps.GridTrack> tracks,
            List<PlacedItem> placed,
            Float explicitContentWidth,
            float colGap,
            FontRegistry.LoadedFonts fonts
    ) {
        int n = tracks.size();
        float[] widths = new float[n];
        float pctBase = explicitContentWidth != null ? explicitContentWidth : 0f;
        float fixedAndAutoSum = 0f;
        float totalFr = 0f;
        int autoCount = 0;
        float maxFrRatio = 0f;

        for (int c = 0; c < n; c++) {
            GridProps.GridTrack t = tracks.get(c);
            switch (t.kind()) {
                case LENGTH -> {
                    widths[c] = t.length().toPx(ROOT_FONT_SIZE_PX, ROOT_FONT_SIZE_PX, pctBase);
                    fixedAndAutoSum += widths[c];
                }
                case AUTO -> {
                    widths[c] = measureTrackIntrinsicWidth(c, placed, pctBase > 0 ? pctBase : 800f, fonts);
                    fixedAndAutoSum += widths[c];
                    autoCount++;
                }
                case FR -> {
                    // Bare Nfr == minmax(auto, Nfr): explicit fixed-width items establish the auto floor
                    float explicitFloor = measureTrackExplicitMinWidth(c, placed, pctBase);
                    widths[c] = explicitFloor;
                    if (t.value() > 0f) {
                        totalFr += t.value();
                        if (explicitContentWidth == null) {
                            float intrinsic = measureTrackIntrinsicWidth(c, placed, 800f, fonts);
                            widths[c] = Math.max(explicitFloor, intrinsic);
                            maxFrRatio = Math.max(maxFrRatio, widths[c] / t.value());
                        }
                    } else {
                        fixedAndAutoSum += explicitFloor;
                    }
                }
                case MINMAX -> {
                    widths[c] = t.minPx();
                    if (t.maxFr() > 0f) {
                        totalFr += t.maxFr();
                    } else {
                        fixedAndAutoSum += t.minPx();
                    }
                }
            }
        }

        // CSS Grid §11.5: accommodate spanning items (colSpan > 1) across intrinsic-min tracks (AUTO / bare FR)
        for (PlacedItem item : placed) {
            if (item.colSpan > 1) {
                float needed = explicitItemMinWidth(item, pctBase);
                if (needed <= 0f) continue;
                float current = sumRange(widths, item.col, item.colSpan) + colGap * (item.colSpan - 1);
                if (needed > current) {
                    List<Integer> flexEligible = new ArrayList<>();
                    List<Integer> nonFlexEligible = new ArrayList<>();
                    float sumFlex = 0f;
                    for (int c = item.col; c < Math.min(n, item.col + item.colSpan); c++) {
                        GridProps.GridTrack tr = tracks.get(c);
                        if (tr.kind() == GridProps.GridTrack.Kind.FR && tr.value() > 0f) {
                            flexEligible.add(c);
                            sumFlex += tr.value();
                        } else if (tr.kind() == GridProps.GridTrack.Kind.AUTO || tr.kind() == GridProps.GridTrack.Kind.FR) {
                            nonFlexEligible.add(c);
                        }
                    }
                    float delta = needed - current;
                    if (!flexEligible.isEmpty() && sumFlex > 0f) {
                        for (int c : flexEligible) {
                            widths[c] += delta * (tracks.get(c).value() / sumFlex);
                        }
                    } else if (!nonFlexEligible.isEmpty()) {
                        float extra = delta / nonFlexEligible.size();
                        for (int c : nonFlexEligible) {
                            widths[c] += extra;
                            fixedAndAutoSum += extra;
                        }
                    }
                }
            }
        }

        if (explicitContentWidth != null) {
            float totalGaps = n > 1 ? colGap * (n - 1) : 0f;
            float freeWidth = Math.max(0f, explicitContentWidth - fixedAndAutoSum - totalGaps);
            if (totalFr > 0f) {
                // CSS Grid §11.7.1: denominator is max(1, sum of flex factors)
                float perFr = freeWidth / Math.max(1f, totalFr);
                for (int c = 0; c < n; c++) {
                    GridProps.GridTrack t = tracks.get(c);
                    if (t.kind() == GridProps.GridTrack.Kind.FR && t.value() > 0f) {
                        widths[c] = Math.max(widths[c], perFr * t.value());
                    } else if (t.kind() == GridProps.GridTrack.Kind.MINMAX && t.maxFr() > 0f) {
                        widths[c] = Math.max(t.minPx(), perFr * t.maxFr());
                    }
                }
            } else if (autoCount > 0 && freeWidth > 0.01f) {
                // CSS Grid §11.8: stretch auto tracks equally to fill remaining definite width
                float extraPerAuto = freeWidth / autoCount;
                for (int c = 0; c < n; c++) {
                    if (tracks.get(c).kind() == GridProps.GridTrack.Kind.AUTO) {
                        widths[c] += extraPerAuto;
                    }
                }
            }
        } else if (totalFr > 0f && maxFrRatio > 0f) {
            for (int c = 0; c < n; c++) {
                GridProps.GridTrack t = tracks.get(c);
                if (t.kind() == GridProps.GridTrack.Kind.FR && t.value() > 0f) {
                    widths[c] = Math.max(widths[c], maxFrRatio * t.value());
                }
            }
        }
        return widths;
    }

    private float measureTrackExplicitMinWidth(int col, List<PlacedItem> placed, float pctBase) {
        float maxMin = 0f;
        for (PlacedItem item : placed) {
            if (item.col == col && item.colSpan == 1) {
                maxMin = Math.max(maxMin, explicitItemMinWidth(item, pctBase));
            }
        }
        return maxMin;
    }

    private float explicitItemMinWidth(PlacedItem item, float pctBase) {
        LayoutStyle s = item.node.style();
        if (s.width() != null && !s.width().isAuto() && !s.width().isPercent()) {
            float raw = s.width().toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
            float bw = s.borderBox() ? raw : raw + s.padding().horizontal() + s.border().horizontal();
            return bw + s.margin().horizontal();
        }
        if (s.minWidth() != null && !s.minWidth().isAuto() && !s.minWidth().isPercent()) {
            float raw = s.minWidth().toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
            float bw = s.borderBox() ? raw : raw + s.padding().horizontal() + s.border().horizontal();
            return bw + s.margin().horizontal();
        }
        return 0f;
    }

    private float measureTrackIntrinsicWidth(int col, List<PlacedItem> placed, float maxAvail, FontRegistry.LoadedFonts fonts) {
        float maxIntrinsic = 0f;
        for (PlacedItem item : placed) {
            if (item.col == col && item.colSpan == 1) {
                float w = engine.measureMaxContentWidth(item.node, maxAvail, fonts)
                        + item.node.style().margin().horizontal();
                maxIntrinsic = Math.max(maxIntrinsic, w);
            }
        }
        return maxIntrinsic;
    }

    private float[] resolveRowHeights(
            List<GridProps.GridTrack> rowTracks,
            int numRows,
            List<PlacedItem> placed,
            Float explicitContentHeight,
            float rowGap
    ) {
        float[] heights = new float[numRows];
        boolean[] explicit = new boolean[numRows];
        float fixedSum = 0f;
        float totalFr = 0f;
        float pctBase = explicitContentHeight != null ? explicitContentHeight : 0f;

        for (int r = 0; r < Math.min(numRows, rowTracks.size()); r++) {
            GridProps.GridTrack t = rowTracks.get(r);
            if (t.kind() == GridProps.GridTrack.Kind.LENGTH) {
                heights[r] = t.length().toPx(ROOT_FONT_SIZE_PX, ROOT_FONT_SIZE_PX, pctBase);
                explicit[r] = true;
                fixedSum += heights[r];
            } else if (t.kind() == GridProps.GridTrack.Kind.FR) {
                if (t.value() > 0f) totalFr += t.value();
            } else if (t.kind() == GridProps.GridTrack.Kind.MINMAX) {
                heights[r] = t.minPx();
                explicit[r] = true; // minmax(minPx, maxFr) does NOT take auto item height as floor
                if (t.maxFr() > 0f) totalFr += t.maxFr();
            }
        }

        // Single-row items first (for auto and bare FR rows, not fixed or minmax(0, ...))
        for (PlacedItem item : placed) {
            if (item.rowSpan == 1 && !explicit[item.row]) {
                float outerH = item.box.borderBox().height() + item.node.style().margin().vertical();
                heights[item.row] = Math.max(heights[item.row], outerH);
            }
        }

        // Multi-row spanning items distribute needed height across eligible intrinsic-min rows (weighted by FR when present)
        for (PlacedItem item : placed) {
            if (item.rowSpan > 1) {
                float needed = item.box.borderBox().height() + item.node.style().margin().vertical();
                float current = sumRange(heights, item.row, item.rowSpan) + rowGap * (item.rowSpan - 1);
                if (needed > current) {
                    List<Integer> flexEligible = new ArrayList<>();
                    List<Integer> nonFlexEligible = new ArrayList<>();
                    float sumFlex = 0f;
                    for (int r = item.row; r < Math.min(numRows, item.row + item.rowSpan); r++) {
                        if (explicit[r]) continue;
                        if (r < rowTracks.size()
                                && rowTracks.get(r).kind() == GridProps.GridTrack.Kind.FR
                                && rowTracks.get(r).value() > 0f) {
                            flexEligible.add(r);
                            sumFlex += rowTracks.get(r).value();
                        } else {
                            nonFlexEligible.add(r);
                        }
                    }
                    float delta = needed - current;
                    if (!flexEligible.isEmpty() && sumFlex > 0f) {
                        for (int r : flexEligible) {
                            heights[r] += delta * (rowTracks.get(r).value() / sumFlex);
                        }
                    } else if (!nonFlexEligible.isEmpty()) {
                        float extra = delta / nonFlexEligible.size();
                        for (int r : nonFlexEligible) heights[r] += extra;
                    }
                }
            }
        }

        // Distribute remaining height to FR / MINMAX row tracks, or stretch auto rows when definite height is given
        if (explicitContentHeight != null) {
            float totalGaps = numRows > 1 ? rowGap * (numRows - 1) : 0f;
            if (totalFr > 0f) {
                float nonFrSum = 0f;
                for (int r = 0; r < numRows; r++) {
                    boolean isFlexRow = r < rowTracks.size()
                            && ((rowTracks.get(r).kind() == GridProps.GridTrack.Kind.FR && rowTracks.get(r).value() > 0f)
                                || (rowTracks.get(r).kind() == GridProps.GridTrack.Kind.MINMAX && rowTracks.get(r).maxFr() > 0f));
                    if (!isFlexRow) nonFrSum += heights[r];
                }
                float freeH = Math.max(0f, explicitContentHeight - nonFrSum - totalGaps);
                float perFr = freeH / Math.max(1f, totalFr);
                for (int r = 0; r < Math.min(numRows, rowTracks.size()); r++) {
                    GridProps.GridTrack t = rowTracks.get(r);
                    if (t.kind() == GridProps.GridTrack.Kind.FR && t.value() > 0f) {
                        heights[r] = Math.max(heights[r], perFr * t.value());
                    } else if (t.kind() == GridProps.GridTrack.Kind.MINMAX && t.maxFr() > 0f) {
                        heights[r] = Math.max(t.minPx(), perFr * t.maxFr());
                    }
                }
            } else {
                int autoRows = 0;
                float sumH = 0f;
                for (int r = 0; r < numRows; r++) {
                    sumH += heights[r];
                    if (!explicit[r]) autoRows++;
                }
                float freeH = Math.max(0f, explicitContentHeight - sumH - totalGaps);
                if (autoRows > 0 && freeH > 0.01f) {
                    float extra = freeH / autoRows;
                    for (int r = 0; r < numRows; r++) {
                        if (!explicit[r]) heights[r] += extra;
                    }
                }
            }
        }
        return heights;
    }

    private static boolean isAreaFree(List<boolean[]> occupied, int r, int c, int rSpan, int cSpan) {
        for (int dr = 0; dr < rSpan; dr++) {
            if (r + dr >= occupied.size()) continue;
            boolean[] row = occupied.get(r + dr);
            for (int dc = 0; dc < cSpan; dc++) {
                if (c + dc < row.length && row[c + dc]) return false;
            }
        }
        return true;
    }

    private static int findFirstFreeRow(List<boolean[]> occupied, int c, int cSpan, int rSpan) {
        int r = 0;
        while (!isAreaFree(occupied, r, c, rSpan, cSpan)) {
            r++;
        }
        return r;
    }

    private static void markOccupied(List<boolean[]> occupied, int r, int c, int rSpan, int cSpan, int numCols) {
        while (occupied.size() < r + rSpan) {
            occupied.add(new boolean[numCols]);
        }
        for (int dr = 0; dr < rSpan; dr++) {
            boolean[] row = occupied.get(r + dr);
            for (int dc = 0; dc < cSpan; dc++) {
                if (c + dc < numCols) row[c + dc] = true;
            }
        }
    }

    private static float sumRange(float[] arr, int start, int count) {
        float sum = 0f;
        for (int i = start; i < Math.min(arr.length, start + count); i++) {
            sum += arr[i];
        }
        return sum;
    }

    private static LayoutNode withFixedWidth(LayoutNode node, float borderBoxW) {
        LayoutStyle s = node.style();
        float w = s.borderBox() ? borderBoxW : Math.max(0f, borderBoxW - s.padding().horizontal() - s.border().horizontal());
        return new LayoutNode(
                node.id(),
                s.withDimensions(new CssLength(w, CssLength.Unit.PX), s.height()),
                node.text(), node.imageBytes(), node.linkHref(), node.children()
        );
    }

    private static LayoutNode withFixedDimensions(LayoutNode node, float borderBoxW, float borderBoxH) {
        LayoutStyle s = node.style();
        float w = s.borderBox() ? borderBoxW : Math.max(0f, borderBoxW - s.padding().horizontal() - s.border().horizontal());
        float h = s.borderBox() ? borderBoxH : Math.max(0f, borderBoxH - s.padding().vertical() - s.border().vertical());
        return new LayoutNode(
                node.id(),
                s.withDimensions(new CssLength(w, CssLength.Unit.PX), new CssLength(h, CssLength.Unit.PX)),
                node.text(), node.imageBytes(), node.linkHref(), node.children()
        );
    }
}
