package io.javahtmlpdf.model;

/**
 * Immutable 2D rectangle in CSS pixels with top-left origin ({@code +Y} downward).
 */
public record Rect(float x, float y, float width, float height) {

    public static final Rect ZERO = new Rect(0f, 0f, 0f, 0f);

    public float right() {
        return x + width;
    }

    public float bottom() {
        return y + height;
    }
}
