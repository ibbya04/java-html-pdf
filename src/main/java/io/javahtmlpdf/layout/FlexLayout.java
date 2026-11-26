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
 * {@code align-items}, {@code align-self}, {@code flex-grow}, {@code flex-shrink}
 * (with min/max freeze-and-redistribute), {@code flex-basis}, {@code gap}, and
 * both definite and indefinite ({@code max-content}) container sizing.
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
        final boolean mainMarginStartAuto;
        final boolean mainMarginEndAuto;
        final boolean crossMarginStartAuto;
        final boolean crossMarginEndAuto;
        final float baseMainSize;
        final float minMainSize;
        final float maxMainSize;
        float targetMainSize;
        boolean frozen;
        LayoutBox laidOutBox;

        FlexItem(LayoutNode node, boolean isRow, float baseMainSize, float minMainSize, float maxMainSize) {
            this.node = node;
            this.style = node.style();
            Edges m = style.margin();
            this.mainMarginStart = isRow ? m.left() : m.top();
            this.mainMarginEnd = isRow ? m.right() : m.bottom();
            this.crossMarginStart = isRow ? m.top() : m.left();
            this.crossMarginEnd = isRow ? m.bottom() : m.right();
            this.mainMarginStartAuto = isRow ? style.marginLeftAuto() : style.marginTopAuto();
            this.mainMarginEndAuto = isRow ? style.marginRightAuto() : style.marginBottomAuto();
            this.crossMarginStartAuto = isRow ? style.marginTopAuto() : style.marginLeftAuto();
            this.crossMarginEndAuto = isRow ? style.marginBottomAuto() : style.marginRightAuto();
            this.baseMainSize = baseMainSize;
            this.minMainSize = minMainSize;
            this.maxMainSize = maxMainSize;
            this.targetMainSize = clamp(baseMainSize, minMainSize, maxMainSize);
        }

        float outerHypotheticalMain() {
            return clamp(baseMainSize, minMainSize, maxMainSize) + mainMarginStart + mainMarginEnd;
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
            Float containingWidthObj,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle style = container.style();
        FlexProps flex = style.flex();
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
            float borderBoxH = style.borderBox() ? rawH : rawH + padding.vertical() + border.vertical();
            if (style.minHeight() != null) {
                float minH = style.minHeight().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
                borderBoxH = Math.max(borderBoxH, style.borderBox() ? minH : minH + padding.vertical() + border.vertical());
            }
            explicitContentHeight = Math.max(0f, borderBoxH - border.vertical() - padding.vertical());
        }

        boolean isRow = flex.isRow();
        float mainGap = isRow ? flex.columnGapPx() : flex.rowGapPx();
        float crossGap = isRow ? flex.rowGapPx() : flex.columnGapPx();
        Float availableMain = isRow ? explicitContentWidth : explicitContentHeight;

        // 1. Collect flex items and measure hypothetical main sizes
        List<FlexItem> items = new ArrayList<>();
        for (LayoutNode child : container.children()) {
            if (child.style().display() == Display.NONE) continue;
            float baseMain = computeHypotheticalMainSize(child, isRow, explicitContentWidth, availableMain, fonts);
            float pctBase = availableMain != null ? availableMain : (explicitContentWidth != null ? explicitContentWidth : 0f);
            float minMain = resolveMinMainSize(child, isRow, pctBase, baseMain);
            float maxMain = resolveMaxMainSize(child.style(), isRow, pctBase);
            items.add(new FlexItem(child, isRow, baseMain, minMain, maxMain));
        }

        // 2. Partition items into flex lines
        List<List<FlexItem>> lines = new ArrayList<>();
        if (items.isEmpty()) {
            // empty flex container
        } else if (flex.wrap() == FlexProps.FlexWrap.NOWRAP || availableMain == null) {
            lines.add(new ArrayList<>(items));
        } else {
            List<FlexItem> current = new ArrayList<>();
            float used = 0f;
            for (FlexItem item : items) {
                float itemOuter = item.outerHypotheticalMain();
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

        // 3. Resolve flexible lengths (CSS freeze-and-redistribute loop) and lay out each line
        List<LayoutBox> placedChildren = new ArrayList<>();
        float crossCursor = 0f;
        float maxLineMainUsed = 0f;

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            List<FlexItem> line = lines.get(lineIdx);
            float totalGaps = line.size() > 1 ? mainGap * (line.size() - 1) : 0f;

            if (availableMain != null) {
                resolveFlexibleLengths(line, availableMain - totalGaps);
            }

            // Lay out each item at (0, 0) with its resolved main size
            float lineCrossSize = 0f;
            for (FlexItem item : line) {
                FlexProps.AlignItems effectiveAlign = resolveItemAlignment(item.style.flex().alignSelf(), flex.alignItems());
                LayoutNode sizedNode = withFixedMainSize(item.node, isRow, item.targetMainSize);
                Float childContainingW;
                if (isRow) {
                    childContainingW = item.targetMainSize + item.style.margin().horizontal();
                } else {
                    // Column flex item: stretch if align is STRETCH (and container width is definite), else shrink-to-fit!
                    boolean stretchWidth = (effectiveAlign == FlexProps.AlignItems.STRETCH && explicitContentWidth != null);
                    childContainingW = stretchWidth ? explicitContentWidth : null;
                }
                item.laidOutBox = engine.layoutNodeAtOrigin(sizedNode, childContainingW, fonts);
                if (!isRow && item.style.height() == null && item.style.flex().flexBasis() == null
                        && item.style.flex().flexGrow() == 0f) {
                    item.targetMainSize = clamp(item.laidOutBox.borderBox().height(), item.minMainSize, item.maxMainSize);
                }
                lineCrossSize = Math.max(lineCrossSize, item.outerCrossSize(isRow));
            }

            // Single-line stretch to container's explicit cross dimension
            if (lines.size() == 1) {
                if (isRow && explicitContentHeight != null) {
                    lineCrossSize = Math.max(lineCrossSize, explicitContentHeight);
                } else if (!isRow && explicitContentWidth != null) {
                    lineCrossSize = Math.max(lineCrossSize, explicitContentWidth);
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

            // Compute main-axis justification & auto-margins
            float sumFinalOuter = 0f;
            int mainAutoMargins = 0;
            for (FlexItem item : line) {
                sumFinalOuter += item.outerTargetMain();
                if (item.mainMarginStartAuto) mainAutoMargins++;
                if (item.mainMarginEndAuto) mainAutoMargins++;
            }
            float lineContentMain = sumFinalOuter + totalGaps;
            float containerMinMain = 0f;
            if (isRow && style.minWidth() != null && !style.minWidth().isPercent()) {
                float rawMin = style.minWidth().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
                float bbMin = style.borderBox() ? rawMin : rawMin + padding.horizontal() + border.horizontal();
                containerMinMain = Math.max(0f, bbMin - padding.horizontal() - border.horizontal());
            } else if (!isRow && style.minHeight() != null && !style.minHeight().isPercent()) {
                float rawMin = style.minHeight().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
                float bbMin = style.borderBox() ? rawMin : rawMin + padding.vertical() + border.vertical();
                containerMinMain = Math.max(0f, bbMin - padding.vertical() - border.vertical());
            }
            float effectiveMain = availableMain != null
                    ? Math.max(availableMain, containerMinMain)
                    : Math.max(lineContentMain, containerMinMain);
            float remainingMain = effectiveMain - lineContentMain;
            maxLineMainUsed = Math.max(maxLineMainUsed, Math.max(lineContentMain, containerMinMain));

            float autoMarginSpace = (mainAutoMargins > 0 && remainingMain > 0f) ? (remainingMain / mainAutoMargins) : 0f;
            float justifyRemaining = (mainAutoMargins > 0 && remainingMain > 0f) ? 0f : remainingMain;

            float mainCursor;
            float itemGap = mainGap;
            int n = line.size();
            switch (flex.justifyContent()) {
                case FLEX_END -> mainCursor = justifyRemaining;
                case CENTER -> mainCursor = justifyRemaining / 2f;
                case SPACE_BETWEEN -> {
                    mainCursor = 0f;
                    if (n > 1 && justifyRemaining > 0f) itemGap = mainGap + (justifyRemaining / (n - 1));
                }
                case SPACE_AROUND -> {
                    if (justifyRemaining > 0f) {
                        float step = justifyRemaining / n;
                        mainCursor = step / 2f;
                        itemGap = mainGap + step;
                    } else {
                        mainCursor = justifyRemaining / 2f;
                    }
                }
                case SPACE_EVENLY -> {
                    if (justifyRemaining > 0f) {
                        float step = justifyRemaining / (n + 1);
                        mainCursor = step;
                        itemGap = mainGap + step;
                    } else {
                        mainCursor = justifyRemaining / 2f;
                    }
                }
                default -> mainCursor = 0f;
            }

            List<FlexItem> ordered = flex.isReverse() ? reversed(line) : line;
            for (int i = 0; i < ordered.size(); i++) {
                FlexItem item = ordered.get(i);
                FlexProps.AlignItems effectiveAlign = resolveItemAlignment(item.style.flex().alignSelf(), flex.alignItems());
                float crossFree = lineCrossSize - item.outerCrossSize(isRow);
                float crossOffset;
                if (item.crossMarginStartAuto && item.crossMarginEndAuto) {
                    crossOffset = Math.max(0f, crossFree) / 2f;
                } else if (item.crossMarginStartAuto) {
                    crossOffset = Math.max(0f, crossFree);
                } else if (item.crossMarginEndAuto) {
                    crossOffset = 0f;
                } else {
                    crossOffset = switch (effectiveAlign) {
                        case CENTER -> crossFree / 2f;
                        case FLEX_END -> crossFree;
                        default -> 0f;
                    };
                }

                float extraMainStart = item.mainMarginStartAuto ? autoMarginSpace : 0f;
                float extraMainEnd = item.mainMarginEndAuto ? autoMarginSpace : 0f;

                float targetX;
                float targetY;
                if (isRow) {
                    targetX = contentX + mainCursor + item.mainMarginStart + extraMainStart;
                    targetY = contentY + crossCursor + crossOffset + item.crossMarginStart;
                } else {
                    targetX = contentX + crossCursor + crossOffset + item.crossMarginStart;
                    targetY = contentY + mainCursor + item.mainMarginStart + extraMainStart;
                }

                float dx = targetX - item.laidOutBox.borderBox().x();
                float dy = targetY - item.laidOutBox.borderBox().y();
                placedChildren.add(LayoutEngine.translateBox(item.laidOutBox, dx, dy));

                mainCursor += item.outerTargetMain() + extraMainStart + extraMainEnd;
                if (i < ordered.size() - 1) {
                    mainCursor += itemGap;
                }
            }

            crossCursor += lineCrossSize;
            if (lineIdx < lines.size() - 1) {
                crossCursor += crossGap;
            }
        }

        if (!definiteWidth) {
            float contentW = isRow ? maxLineMainUsed : crossCursor;
            borderBoxWidth = border.horizontal() + padding.horizontal() + contentW;
            if (style.maxWidth() != null && !style.maxWidth().isPercent()) {
                float maxW = style.maxWidth().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
                borderBoxWidth = Math.min(borderBoxWidth, style.borderBox() ? maxW : maxW + padding.horizontal() + border.horizontal());
            }
            if (style.minWidth() != null && !style.minWidth().isPercent()) {
                float minW = style.minWidth().toPx(style.fontSizePx(), ROOT_FONT_SIZE_PX, 0f);
                borderBoxWidth = Math.max(borderBoxWidth, style.borderBox() ? minW : minW + padding.horizontal() + border.horizontal());
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

    /**
     * CSS Flexbox §9.7 Resolve the Flexible Lengths with freeze-and-redistribute for min/max clamping.
     */
    private void resolveFlexibleLengths(List<FlexItem> line, float availableSpace) {
        float initialSum = 0f;
        for (FlexItem item : line) initialSum += item.outerHypotheticalMain();
        boolean growing = initialSum < availableSpace;

        for (FlexItem item : line) {
            float factor = growing ? item.style.flex().flexGrow() : item.style.flex().flexShrink();
            if (factor <= 0f) {
                item.frozen = true;
                item.targetMainSize = clamp(item.baseMainSize, item.minMainSize, item.maxMainSize);
            } else {
                item.frozen = false;
                item.targetMainSize = item.baseMainSize;
            }
        }

        for (int iter = 0; iter < line.size() + 2; iter++) {
            float usedFrozen = 0f;
            float usedUnfrozen = 0f;
            float totalFactor = 0f;
            int unfrozenCount = 0;

            for (FlexItem item : line) {
                if (item.frozen) {
                    usedFrozen += item.outerTargetMain();
                } else {
                    usedUnfrozen += item.baseMainSize + item.mainMarginStart + item.mainMarginEnd;
                    totalFactor += growing
                            ? item.style.flex().flexGrow()
                            : (item.style.flex().flexShrink() * Math.max(1f, item.baseMainSize));
                    unfrozenCount++;
                }
            }
            if (unfrozenCount == 0 || totalFactor <= 0f) break;

            float remainingFree = availableSpace - usedFrozen - usedUnfrozen;
            if (Math.abs(remainingFree) < 0.01f) break;

            boolean anyViolation = false;
            for (FlexItem item : line) {
                if (item.frozen) continue;
                float share = growing
                        ? (item.style.flex().flexGrow() / totalFactor)
                        : ((item.style.flex().flexShrink() * Math.max(1f, item.baseMainSize)) / totalFactor);
                float proposed = item.baseMainSize + (remainingFree * share);
                float clamped = clamp(proposed, item.minMainSize, item.maxMainSize);
                if (Math.abs(clamped - proposed) > 0.01f) {
                    item.targetMainSize = clamped;
                    item.frozen = true;
                    anyViolation = true;
                } else {
                    item.targetMainSize = proposed;
                }
            }
            if (!anyViolation) break;
        }
    }

    private float computeHypotheticalMainSize(
            LayoutNode child,
            boolean isRow,
            Float containerContentWidth,
            Float availableMain,
            FontRegistry.LoadedFonts fonts
    ) {
        LayoutStyle s = child.style();
        Edges p = s.padding();
        Edges b = s.border();
        float mainPadBorder = isRow ? (p.horizontal() + b.horizontal()) : (p.vertical() + b.vertical());
        float pctBase = availableMain != null ? availableMain : (containerContentWidth != null ? containerContentWidth : 0f);

        CssLength basis = s.flex().flexBasis();
        if (basis != null && !basis.isAuto()) {
            if (!basis.isPercent() || availableMain != null) {
                float raw = basis.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
                return s.borderBox() ? raw : raw + mainPadBorder;
            }
        }

        CssLength explicit = isRow ? s.width() : s.height();
        if (explicit != null && !explicit.isAuto()) {
            if (!explicit.isPercent() || availableMain != null) {
                float raw = explicit.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
                return s.borderBox() ? raw : raw + mainPadBorder;
            }
        }

        if (isRow) {
            float intrinsic = engine.measureMaxContentWidth(child, containerContentWidth != null ? containerContentWidth : 800f, fonts);
            return containerContentWidth != null ? Math.min(intrinsic, containerContentWidth) : intrinsic;
        } else {
            LayoutBox measured = engine.layoutNodeAtOrigin(child, containerContentWidth, fonts);
            return measured.borderBox().height();
        }
    }

    private float resolveMinMainSize(LayoutNode child, boolean isRow, float pctBase, float baseMain) {
        LayoutStyle s = child.style();
        CssLength minLen = isRow ? s.minWidth() : s.minHeight();
        if (minLen != null && !minLen.isAuto()) {
            float raw = minLen.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
            float padBorder = isRow ? (s.padding().horizontal() + s.border().horizontal()) : (s.padding().vertical() + s.border().vertical());
            return Math.max(0f, s.borderBox() ? raw : raw + padBorder);
        }
        // CSS Flexbox §4.5 automatic minimum size when overflow is visible and main dimension is auto
        boolean mainIsAuto = (isRow ? s.width() == null : s.height() == null)
                && (s.flex().flexBasis() == null || s.flex().flexBasis().isAuto());
        if (mainIsAuto && !s.overflowHidden() && !child.children().isEmpty() && s.flex().flexGrow() == 0f) {
            return baseMain;
        }
        return 0f;
    }

    private float resolveMaxMainSize(LayoutStyle s, boolean isRow, float pctBase) {
        if (!isRow) return Float.POSITIVE_INFINITY;
        CssLength maxLen = s.maxWidth();
        if (maxLen == null || maxLen.isAuto()) return Float.POSITIVE_INFINITY;
        float raw = maxLen.toPx(s.fontSizePx(), ROOT_FONT_SIZE_PX, pctBase);
        return s.borderBox() ? raw : raw + s.padding().horizontal() + s.border().horizontal();
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
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
                return node;
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
