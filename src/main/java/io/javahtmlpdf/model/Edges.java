package io.javahtmlpdf.model;

/**
 * Immutable four-sided edge sizes (CSS px) for margin, padding, or border widths.
 */
public record Edges(float top, float right, float bottom, float left) {

    public static final Edges ZERO = new Edges(0f, 0f, 0f, 0f);

    public static Edges all(float value) {
        return new Edges(value, value, value, value);
    }

    public float horizontal() {
        return left + right;
    }

    public float vertical() {
        return top + bottom;
    }
}
