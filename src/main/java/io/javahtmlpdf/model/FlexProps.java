package io.javahtmlpdf.model;

import io.javahtmlpdf.css.CssLength;

/**
 * Flexbox container and item properties resolved from CSS.
 */
public record FlexProps(
        FlexDirection direction,
        FlexWrap wrap,
        JustifyContent justifyContent,
        AlignItems alignItems,
        AlignSelf alignSelf,
        float flexGrow,
        float flexShrink,
        CssLength flexBasis,
        float rowGapPx,
        float columnGapPx
) {

    public enum FlexDirection { ROW, COLUMN, ROW_REVERSE, COLUMN_REVERSE }
    public enum FlexWrap { NOWRAP, WRAP }
    public enum JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
    public enum AlignItems { STRETCH, FLEX_START, FLEX_END, CENTER }
    public enum AlignSelf { AUTO, STRETCH, FLEX_START, FLEX_END, CENTER }

    public static final FlexProps DEFAULT = new FlexProps(
            FlexDirection.ROW,
            FlexWrap.NOWRAP,
            JustifyContent.FLEX_START,
            AlignItems.STRETCH,
            AlignSelf.AUTO,
            0f,
            1f,
            null,
            0f,
            0f
    );

    public boolean isRow() {
        return direction == FlexDirection.ROW || direction == FlexDirection.ROW_REVERSE;
    }

    public boolean isReverse() {
        return direction == FlexDirection.ROW_REVERSE || direction == FlexDirection.COLUMN_REVERSE;
    }
}
