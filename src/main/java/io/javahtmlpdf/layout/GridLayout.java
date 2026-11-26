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
 * {@code fr}, {@code auto}, {@code repeat()}, {@code minmax()}), {@code grid-column}/{@code grid-row}
 * spans and explicit placement, {@code gap}, and row-height stretching.
 */
final class GridLayout {

    private static final float ROOT_FONT_SIZE_PX = 16f;

    private final LayoutEngine engine;

    GridLayout(LayoutEngine engine) {
        this.engine = engine;
    }

    private static final class PlacedItem {
        final LayoutNode node;
        final int col;      // 0-based
        final int colSpan;
        final int row;      // 0-based
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
            float containingWidth,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle style = container.style();
        GridProps grid = style.grid();
        Edges margin = style.margin();
        Edges padding = style.padding();
        Edges border = style.border();

        float borderBoxWidth = engine.resolveBorderBoxWidth(style, containingWidth, margin, padding, border);
        float marginLeft = margin.left();
        if (style.marginLeftAuto() && style.marginRightAuto()) {
            marginLeft = Math.max(0f, containingWidth - borderBoxWidth) / 2f;
        } else if (style.marginLeftAuto()) {
            marginLeft = Math.max(0f, containingWidth - borderBoxWidth - margin.right());
        }

        float boxX = originX + marginLeft;
        float boxY = originY + margin.top();
        float contentX = boxX + border.left() + padding.left();
        float contentY = boxY + border.top() + padding.top();
        float contentWidth = Math.max(0f, borderBoxWidth - border.horizontal() - padding.horizontal());

        List<GridProps.GridTrack> colTracks = grid.templateColumns().isEmpty()
                ? List.of(GridProps.GridTrack.fr(1f))
                : grid.templateColumns();
        int numCols = colTracks.size();
        float colGap = grid.columnGapPx();
        float rowGap = grid.rowGapPx();

        // 1. Collect visible children & place them into the 2D grid
        List<LayoutNode> visible = new ArrayList<>();
        for (LayoutNode child : container.children()) {
            if (child.style().display() != Display.NONE) {
                visible.add(child);
            }
        }

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
                // Advance auto-placement cursor until we find an unoccupied slot that fits cSpan x rSpan
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

        // 2. Compute column track widths
        float[] colWidths = resolveColumnWidths(colTracks, placed, contentWidth, colGap, fonts);
        float[] colOffsets = new float[numCols];
        float cx = 0f;
        for (int c = 0; c < numCols; c++) {
            colOffsets[c] = cx;
            cx += colWidths[c] + colGap;
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

        // 4. Compute row track heights
        float[] rowHeights = resolveRowHeights(grid.templateRows(), numRows, placed, rowGap);
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
            float contentWidth,
            float colGap,
            FontRegistry.LoadedFonts fonts
    ) {
        int n = tracks.size();
        float[] widths = new float[n];
        float fixedAndAutoSum = 0f;
        float totalFr = 0f;

        for (int c = 0; c < n; c++) {
            GridProps.GridTrack t = tracks.get(c);
            switch (t.kind()) {
                case LENGTH -> {
                    widths[c] = t.length().toPx(ROOT_FONT_SIZE_PX, ROOT_FONT_SIZE_PX, contentWidth);
                    fixedAndAutoSum += widths[c];
                }
                case AUTO -> {
                    float maxIntrinsic = 0f;
                    for (PlacedItem item : placed) {
                        if (item.col == c && item.colSpan == 1) {
                            float w = engine.measureMaxContentWidth(item.node, contentWidth, fonts)
                                    + item.node.style().margin().horizontal();
                            maxIntrinsic = Math.max(maxIntrinsic, w);
                        }
                    }
                    widths[c] = maxIntrinsic;
                    fixedAndAutoSum += widths[c];
                }
                case FR -> totalFr += Math.max(0f, t.value());
                case MINMAX -> {
                    widths[c] = t.minPx();
                    totalFr += Math.max(0f, t.maxFr());
                }
            }
        }

        float totalGaps = n > 1 ? colGap * (n - 1) : 0f;
        float freeWidth = Math.max(0f, contentWidth - fixedAndAutoSum - totalGaps);
        if (totalFr > 0f) {
            float perFr = freeWidth / totalFr;
            for (int c = 0; c < n; c++) {
                GridProps.GridTrack t = tracks.get(c);
                if (t.kind() == GridProps.GridTrack.Kind.FR) {
                    widths[c] = perFr * t.value();
                } else if (t.kind() == GridProps.GridTrack.Kind.MINMAX) {
                    widths[c] = Math.max(t.minPx(), perFr * t.maxFr());
                }
            }
        }
        return widths;
    }

    private float[] resolveRowHeights(
            List<GridProps.GridTrack> rowTracks,
            int numRows,
            List<PlacedItem> placed,
            float rowGap
    ) {
        float[] heights = new float[numRows];
        boolean[] explicit = new boolean[numRows];

        for (int r = 0; r < Math.min(numRows, rowTracks.size()); r++) {
            GridProps.GridTrack t = rowTracks.get(r);
            if (t.kind() == GridProps.GridTrack.Kind.LENGTH) {
                heights[r] = t.length().toPx(ROOT_FONT_SIZE_PX, ROOT_FONT_SIZE_PX, 0f);
                explicit[r] = true;
            }
        }

        // Single-row items first
        for (PlacedItem item : placed) {
            if (item.rowSpan == 1 && !explicit[item.row]) {
                float outerH = item.box.borderBox().height() + item.node.style().margin().vertical();
                heights[item.row] = Math.max(heights[item.row], outerH);
            }
        }

        // Multi-row spanning items expand the last row if needed
        for (PlacedItem item : placed) {
            if (item.rowSpan > 1) {
                float needed = item.box.borderBox().height() + item.node.style().margin().vertical();
                float current = sumRange(heights, item.row, item.rowSpan) + rowGap * (item.rowSpan - 1);
                if (needed > current) {
                    int lastRow = item.row + item.rowSpan - 1;
                    heights[lastRow] += (needed - current);
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
