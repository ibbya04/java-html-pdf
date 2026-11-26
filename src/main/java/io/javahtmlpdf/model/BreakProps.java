package io.javahtmlpdf.model;

/**
 * CSS Fragmentation / Pagination hints for multi-page layout.
 */
public record BreakProps(
        boolean breakBeforePage,
        boolean breakAfterAvoid,
        boolean breakInsideAvoid,
        int orphans,
        int widows
) {
    public static final BreakProps DEFAULT = new BreakProps(false, false, false, 2, 2);
}
