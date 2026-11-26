package io.javahtmlpdf.css;

import java.util.List;
import java.util.Map;

/**
 * Parsed stylesheet containing regular selector rules and optional {@code @page} declarations.
 */
public record CssStylesheet(
        List<CssRule> rules,
        Map<String, String> pageDeclarations
) {
    public static final CssStylesheet EMPTY = new CssStylesheet(List.of(), Map.of());
}
