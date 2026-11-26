package io.javahtmlpdf.model;

import java.util.List;

/**
 * Input node to the {@link io.javahtmlpdf.layout.LayoutEngine}.
 * Represents either an element box, a text leaf ({@code text != null}), or a replaced image ({@code imageBytes != null}).
 */
public record LayoutNode(
        String id,
        LayoutStyle style,
        String text,
        byte[] imageBytes,
        String linkHref,
        List<LayoutNode> children
) {

    public LayoutNode {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public LayoutNode(String id, LayoutStyle style, String text, List<LayoutNode> children) {
        this(id, style, text, null, null, children);
    }

    public boolean isTextNode() {
        return text != null && children.isEmpty() && imageBytes == null;
    }

    public boolean isImageNode() {
        return imageBytes != null;
    }
}
