package io.javahtmlpdf;

/**
 * Standard physical Page Sheet sizes in PDF points (1 pt = 1/72 inch).
 * CSS pixels map to PDF points via {@code 1 CSS px = 0.75 pt} (96 DPI -> 72 DPI).
 */
public record PageSize(float widthPt, float heightPt) {

    public static final float PX_TO_PT = 72.0f / 96.0f; // 0.75
    public static final float PT_TO_PX = 96.0f / 72.0f; // 1.3333...

    public static final PageSize A4 = new PageSize(595.28f, 841.89f);
    public static final PageSize LETTER = new PageSize(612.0f, 792.0f);

    public float widthPx() {
        return widthPt * PT_TO_PX;
    }

    public float heightPx() {
        return heightPt * PT_TO_PX;
    }

    public PageSize landscape() {
        return new PageSize(heightPt, widthPt);
    }
}
