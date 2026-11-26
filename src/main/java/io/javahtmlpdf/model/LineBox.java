package io.javahtmlpdf.model;

import java.util.List;

/**
 * A horizontal strip inside an inline Formatting Context holding one wrapped line of {@link InlineRun}s.
 */
public record LineBox(
        float x,
        float y,
        float width,
        float height,
        float baselineY,
        List<InlineRun> runs
) {
    public LineBox {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }
}
