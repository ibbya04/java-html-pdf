package io.javahtmlpdf.model;

import java.util.List;

/**
 * Output of the layout engine: a positioned box with exact border-box geometry
 * in CSS pixels (top-left origin), wrapped {@link LineBox}es, optional image bytes,
 * optional list bullet marker, and laid-out child boxes.
 */
public record LayoutBox(
        String id,
        LayoutStyle style,
        Rect borderBox,
        List<LineBox> lines,
        byte[] imageBytes,
        String listMarker,
        List<LayoutBox> children
) {

    public LayoutBox {
        lines = lines == null ? List.of() : List.copyOf(lines);
        children = children == null ? List.of() : List.copyOf(children);
    }
}
