package io.javahtmlpdf.layout;

import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.Rect;
import io.javahtmlpdf.model.TableRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java HTML Table Formatting Context layout solver.
 * Supports {@code <table>}, {@code <thead>}, {@code <tbody>}, {@code <tfoot>},
 * {@code <tr>}, {@code <th>}, {@code <td>}, {@code colspan}, explicit &amp; auto column widths,
 * and uniform row heights. Marks {@code <thead>} rows with {@link TableRole#THEAD} so the
 * Paginator can repeat table headers automatically across page breaks.
 */
final class TableLayout {

    private static final float ROOT_FONT_SIZE_PX = 16f;

    private final LayoutEngine engine;

    TableLayout(LayoutEngine engine) {
        this.engine = engine;
    }

    private record RowSpec(LayoutNode rowNode, boolean isHeader, List<LayoutNode> cells) {}

    LayoutBox layout(
            LayoutNode tableNode,
            float originX,
            float originY,
            float containingWidth,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle style = tableNode.style();
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
        float tableContentWidth = Math.max(0f, borderBoxWidth - border.horizontal() - padding.horizontal());

        // 1. Flatten thead / tbody / tfoot / direct tr into ordered rows
        List<RowSpec> rows = new ArrayList<>();
        collectRows(tableNode, false, rows);

        int numCols = 0;
        for (RowSpec row : rows) {
            int colCount = 0;
            for (LayoutNode cell : row.cells()) {
                colCount += cell.style().colSpan();
            }
            numCols = Math.max(numCols, colCount);
        }

        if (rows.isEmpty() || numCols == 0) {
            float h = engine.resolveBorderBoxHeight(style, 0f, padding, border);
            return new LayoutBox(tableNode.id(), style, new Rect(boxX, boxY, borderBoxWidth, h), List.of(), null, null, List.of());
        }

        // 2. Resolve column widths (explicit px/% first, then intrinsic max-content scaled to fill tableContentWidth)
        float[] colWidths = resolveColumnWidths(rows, numCols, tableContentWidth, fonts);
        float[] colOffsets = new float[numCols];
        float cx = 0f;
        for (int c = 0; c < numCols; c++) {
            colOffsets[c] = cx;
            cx += colWidths[c];
        }

        // 3. Lay out each row and stretch every cell to the row's max height
        List<LayoutBox> rowBoxes = new ArrayList<>(rows.size());
        float cursorY = contentY;

        for (RowSpec row : rows) {
            List<LayoutBox> measuredCells = new ArrayList<>(row.cells().size());
            int[] cellCols = new int[row.cells().size()];
            float[] cellWidths = new float[row.cells().size()];
            float rowHeight = 0f;

            int colIdx = 0;
            for (int i = 0; i < row.cells().size(); i++) {
                LayoutNode cell = row.cells().get(i);
                int span = Math.min(numCols - colIdx, cell.style().colSpan());
                if (span <= 0) break;
                float cellW = sumRange(colWidths, colIdx, span);
                cellCols[i] = colIdx;
                cellWidths[i] = cellW;

                LayoutNode sizedCell = withFixedWidth(cell, cellW);
                LayoutBox measured = engine.layoutNodeAtOrigin(sizedCell, cellW, fonts);
                measuredCells.add(measured);
                rowHeight = Math.max(rowHeight, measured.borderBox().height());
                colIdx += span;
            }

            // Stretch all cells in this row to uniform rowHeight and position them
            List<LayoutBox> finalCells = new ArrayList<>(measuredCells.size());
            for (int i = 0; i < measuredCells.size(); i++) {
                LayoutNode cell = row.cells().get(i);
                LayoutBox box = measuredCells.get(i);
                if (Math.abs(box.borderBox().height() - rowHeight) > 0.05f) {
                    LayoutNode stretched = withFixedDimensions(cell, cellWidths[i], rowHeight);
                    box = engine.layoutNodeAtOrigin(stretched, cellWidths[i], fonts);
                }
                float targetX = contentX + colOffsets[cellCols[i]];
                float targetY = cursorY;
                finalCells.add(LayoutEngine.translateBox(box, targetX - box.borderBox().x(), targetY - box.borderBox().y()));
            }

            LayoutStyle rowStyle = row.rowNode().style();
            if (row.isHeader()) {
                rowStyle = rowStyle.withTableRole(TableRole.THEAD, 1);
            } else {
                rowStyle = rowStyle.withTableRole(TableRole.TR, 1);
            }
            LayoutBox rowBox = new LayoutBox(
                    row.rowNode().id(),
                    rowStyle,
                    new Rect(contentX, cursorY, tableContentWidth, rowHeight),
                    List.of(),
                    null,
                    null,
                    finalCells
            );
            rowBoxes.add(rowBox);
            cursorY += rowHeight;
        }

        float contentHeight = cursorY - contentY;
        float borderBoxHeight = engine.resolveBorderBoxHeight(style, contentHeight, padding, border);
        LayoutStyle tableStyle = style.withTableRole(TableRole.TABLE, 1);
        return new LayoutBox(
                tableNode.id(),
                tableStyle,
                new Rect(boxX, boxY, borderBoxWidth, borderBoxHeight),
                List.of(),
                null,
                null,
                rowBoxes
        );
    }

    private void collectRows(LayoutNode node, boolean inThead, List<RowSpec> out) {
        for (LayoutNode child : node.children()) {
            if (child.style().display() == Display.NONE) continue;
            TableRole role = child.style().tableRole();
            if (role == TableRole.THEAD) {
                collectRows(child, true, out);
            } else if (role == TableRole.TBODY || role == TableRole.TFOOT) {
                collectRows(child, false, out);
            } else if (role == TableRole.TR) {
                List<LayoutNode> cells = new ArrayList<>();
                for (LayoutNode cell : child.children()) {
                    if (cell.style().display() != Display.NONE
                            && (cell.style().tableRole() == TableRole.TH || cell.style().tableRole() == TableRole.TD)) {
                        cells.add(cell);
                    }
                }
                if (!cells.isEmpty()) {
                    out.add(new RowSpec(child, inThead, cells));
                }
            }
        }
    }

    private float[] resolveColumnWidths(
            List<RowSpec> rows,
            int numCols,
            float tableContentWidth,
            FontRegistry.LoadedFonts fonts
    ) {
        float[] explicit = new float[numCols];
        boolean[] hasExplicit = new boolean[numCols];
        float[] intrinsic = new float[numCols];

        for (RowSpec row : rows) {
            int col = 0;
            for (LayoutNode cell : row.cells()) {
                int span = cell.style().colSpan();
                if (span == 1 && col < numCols) {
                    CssLength w = cell.style().width();
                    if (w != null && !w.isAuto()) {
                        float px = w.toPx(cell.style().fontSizePx(), ROOT_FONT_SIZE_PX, tableContentWidth);
                        explicit[col] = Math.max(explicit[col], px);
                        hasExplicit[col] = true;
                    } else {
                        float mc = engine.measureMaxContentWidth(cell, tableContentWidth, fonts);
                        intrinsic[col] = Math.max(intrinsic[col], mc);
                    }
                }
                col += span;
            }
        }

        float[] result = new float[numCols];
        float fixedSum = 0f;
        float autoSum = 0f;
        int autoCount = 0;
        for (int c = 0; c < numCols; c++) {
            if (hasExplicit[c]) {
                result[c] = explicit[c];
                fixedSum += explicit[c];
            } else {
                autoCount++;
                autoSum += Math.max(20f, intrinsic[c]);
            }
        }

        float remaining = Math.max(0f, tableContentWidth - fixedSum);
        if (autoCount > 0) {
            for (int c = 0; c < numCols; c++) {
                if (!hasExplicit[c]) {
                    float weight = Math.max(20f, intrinsic[c]);
                    result[c] = autoSum > 0f ? remaining * (weight / autoSum) : (remaining / autoCount);
                }
            }
        } else if (fixedSum > 0f && Math.abs(fixedSum - tableContentWidth) > 0.5f) {
            // Normalize explicit widths if they exceed table width
            if (fixedSum > tableContentWidth) {
                float scale = tableContentWidth / fixedSum;
                for (int c = 0; c < numCols; c++) result[c] *= scale;
            }
        }
        return result;
    }

    private static float sumRange(float[] arr, int start, int count) {
        float sum = 0f;
        for (int i = start; i < Math.min(arr.length, start + count); i++) sum += arr[i];
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
