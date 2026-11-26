package io.javahtmlpdf.model;

import io.javahtmlpdf.css.CssLength;

import java.util.List;

/**
 * CSS Grid container and item placement properties resolved from CSS.
 */
public record GridProps(
        List<GridTrack> templateColumns,
        List<GridTrack> templateRows,
        int colStart,   // 1-based, 0 = auto
        int colSpan,    // >= 1
        int rowStart,   // 1-based, 0 = auto
        int rowSpan,    // >= 1
        float rowGapPx,
        float columnGapPx
) {

    public record GridTrack(Kind kind, float value, CssLength length, float minPx, float maxFr) {
        public enum Kind { LENGTH, FR, AUTO, MINMAX }

        public static GridTrack fr(float fr) {
            return new GridTrack(Kind.FR, fr, null, 0f, fr);
        }

        public static GridTrack length(CssLength len) {
            return new GridTrack(Kind.LENGTH, len.value(), len, 0f, 0f);
        }

        public static GridTrack auto() {
            return new GridTrack(Kind.AUTO, 0f, null, 0f, 0f);
        }

        public static GridTrack minmax(float minPx, float maxFr) {
            return new GridTrack(Kind.MINMAX, maxFr, null, minPx, maxFr);
        }
    }

    public GridProps {
        templateColumns = templateColumns == null ? List.of() : List.copyOf(templateColumns);
        templateRows = templateRows == null ? List.of() : List.copyOf(templateRows);
        colSpan = Math.max(1, colSpan);
        rowSpan = Math.max(1, rowSpan);
    }

    public static final GridProps DEFAULT = new GridProps(
            List.of(), List.of(), 0, 1, 0, 1, 0f, 0f
    );
}
