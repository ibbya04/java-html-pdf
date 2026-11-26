package io.javahtmlpdf.css;

import io.javahtmlpdf.model.MarginBoxSpec;

import java.util.List;
import java.util.Map;

/**
 * Parsed stylesheet containing regular selector rules, {@code @page} declarations,
 * and {@code @page} margin-box rules ({@code @top-left}, {@code @bottom-right}, etc.).
 */
public record CssStylesheet(
        List<CssRule> rules,
        Map<String, String> pageDeclarations,
        Map<MarginBoxSpec.Position, Map<String, String>> pageMarginBoxes
) {
    public static final CssStylesheet EMPTY = new CssStylesheet(List.of(), Map.of(), Map.of());
}
