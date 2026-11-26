package io.javahtmlpdf.model;

import io.javahtmlpdf.css.CssColor;
import io.javahtmlpdf.css.CssLength;

/**
 * Resolved layout and visual CSS properties for a single node.
 * Nullable {@link CssLength} or {@code Float} dimensions represent CSS {@code auto}.
 */
public record LayoutStyle(
        Display display,
        CssLength width,
        CssLength height,
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
        boolean listItem,
        boolean overflowHidden
) {

    public enum TextAlign {
        LEFT, CENTER, RIGHT
    }

    public static LayoutStyle defaults() {
        return new LayoutStyle(
                Display.BLOCK,
                null,
                null,
                null,
                Edges.ZERO,
                false,
                false,
                Edges.ZERO,
                Edges.ZERO,
                true,
                null,
                CssColor.BLACK,
                CssColor.BLACK,
                CssColor.BLACK,
                CssColor.BLACK,
                0f,
                CssColor.BLACK,
                "sans-serif",
                16.0f,
                false,
                false,
                1.2f,
                TextAlign.LEFT,
                false,
                false,
                false
        );
    }

    /**
     * Convenience constructor matching Phase 0 tests and Geometry Oracle fixtures.
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
                null,
                margin,
                false,
                false,
                padding,
                border,
                borderBox,
                bg,
                bc,
                bc,
                bc,
                bc,
                0f,
                fg,
                "sans-serif",
                fontSizePx,
                false,
                false,
                1.2f,
                TextAlign.LEFT,
                false,
                false,
                false
        );
    }
}
