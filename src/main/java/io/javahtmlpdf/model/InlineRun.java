package io.javahtmlpdf.model;

/**
 * A contiguous span of text sharing a single font, size, color, and decoration
 * inside a {@link LineBox}.
 */
public record InlineRun(
        String text,
        LayoutStyle style,
        float x,
        float y,
        float width,
        float height,
        float baselineY,
        String linkHref
) {}
