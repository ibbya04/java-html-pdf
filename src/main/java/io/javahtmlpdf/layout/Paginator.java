package io.javahtmlpdf.layout;

import io.javahtmlpdf.PageSize;
import io.javahtmlpdf.model.BreakProps;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.InlineRun;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.LineBox;
import io.javahtmlpdf.model.MarginBoxSpec;
import io.javahtmlpdf.model.PageSheet;
import io.javahtmlpdf.model.Rect;
import io.javahtmlpdf.model.TableRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Slices a laid-out {@link LayoutBox} tree into fixed-size {@link PageSheet}s.
 *
 * <p>Handles explicit {@code break-before: page}, {@code break-inside: avoid},
 * {@code break-after: avoid} (keeping headings with following content), paragraph
 * line-level splitting (respecting {@code orphans} and {@code widows}), and multi-page
 * {@code <table>} fragmentation with automatic repeating {@code <thead>} rows.
 */
public final class Paginator {

    private static final float EPS = 0.5f;

    private static final class PageBuilder {
        final List<LayoutBox> boxes = new ArrayList<>();
        float cursorY;

        PageBuilder(float startY) {
            this.cursorY = startY;
        }

        boolean isEmpty() {
            return boxes.isEmpty();
        }
    }

    public List<PageSheet> paginate(
            LayoutBox root,
            PageSize pageSize,
            Edges pageMarginsPx,
            List<MarginBoxSpec> marginBoxes
    ) {
        float topY = pageMarginsPx.top();
        float bottomY = pageSize.heightPx() - pageMarginsPx.bottom();
        float pageContentHeight = Math.max(40f, bottomY - topY);

        // Fast path: entire document fits on Page 1 and has no forced page breaks
        if (root.borderBox().bottom() <= bottomY + EPS && !containsForcedBreak(root)) {
            return List.of(new PageSheet(1, 1, pageSize, pageMarginsPx, List.of(root), marginBoxes));
        }

        List<PageBuilder> pages = new ArrayList<>();
        pages.add(new PageBuilder(topY));

        // If root (body) has a background color, preserve it per page or flow its children
        List<LayoutBox> flowItems = root.children().isEmpty() ? List.of(root) : root.children();
        flowChildren(flowItems, pages, topY, bottomY, pageContentHeight);

        int total = pages.size();
        List<PageSheet> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            result.add(new PageSheet(i + 1, total, pageSize, pageMarginsPx, pages.get(i).boxes, marginBoxes));
        }
        return result;
    }

    private void flowChildren(
            List<LayoutBox> items,
            List<PageBuilder> pages,
            float topY,
            float bottomY,
            float pageContentHeight
    ) {
        for (int i = 0; i < items.size(); i++) {
            LayoutBox box = items.get(i);
            LayoutBox nextBox = (i + 1 < items.size()) ? items.get(i + 1) : null;
            float gapBefore = (i == 0) ? 0f : Math.max(0f, box.borderBox().y() - items.get(i - 1).borderBox().bottom());

            PageBuilder page = pages.getLast();

            // 1. Explicit break-before: page
            if (box.style().breaks().breakBeforePage() && !page.isEmpty()) {
                page = startNewPage(pages, topY);
                gapBefore = 0f;
            }

            float targetY = page.isEmpty() ? topY : page.cursorY + gapBefore;
            float boxHeight = box.borderBox().height();

            // 2. Keep-with-next (break-after: avoid, e.g. headings)
            if (box.style().breaks().breakAfterAvoid() && nextBox != null && !page.isEmpty()) {
                float nextGap = Math.max(0f, nextBox.borderBox().y() - box.borderBox().bottom());
                float minNextHeight = Math.min(nextBox.borderBox().height(), 60f);
                if (targetY + boxHeight + nextGap + minNextHeight > bottomY + EPS) {
                    page = startNewPage(pages, topY);
                    targetY = topY;
                }
            }

            // 3. Entire box fits on current page
            if (targetY + boxHeight <= bottomY + EPS) {
                placeBoxAt(page, box, targetY);
                continue;
            }

            // 4. Table fragmentation with repeating <thead>
            if (box.style().tableRole() == TableRole.TABLE && !box.children().isEmpty()) {
                paginateTable(box, pages, targetY, topY, bottomY);
                continue;
            }

            // 5. Atomic box (break-inside: avoid, image, or fits cleanly on a fresh page)
            BreakProps breaks = box.style().breaks();
            boolean atomic = breaks.breakInsideAvoid() || box.imageBytes() != null
                    || (box.lines().isEmpty() && box.children().isEmpty());
            if (atomic || (boxHeight <= pageContentHeight + EPS && box.lines().isEmpty())) {
                if (!page.isEmpty()) {
                    page = startNewPage(pages, topY);
                    targetY = topY;
                }
                placeBoxAt(page, box, targetY);
                continue;
            }

            // 6. Paragraph / multi-line text splitting (never slice a line in half!)
            if (!box.lines().isEmpty() && box.children().isEmpty()) {
                paginateTextBox(box, pages, targetY, topY, bottomY);
                continue;
            }

            // 7. Tall container block: flow its children across pages
            if (!box.children().isEmpty()) {
                flowChildren(box.children(), pages, topY, bottomY, pageContentHeight);
                continue;
            }

            // Fallback: place on fresh page
            if (!page.isEmpty()) {
                page = startNewPage(pages, topY);
                targetY = topY;
            }
            placeBoxAt(page, box, targetY);
        }
    }

    private void paginateTable(
            LayoutBox table,
            List<PageBuilder> pages,
            float startTargetY,
            float topY,
            float bottomY
    ) {
        List<LayoutBox> headerRows = new ArrayList<>();
        List<LayoutBox> bodyRows = new ArrayList<>();
        for (LayoutBox row : table.children()) {
            if (row.style().tableRole() == TableRole.THEAD) {
                headerRows.add(row);
            } else {
                bodyRows.add(row);
            }
        }

        float headerHeight = 0f;
        for (LayoutBox hr : headerRows) headerHeight += hr.borderBox().height();

        PageBuilder page = pages.getLast();
        float cursorY = startTargetY;

        // If header + first body row don't fit on current non-empty page, start fresh page
        float firstBodyH = bodyRows.isEmpty() ? 0f : bodyRows.getFirst().borderBox().height();
        if (!page.isEmpty() && cursorY + headerHeight + firstBodyH > bottomY + EPS) {
            page = startNewPage(pages, topY);
            cursorY = topY;
        }

        // Place initial header rows
        for (LayoutBox hr : headerRows) {
            placeBoxAt(page, hr, cursorY);
            cursorY = page.cursorY;
        }

        // Place body rows, repeating headerRows at the top of every continuation page
        for (LayoutBox row : bodyRows) {
            float rowH = row.borderBox().height();
            if (!page.isEmpty() && cursorY + rowH > bottomY + EPS) {
                page = startNewPage(pages, topY);
                cursorY = topY;
                for (LayoutBox hr : headerRows) {
                    placeBoxAt(page, hr, cursorY);
                    cursorY = page.cursorY;
                }
            }
            placeBoxAt(page, row, cursorY);
            cursorY = page.cursorY;
        }
    }

    private void paginateTextBox(
            LayoutBox box,
            List<PageBuilder> pages,
            float startTargetY,
            float topY,
            float bottomY
    ) {
        List<LineBox> allLines = box.lines();
        int orphans = box.style().breaks().orphans();
        int widows = box.style().breaks().widows();

        PageBuilder page = pages.getLast();
        float cursorY = startTargetY;
        int idx = 0;

        while (idx < allLines.size()) {
            float topPad = (idx == 0) ? (box.style().border().top() + box.style().padding().top()) : 0f;
            float avail = bottomY - cursorY - topPad;

            int fitCount = 0;
            float usedH = 0f;
            while (idx + fitCount < allLines.size()) {
                float lh = allLines.get(idx + fitCount).height();
                if (fitCount > 0 && usedH + lh > avail + EPS) break;
                if (fitCount == 0 && usedH + lh > avail + EPS && !page.isEmpty()) break;
                usedH += lh;
                fitCount++;
            }

            int remainingAfter = allLines.size() - (idx + fitCount);

            // Enforce orphans/widows when splitting
            if (fitCount == 0 || (remainingAfter > 0 && fitCount < orphans && !page.isEmpty())) {
                page = startNewPage(pages, topY);
                cursorY = topY;
                continue;
            }
            if (remainingAfter > 0 && remainingAfter < widows && fitCount > orphans) {
                int shift = widows - remainingAfter;
                fitCount = Math.max(orphans, fitCount - shift);
            }

            List<LineBox> slice = allLines.subList(idx, idx + fitCount);
            float sliceStartLineY = slice.getFirst().y();
            float sliceHeight = 0f;
            for (LineBox lb : slice) sliceHeight += lb.height();

            float bottomPad = (idx + fitCount == allLines.size())
                    ? (box.style().border().bottom() + box.style().padding().bottom()) : 0f;
            float dy = (cursorY + topPad) - sliceStartLineY;

            List<LineBox> movedLines = new ArrayList<>(slice.size());
            for (LineBox lb : slice) {
                List<InlineRun> movedRuns = new ArrayList<>(lb.runs().size());
                for (InlineRun r : lb.runs()) {
                    movedRuns.add(new InlineRun(
                            r.text(), r.style(), r.x(), r.y() + dy,
                            r.width(), r.height(), r.baselineY() + dy, r.linkHref()
                    ));
                }
                movedLines.add(new LineBox(lb.x(), lb.y() + dy, lb.width(), lb.height(), lb.baselineY() + dy, movedRuns));
            }

            Rect fragRect = new Rect(box.borderBox().x(), cursorY, box.borderBox().width(), topPad + sliceHeight + bottomPad);
            LayoutBox fragment = new LayoutBox(
                    box.id(), box.style(), fragRect, movedLines, null,
                    idx == 0 ? box.listMarker() : null, List.of()
            );
            page.boxes.add(fragment);
            page.cursorY = fragRect.bottom();

            idx += fitCount;
            if (idx < allLines.size()) {
                page = startNewPage(pages, topY);
                cursorY = topY;
            }
        }
    }

    private static void placeBoxAt(PageBuilder page, LayoutBox box, float targetY) {
        float dy = targetY - box.borderBox().y();
        LayoutBox moved = LayoutEngine.translateBox(box, 0f, dy);
        page.boxes.add(moved);
        page.cursorY = moved.borderBox().bottom();
    }

    private static PageBuilder startNewPage(List<PageBuilder> pages, float topY) {
        PageBuilder next = new PageBuilder(topY);
        pages.add(next);
        return next;
    }

    private static boolean containsForcedBreak(LayoutBox box) {
        if (box.style().breaks().breakBeforePage()) return true;
        for (LayoutBox child : box.children()) {
            if (containsForcedBreak(child)) return true;
        }
        return false;
    }
}
