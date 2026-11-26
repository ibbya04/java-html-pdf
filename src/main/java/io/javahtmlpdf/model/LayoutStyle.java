package io.javahtmlpdf.model;

import io.javahtmlpdf.css.CssColor;
import io.javahtmlpdf.css.CssLength;

/**
 * Resolved layout and visual CSS properties for a single node.
 * Nullable {@link CssLength} dimensions represent CSS {@code auto}.
 */
public record LayoutStyle(
        Display display,
        CssLength width,
        CssLength height,
        CssLength minWidth,
        CssLength minHeight,
        CssLength maxWidth,
        Edges margin,
        boolean marginLeftAuto,
        boolean marginRightAuto,
        Edges padding,
        Edges border,
        boolean borderBox,
        CssColor backgroundColor,
        CssColor borderTopColor,
        CssColor borderRightColor,
        CssColor borderBottomColor,
        CssColor borderLeftColor,
        float borderRadiusPx,
        CssColor color,
        String fontFamily,
        float fontSizePx,
        boolean bold,
        boolean italic,
        float lineHeightMultiplier,
        TextAlign textAlign,
        boolean underline,
        boolean whiteSpaceNowrap,
        boolean listItem,
        boolean overflowHidden,
        FlexProps flex,
        GridProps grid,
        TableRole tableRole,
        int colSpan,
        BreakProps breaks
) {

    public enum TextAlign {
        LEFT, CENTER, RIGHT
    }

    public LayoutStyle {
        flex = flex == null ? FlexProps.DEFAULT : flex;
        grid = grid == null ? GridProps.DEFAULT : grid;
        tableRole = tableRole == null ? TableRole.NONE : tableRole;
        colSpan = Math.max(1, colSpan);
        breaks = breaks == null ? BreakProps.DEFAULT : breaks;
    }

    public static LayoutStyle defaults() {
        return new LayoutStyle(
                Display.BLOCK,
                null, null, null, null, null,
                Edges.ZERO,
                false, false,
                Edges.ZERO,
                Edges.ZERO,
                true,
                null,
                CssColor.BLACK, CssColor.BLACK, CssColor.BLACK, CssColor.BLACK,
                0f,
                CssColor.BLACK,
                "sans-serif",
                16.0f,
                false, false,
                1.2f,
                TextAlign.LEFT,
                false, false, false, false,
                FlexProps.DEFAULT,
                GridProps.DEFAULT,
                TableRole.NONE,
                1,
                BreakProps.DEFAULT
        );
    }

    public LayoutStyle withDimensions(CssLength newWidth, CssLength newHeight) {
        return new LayoutStyle(
                display, newWidth, newHeight, minWidth, minHeight, maxWidth,
                margin, marginLeftAuto, marginRightAuto,
                padding, border, borderBox,
                backgroundColor,
                borderTopColor, borderRightColor, borderBottomColor, borderLeftColor,
                borderRadiusPx, color, fontFamily, fontSizePx,
                bold, italic, lineHeightMultiplier, textAlign,
                underline, whiteSpaceNowrap, listItem, overflowHidden,
                flex, grid, tableRole, colSpan, breaks
        );
    }

    public LayoutStyle withFlex(FlexProps newFlex) {
        return new LayoutStyle(
                display, width, height, minWidth, minHeight, maxWidth,
                margin, marginLeftAuto, marginRightAuto,
                padding, border, borderBox,
                backgroundColor,
                borderTopColor, borderRightColor, borderBottomColor, borderLeftColor,
                borderRadiusPx, color, fontFamily, fontSizePx,
                bold, italic, lineHeightMultiplier, textAlign,
                underline, whiteSpaceNowrap, listItem, overflowHidden,
                newFlex, grid, tableRole, colSpan, breaks
        );
    }

    public LayoutStyle withGrid(GridProps newGrid) {
        return new LayoutStyle(
                display, width, height, minWidth, minHeight, maxWidth,
                margin, marginLeftAuto, marginRightAuto,
                padding, border, borderBox,
                backgroundColor,
                borderTopColor, borderRightColor, borderBottomColor, borderLeftColor,
                borderRadiusPx, color, fontFamily, fontSizePx,
                bold, italic, lineHeightMultiplier, textAlign,
                underline, whiteSpaceNowrap, listItem, overflowHidden,
                flex, newGrid, tableRole, colSpan, breaks
        );
    }

    public LayoutStyle withTableRole(TableRole newRole, int newColSpan) {
        return new LayoutStyle(
                display, width, height, minWidth, minHeight, maxWidth,
                margin, marginLeftAuto, marginRightAuto,
                padding, border, borderBox,
                backgroundColor,
                borderTopColor, borderRightColor, borderBottomColor, borderLeftColor,
                borderRadiusPx, color, fontFamily, fontSizePx,
                bold, italic, lineHeightMultiplier, textAlign,
                underline, whiteSpaceNowrap, listItem, overflowHidden,
                flex, grid, newRole, newColSpan, breaks
        );
    }

    /**
     * Convenience constructor matching Geometry Oracle fixtures.
     */
    public static LayoutStyle simpleBlock(
            Display display,
            Float widthPx,
            Float heightPx,
            Edges margin,
            Edges padding,
            Edges border,
            boolean borderBox,
            String backgroundColorHex,
            String borderColorHex,
            String colorHex,
            float fontSizePx
    ) {
        CssColor bg = CssColor.parse(backgroundColorHex).orElse(null);
        CssColor bc = CssColor.parse(borderColorHex).orElse(CssColor.BLACK);
        CssColor fg = CssColor.parse(colorHex).orElse(CssColor.BLACK);
        return new LayoutStyle(
                display,
                widthPx == null ? null : new CssLength(widthPx, CssLength.Unit.PX),
                heightPx == null ? null : new CssLength(heightPx, CssLength.Unit.PX),
                null, null, null,
                margin,
                false, false,
                padding,
                border,
                borderBox,
                bg,
                bc, bc, bc, bc,
                0f,
                fg,
                "sans-serif",
                fontSizePx,
                false, false,
                1.2f,
                TextAlign.LEFT,
                false, false, false, false,
                FlexProps.DEFAULT,
                GridProps.DEFAULT,
                TableRole.NONE,
                1,
                BreakProps.DEFAULT
        );
    }
}
