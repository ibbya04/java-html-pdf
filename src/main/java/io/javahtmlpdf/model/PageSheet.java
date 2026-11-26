package io.javahtmlpdf.model;

import io.javahtmlpdf.PageSize;

import java.util.List;

/**
 * A single fixed-size physical output page containing its fragment of laid-out
 * {@link LayoutBox}es and surrounding {@link MarginBoxSpec} headers/footers.
 */
public record PageSheet(
        int pageNumber,
        int totalPages,
        PageSize pageSize,
        Edges marginsPx,
        List<LayoutBox> contentBoxes,
        List<MarginBoxSpec> marginBoxes
) {
    public PageSheet {
        contentBoxes = contentBoxes == null ? List.of() : List.copyOf(contentBoxes);
        marginBoxes = marginBoxes == null ? List.of() : List.copyOf(marginBoxes);
    }
}
