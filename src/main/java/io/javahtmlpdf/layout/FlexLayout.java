package io.javahtmlpdf.layout;

import io.javahtmlpdf.css.CssLength;
import io.javahtmlpdf.font.FontRegistry;
import io.javahtmlpdf.model.Display;
import io.javahtmlpdf.model.Edges;
import io.javahtmlpdf.model.FlexProps;
import io.javahtmlpdf.model.LayoutBox;
import io.javahtmlpdf.model.LayoutNode;
import io.javahtmlpdf.model.LayoutStyle;
import io.javahtmlpdf.model.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java CSS Flexbox (1D) Formatting Context layout solver.
 * Supports {@code flex-direction}, {@code flex-wrap}, {@code justify-content},
 * {@code align-items}, {@code align-self}, {@code flex-grow}, {@code flex-shrink},
 * {@code flex-basis}, and {@code gap}.
 */
final class FlexLayout {

    private static final float ROOT_FONT_SIZE_PX = 16f;

    private final LayoutEngine engine;

    FlexLayout(LayoutEngine engine) {
        this.engine = engine;
    }

    private static final class FlexItem {
        final LayoutNode node;
        final LayoutStyle style;
        final float mainMarginStart;
        final float mainMarginEnd;
        final float crossMarginStart;
        final float crossMarginEnd;
        float baseMainSize;       // hypothetical border-box main size
        float targetMainSize;     // resolved border-box main size after grow/shrink
        float minMainSize;
        LayoutBox laidOutBox;

        FlexItem(LayoutNode node, boolean isRow, float baseMainSize, float minMainSize) {
            this.node = node;
            this.style = node.style();
            Edges m = style.margin();
            this.mainMarginStart = isRow ? m.left() : m.top();
            this.mainMarginEnd = isRow ? m.right() : m.bottom();
            this.crossMarginStart = isRow ? m.top() : m.left();
            this.crossMarginEnd = isRow ? m.bottom() : m.right();
            this.baseMainSize = baseMainSize;
            this.targetMainSize = baseMainSize;
            this.minMainSize = minMainSize;
        }

        float outerBaseMain() {
            return baseMainSize + mainMarginStart + mainMarginEnd;
        }

        float outerTargetMain() {
            return targetMainSize + mainMarginStart + mainMarginEnd;
        }

        float crossSize(boolean isRow) {
            return isRow ? laidOutBox.borderBox().height() : laidOutBox.borderBox().width();
        }

        float outerCrossSize(boolean isRow) {
            return crossSize(isRow) + crossMarginStart + crossMarginEnd;
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
        FlexProps flex = style.flex();
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

        Float explicitContentHeight = null;
        if (style.height() != null) {
            float rawH = style.height().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
            float borderBoxH = style.borderBox() ? rawH : rawH + padding.vertical() + border.vertical();
            explicitContentHeight = Math.max(0f, borderBoxH - border.vertical() - padding.vertical());
        }

        boolean isRow = flex.isRow();
        float mainGap = isRow ? flex.columnGapPx() : flex.rowGapPx();
        float crossGap = isRow ? flex.rowGapPx() : flex.columnGapPx();
        Float availableMain = isRow ? Float.valueOf(contentWidth) : explicitContentHeight;

        // 1. Collect flex items and measure hypothetical main sizes
        List<FlexItem> items = new ArrayList<>();
        for (LayoutNode child : container.children()) {
            if (child.style().display() == Display.NONE) continue;
            float baseMain = computeHypotheticalMainSize(child, isRow, contentWidth, availableMain, fonts);
            float minMain = resolveMinMainSize(child.style(), isRow, availableMain == null ? contentWidth : availableMain);
            items.add(new FlexItem(child, isRow, Math.max(baseMain, minMain), minMain));
        }

        // 2. Partition items into flex lines (wrap vs nowrap)
        List<List<FlexItem>> lines = new ArrayList<>();
        if (items.isEmpty()) {
            // nothing to lay out
        } else if (flex.wrap() == FlexProps.FlexWrap.NOWRAP || availableMain == null) {
            lines.add(new ArrayList<>(items));
        } else {
            List<FlexItem> current = new ArrayList<>();
            float used = 0f;
            for (FlexItem item : items) {
                float itemOuter = item.outerBaseMain();
                float needed = current.isEmpty() ? itemOuter : used + mainGap + itemOuter;
                if (!current.isEmpty() && needed > availableMain + 0.01f) {
                    lines.add(current);
                    current = new ArrayList<>();
                    used = 0f;
                }
                current.add(item);
                used = current.size() == 1 ? itemOuter : used + mainGap + itemOuter;
            }
            if (!current.isEmpty()) {
                lines.add(current);
            }
        }

        // 3. Resolve flexible lengths (grow / shrink) and lay out each line
        List<LayoutBox> placedChildren = new ArrayList<>();
        float crossCursor = 0f;
        float maxLineMainUsed = 0f;

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            List<FlexItem> line = lines.get(lineIdx);
            float totalGaps = line.size() > 1 ? mainGap * (line.size() - 1) : 0f;
            float sumBaseOuter = 0f;
            for (FlexItem item : line) sumBaseOuter += item.outerBaseMain();

            if (availableMain != null) {
                float freeSpace = availableMain - sumBaseOuter - totalGaps;
                if (freeSpace > 0.01f) {
                    float totalGrow = 0f;
                    for (FlexItem item : line) totalGrow += item.style.flex().flexGrow();
                    if (totalGrow > 0f) {
                        for (FlexItem item : line) {
                            float g = item.style.flex().flexGrow();
                            if (g > 0f) {
                                item.targetMainSize = item.baseMainSize + (freeSpace * (g / totalGrow));
                            }
                        }
                    }
                } else if (freeSpace < -0.01f) {
                    float overflow = -freeSpace;
                    float totalScaledShrink = 0f;
                    for (FlexItem item : line) {
                        totalScaledShrink += item.style.flex().flexShrink() * Math.max(1f, item.baseMainSize);
                    }
                    if (totalScaledShrink > 0f) {
                        for (FlexItem item : line) {
                            float scaled = item.style.flex().flexShrink() * Math.max(1f, item.baseMainSize);
                            float delta = overflow * (scaled / totalScaledShrink);
                            item.targetMainSize = Math.max(item.minMainSize, item.baseMainSize - delta);
                        }
                    }
                }
            }

            // Lay out each item at (0, 0) with its resolved main size to discover cross size
            float lineCrossSize = 0f;
            for (FlexItem item : line) {
                LayoutNode sizedNode = withFixedMainSize(item.node, isRow, item.targetMainSize);
                float childContainingW = isRow
                        ? item.targetMainSize + item.style.margin().horizontal()
                        : contentWidth;
                item.laidOutBox = engine.layoutNodeAtOrigin(sizedNode, childContainingW, fonts);
                if (!isRow && item.style.height() == null && item.style.flex().flexBasis() == null
                        && item.style.flex().flexGrow() == 0f) {
                    // For auto-height column items, update targetMainSize to actual laid-out height
                    item.targetMainSize = item.laidOutBox.borderBox().height();
                }
                lineCrossSize = Math.max(lineCrossSize, item.outerCrossSize(isRow));
            }

            // Single-line stretch to container's explicit cross dimension
            if (lines.size() == 1) {
                if (isRow && explicitContentHeight != null) {
                    lineCrossSize = Math.max(lineCrossSize, explicitContentHeight);
                } else if (!isRow) {
                    lineCrossSize = Math.max(lineCrossSize, contentWidth);
                }
            }

            // Apply align-items / align-self: stretch
            for (FlexItem item : line) {
                FlexProps.AlignItems effectiveAlign = resolveItemAlignment(item.style.flex().alignSelf(), flex.alignItems());
                boolean crossIsAuto = isRow ? item.style.height() == null : item.style.width() == null;
                if (effectiveAlign == FlexProps.AlignItems.STRETCH && crossIsAuto) {
                    float stretchedCross = Math.max(0f, lineCrossSize - item.crossMarginStart - item.crossMarginEnd);
                    if (Math.abs(stretchedCross - item.crossSize(isRow)) > 0.05f) {
                        LayoutNode stretchedNode = withFixedDimensions(
                                item.node,
                                isRow ? item.targetMainSize : stretchedCross,
                                isRow ? stretchedCross : item.targetMainSize
                        );
                        float childContainingW = (isRow ? item.targetMainSize : stretchedCross) + item.style.margin().horizontal();
                        item.laidOutBox = engine.layoutNodeAtOrigin(stretchedNode, childContainingW, fonts);
                    }
                }
            }

            // Compute main-axis justification offsets
            float sumFinalOuter = 0f;
            for (FlexItem item : line) sumFinalOuter += item.outerTargetMain();
            float remainingMain = availableMain != null ? (availableMain - sumFinalOuter - totalGaps) : 0f;
            maxLineMainUsed = Math.max(maxLineMainUsed, sumFinalOuter + totalGaps);

            float mainCursor;
            float itemGap = mainGap;
            int n = line.size();
            switch (flex.justifyContent()) {
                case FLEX_END -> mainCursor = remainingMain;
                case CENTER -> mainCursor = remainingMain / 2f;
                case SPACE_BETWEEN -> {
                    mainCursor = 0f;
                    if (n > 1 && remainingMain > 0f) itemGap = mainGap + (remainingMain / (n - 1));
                }
                case SPACE_AROUND -> {
                    if (remainingMain > 0f) {
                        float step = remainingMain / n;
                        mainCursor = step / 2f;
                        itemGap = mainGap + step;
                    } else {
                        mainCursor = remainingMain / 2f;
                    }
                }
                case SPACE_EVENLY -> {
                    if (remainingMain > 0f) {
                        float step = remainingMain / (n + 1);
                        mainCursor = step;
                        itemGap = mainGap + step;
                    } else {
                        mainCursor = remainingMain / 2f;
                    }
                }
                default -> mainCursor = 0f;
            }

            List<FlexItem> ordered = flex.isReverse() ? reversed(line) : line;
            for (int i = 0; i < ordered.size(); i++) {
                FlexItem item = ordered.get(i);
                FlexProps.AlignItems effectiveAlign = resolveItemAlignment(item.style.flex().alignSelf(), flex.alignItems());
                float crossOffset = switch (effectiveAlign) {
                    case CENTER -> (lineCrossSize - item.outerCrossSize(isRow)) / 2f;
                    case FLEX_END -> lineCrossSize - item.outerCrossSize(isRow);
                    default -> 0f;
                };

                float targetX;
                float targetY;
                if (isRow) {
                    targetX = contentX + mainCursor + item.mainMarginStart;
                    targetY = contentY + crossCursor + crossOffset + item.crossMarginStart;
                } else {
                    targetX = contentX + crossCursor + crossOffset + item.crossMarginStart;
                    targetY = contentY + mainCursor + item.mainMarginStart;
                }

                float dx = targetX - item.laidOutBox.borderBox().x();
                float dy = targetY - item.laidOutBox.borderBox().y();
                placedChildren.add(LayoutEngine.translateBox(item.laidOutBox, dx, dy));

                mainCursor += item.outerTargetMain();
                if (i < ordered.size() - 1) {
                    mainCursor += itemGap;
                }
            }

            crossCursor += lineCrossSize;
            if (lineIdx < lines.size() - 1) {
                crossCursor += crossGap;
            }
        }

        float contentHeight = isRow ? crossCursor : maxLineMainUsed;
        float borderBoxHeight = engine.resolveBorderBoxHeight(style, contentHeight, padding, border);

        return new LayoutBox(
                container.id(),
                style,
                new Rect(boxX, boxY, borderBoxWidth, borderBoxHeight),
                List.of(),
                null,
                null,
                placedChildren
        );
    }

    private float computeHypotheticalMainSize(
            LayoutNode child,
            boolean isRow,
            float containerContentWidth,
            Float availableMain,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle s = child.style();
        Edges p = s.padding();
        Edges b = s.border();
        float mainPadBorder = isRow ? (p.horizontal() + b.horizontal()) : (p.vertical() + b.vertical());
        float pctBase = availableMain != null ? availableMain : containerContentWidth;

        CssLength basis = s.flex().flexBasis();
        if (basis != null && !basis.isAuto()) {
            float raw = basis.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
            return s.borderBox() ? raw : raw + mainPadBorder;
        }

        CssLength explicit = isRow ? s.width() : s.height();
        if (explicit != null && !explicit.isAuto()) {
            float raw = explicit.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
            return s.borderBox() ? raw : raw + mainPadBorder;
        }

        if (isRow) {
            // Measure intrinsic max-content width (clamped to container content width)
            float intrinsic = engine.measureMaxContentWidth(child, containerContentWidth, fonts);
            return Math.min(intrinsic, containerContentWidth);
        } else {
            // Measure intrinsic height by laying out at container content width
            LayoutBox measured = engine.layoutNodeAtOrigin(child, containerContentWidth, fonts);
            return measured.borderBox().height();
        }
    }

    private float resolveMinMainSize(LayoutStyle s, boolean isRow, float pctBase) {
        CssLength minLen = isRow ? s.minWidth() : s.minHeight();
        if (minLen == null || minLen.isAuto()) return 0f;
        return Math.max(0f, minLen.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase));
    }

    private static FlexProps.AlignItems resolveItemAlignment(FlexProps.AlignSelf self, FlexProps.AlignItems containerDefault) {
        return switch (self) {
            case STRETCH -> FlexProps.AlignItems.STRETCH;
            case FLEX_START -> FlexProps.AlignItems.FLEX_START;
            case FLEX_END -> FlexProps.AlignItems.FLEX_END;
            case CENTER -> FlexProps.AlignItems.CENTER;
            case AUTO -> containerDefault;
        };
    }

    private static LayoutNode withFixedMainSize(LayoutNode node, boolean isRow, float borderBoxMainSize) {
        LayoutStyle s = node.style();
        if (isRow) {
            float w = s.borderBox()
                    ? borderBoxMainSize
                    : Math.max(0f, borderBoxMainSize - s.padding().horizontal() - s.border().horizontal());
            LayoutStyle updated = s.withDimensions(new CssLength(w, CssLength.Unit.PX), s.height());
            return new LayoutNode(node.id(), updated, node.text(), node.imageBytes(), node.linkHref(), node.children());
        } else {
            if (s.height() == null && s.flex().flexBasis() == null && s.flex().flexGrow() == 0f) {
                return node; // keep auto height
            }
            float h = s.borderBox()
                    ? borderBoxMainSize
                    : Math.max(0f, borderBoxMainSize - s.padding().vertical() - s.border().vertical());
            LayoutStyle updated = s.withDimensions(s.width(), new CssLength(h, CssLength.Unit.PX));
            return new LayoutNode(node.id(), updated, node.text(), node.imageBytes(), node.linkHref(), node.children());
        }
    }

    private static LayoutNode withFixedDimensions(LayoutNode node, float borderBoxW, float borderBoxH) {
        LayoutStyle s = node.style();
        float w = s.borderBox() ? borderBoxW : Math.max(0f, borderBoxW - s.padding().horizontal() - s.border().horizontal());
        float h = s.borderBox() ? borderBoxH : Math.max(0f, borderBoxH - s.padding().vertical() - s.border().vertical());
        LayoutStyle updated = s.withDimensions(
                new CssLength(w, CssLength.Unit.PX),
                new CssLength(h, CssLength.Unit.PX)
        );
        return new LayoutNode(node.id(), updated, node.text(), node.imageBytes(), node.linkHref(), node.children());
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }
}
